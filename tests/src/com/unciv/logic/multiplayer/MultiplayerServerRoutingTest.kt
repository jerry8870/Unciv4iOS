package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkException
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkError
import com.unciv.logic.multiplayer.storage.MultiplayerGameCreationPartialException
import com.unciv.logic.multiplayer.storage.MultiplayerGameCreationCancelledException
import com.unciv.logic.multiplayer.storage.MultiplayerFullGameUploadOutcomeUnknownException
import com.unciv.logic.multiplayer.storage.MultiplayerRequestCancelledException
import com.unciv.logic.multiplayer.storage.MultiplayerV1Request
import com.unciv.logic.multiplayer.storage.MultiplayerV1Response
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.logic.multiplayer.ServerFeatureSet
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(BaseTestRunner::class)
class MultiplayerServerRoutingTest {
    private val transport = FailingTransport()
    private lateinit var multiplayer: Multiplayer
    private lateinit var dataDirectory: Path

    @Before
    fun setUp() {
        dataDirectory = Files.createTempDirectory("unciv-multiplayer-routing-")
        UncivGame.Current = StrictHttpsGame().apply {
            settings = GameSettings().apply {
                multiplayer.setServer(CURRENT_SERVER)
            }
            files = UncivFiles(Gdx.files, dataDirectory.toString())
        }
        multiplayer = Multiplayer(transport, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        multiplayer.pause()
        dataDirectory.toFile().deleteRecursively()
        UncivGame.Current = UncivGame()
    }

    @Test
    fun `new game pins the validated current server before upload`() = runBlocking {
        val game = game(sourceServer = null)

        val exception = try {
            multiplayer.createGame(game)
            throw AssertionError("Expected an unconfirmed creation result")
        } catch (exception: MultiplayerGameCreationPartialException) {
            exception
        }

        assertEquals(CURRENT_SERVER, game.gameParameters.multiplayerServerUrl)
        assertEquals("$CURRENT_SERVER/files/game-id", transport.firstUrl())
        assertEquals(false, exception.fullUploadConfirmed)
        assertTrue(exception.localRecoveryAvailable)
    }

    @Test
    fun `existing game upload ignores a later global server change`() = runBlocking {
        val game = game(PINNED_SERVER)

        expectNetworkFailure { multiplayer.updateGame(game) }

        assertEquals("$PINNED_SERVER/files/game-id", transport.singleUrl())
    }

    @Test
    fun `preview repair uses only the pinned preview route`() = runBlocking {
        val game = game(PINNED_SERVER)

        expectNetworkFailure { multiplayer.updateGamePreview(game) }

        assertEquals("$PINNED_SERVER/files/game-id_Preview", transport.singleUrl())
    }

    @Test
    fun `authentication cannot switch destination while request is starting`() = runBlocking {
        val switchingTransport = SwitchingTransport {
            UncivGame.Current.settings.multiplayer.setServer(OTHER_SERVER)
        }
        val server = MultiplayerServer(
            transport = switchingTransport,
            networkDispatcher = Dispatchers.Unconfined,
        ).apply { setFeatureSet(ServerFeatureSet(authVersion = 1)) }

        assertEquals(true, server.authenticate("new-secret", authRequired = true))

        assertEquals("$CURRENT_SERVER/auth", switchingTransport.singleUrl())
        assertEquals("new-secret", UncivGame.Current.settings.multiplayer.getPassword(CURRENT_SERVER))
        assertEquals(null, UncivGame.Current.settings.multiplayer.getPassword(OTHER_SERVER))
    }

    @Test
    fun `password change cannot switch destination while request is starting`() = runBlocking {
        UncivGame.Current.settings.multiplayer.setPassword(CURRENT_SERVER, "old-secret")
        val switchingTransport = SwitchingTransport {
            UncivGame.Current.settings.multiplayer.setServer(OTHER_SERVER)
        }
        val server = MultiplayerServer(
            transport = switchingTransport,
            networkDispatcher = Dispatchers.Unconfined,
        ).apply { setFeatureSet(ServerFeatureSet(authVersion = 1)) }

        assertEquals(true, server.setPassword("new-secret"))

        assertEquals("$CURRENT_SERVER/auth", switchingTransport.singleUrl())
        assertEquals("new-secret", UncivGame.Current.settings.multiplayer.getPassword(CURRENT_SERVER))
        assertEquals(null, UncivGame.Current.settings.multiplayer.getPassword(OTHER_SERVER))
    }

    @Test
    fun `connection check never carries authentication across redirect origins`() = runBlocking {
        val settings = UncivGame.Current.settings.multiplayer
        settings.setServer(PINNED_SERVER)
        settings.setPassword(PINNED_SERVER, "first-secret")
        settings.setPassword(OTHER_SERVER, "redirect-secret")
        val requests = CopyOnWriteArrayList<MultiplayerV1Request>()
        val redirectingTransport = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requests += request
                return if (request.url.endsWith("/isalive")) {
                    settings.setServer(CURRENT_SERVER)
                    MultiplayerV1Response(
                        200,
                        """{"authVersion":1}""",
                        emptyMap(),
                        "$OTHER_SERVER/isalive",
                    )
                } else MultiplayerV1Response(200, "", emptyMap(), request.url)
            }
        }
        val server = MultiplayerServer(
            fileStorageIdentifier = PINNED_SERVER,
            transport = redirectingTransport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        val exception = try {
            server.checkServerStatus()
            throw AssertionError("Expected a cross-origin redirect to be rejected")
        } catch (exception: MultiplayerNetworkException) {
            exception
        }

        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, exception.error)
        assertEquals(listOf("$PINNED_SERVER/isalive"), requests.map { it.url })
        assertEquals(CURRENT_SERVER, settings.getServer())
    }

    @Test
    fun `checksum is verified before a redirected source is stamped`() = runBlocking {
        val configuredGame = UncivGame.Current
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        val original = testGame.gameInfo.apply {
            gameId = "game-id"
            currentPlayer = player.civID
            turns = 10
            currentTurnStartTime = 1_000
            historyStartTurn = -1
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = OTHER_SERVER
        }
        val payload = UncivFiles.gameInfoToString(original, forceZip = true, updateChecksum = true)
        val originalChecksum = original.checksum
        UncivGame.Current = configuredGame
        val server = MultiplayerServer(
            fileStorageIdentifier = PINNED_SERVER,
            transport = PayloadTransport(payload),
            networkDispatcher = Dispatchers.Unconfined,
        )

        val downloaded = server.tryDownloadVerifiedGame(original.gameId)

        assertEquals(originalChecksum, downloaded.checksum)
        assertEquals(PINNED_SERVER, downloaded.gameParameters.multiplayerServerUrl)
        assertEquals(10, downloaded.historyStartTurn)
        val pending = PendingTurnUploadState().getOrCreate(original) {
            original.clone().apply {
                currentPlayer = "Egypt"
                turns = 11
                currentTurnStartTime = 2_000
            }
        }
        assertEquals(
            PendingTurnUploadResolution.RetryUpload,
            pending.resolve(
                downloaded,
                original.asPreview(),
                checksumVerifiedBeforeSourceStamping = true,
            ),
        )
    }

    @Test
    fun `downloaded full game must match the requested id`() = runBlocking {
        val configuredGame = UncivGame.Current
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        val payloadGame = testGame.gameInfo.apply {
            gameId = "other-game"
            currentPlayer = player.civID
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = OTHER_SERVER
        }
        val payload = UncivFiles.gameInfoToString(
            payloadGame,
            forceZip = true,
            updateChecksum = true,
        )
        UncivGame.Current = configuredGame
        val server = MultiplayerServer(
            fileStorageIdentifier = PINNED_SERVER,
            transport = PayloadTransport(payload),
            networkDispatcher = Dispatchers.Unconfined,
        )

        val exception = try {
            server.tryDownloadGame("requested-game")
            throw AssertionError("Expected a mismatched game id to be rejected")
        } catch (exception: MultiplayerNetworkException) {
            exception
        }

        assertEquals(MultiplayerNetworkError.INVALID_RESPONSE, exception.error)
    }

    @Test
    fun `iOS API v1 rejects a full game with a stale checksum`() = runBlocking {
        val configuredGame = UncivGame.Current
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        val payloadGame = testGame.gameInfo.apply {
            gameId = "game-id"
            currentPlayer = player.civID
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = CURRENT_SERVER
            checksum = calculateChecksum()
            turns++
        }
        val payload = UncivFiles.gameInfoToString(payloadGame, forceZip = true)
        UncivGame.Current = configuredGame
        val server = MultiplayerServer(
            fileStorageIdentifier = CURRENT_SERVER,
            transport = PayloadTransport(payload),
            networkDispatcher = Dispatchers.Unconfined,
        )

        val exception = try {
            server.tryDownloadGame(payloadGame.gameId)
            throw AssertionError("Expected a stale checksum to be rejected")
        } catch (exception: MultiplayerNetworkException) {
            exception
        }

        assertEquals(MultiplayerNetworkError.INVALID_RESPONSE, exception.error)
    }

    @Test
    fun `iOS API v1 accepts a valid full game when JSON member order changes`() = runBlocking {
        val configuredGame = UncivGame.Current
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        val payloadGame = testGame.gameInfo.apply {
            gameId = "game-id"
            currentPlayer = player.civID
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = CURRENT_SERVER
            checksum = ""
        }

        val jsonRoot = JsonReader().parse(json().toJson(payloadGame))
        val gameParameters = jsonRoot.get("gameParameters")
        val firstParameter = gameParameters.child
        gameParameters.remove(firstParameter.name)
        gameParameters.addChild(firstParameter)
        val unsignedJson = jsonRoot.toJson(JsonWriter.OutputType.json)
        val checksum = FileConversions.encode(
            MessageDigest.getInstance("SHA-1").digest(unsignedJson.toByteArray(Charsets.UTF_8))
        )
        val signedJson = "{\"checksum\":\"$checksum\"," + unsignedJson.drop(1)
        val payload = FileConversions.zip(signedJson)

        val normallyParsedGame = UncivFiles.gameInfoFromString(payload)
        assertNotEquals(checksum, normallyParsedGame.calculateChecksum())

        UncivGame.Current = configuredGame
        val server = MultiplayerServer(
            fileStorageIdentifier = CURRENT_SERVER,
            transport = PayloadTransport(payload),
            networkDispatcher = Dispatchers.Unconfined,
        )

        val downloadedGame = server.tryDownloadGame(payloadGame.gameId)

        assertEquals(payloadGame.gameId, downloadedGame.gameId)
        assertEquals(checksum, downloadedGame.checksum)
    }

    @Test
    fun `iOS existing game without a pinned source fails before transport`() = runBlocking {
        val game = game(sourceServer = null)

        expectNetworkFailure { multiplayer.updateGame(game) }

        assertEquals(0, transport.requestCount())
    }

    @Test
    fun `existing game preview check uses its pinned server`() = runBlocking {
        val game = game(PINNED_SERVER)

        expectNetworkFailure { multiplayer.downloadGame(game) }

        assertEquals("$PINNED_SERVER/files/game-id_Preview", transport.singleUrl())
    }

    @Test
    fun `saved preview full download uses its pinned server`() = runBlocking {
        val preview = game(PINNED_SERVER).asPreview()
        val savedGame = MultiplayerGamePreview(FileHandle("unused-preview"), preview)

        expectNetworkFailure { multiplayer.downloadGame(savedGame) }

        assertEquals("$PINNED_SERVER/files/game-id", transport.singleUrl())
    }

    @Test
    fun `same game id on different servers is saved independently`() = runBlocking {
        val previewPayload = UncivFiles.gameInfoToString(game(CURRENT_SERVER).asPreview())
        transport.succeedWith(previewPayload)

        multiplayer.addGame("game-id")
        UncivGame.Current.settings.multiplayer.setServer(OTHER_SERVER)
        multiplayer.addGame("game-id")

        val serverAGame = multiplayer.multiplayerFiles.getGameByGameId("game-id", CURRENT_SERVER)
        val serverBGame = multiplayer.multiplayerFiles.getGameByGameId("game-id", OTHER_SERVER)
        assertEquals(CURRENT_SERVER, serverAGame?.preview?.gameParameters?.multiplayerServerUrl)
        assertEquals(OTHER_SERVER, serverBGame?.preview?.gameParameters?.multiplayerServerUrl)
        assertNotEquals(serverAGame?.name, serverBGame?.name)
        assertEquals(2, multiplayer.games.size)
    }

    @Test
    fun `rename cannot overwrite another multiplayer game`() = runBlocking {
        val firstPreview = game(CURRENT_SERVER).apply { gameId = "first-id" }.asPreview()
        val secondPreview = game(PINNED_SERVER).apply { gameId = "second-id" }.asPreview()
        transport.succeedWith(UncivFiles.gameInfoToString(firstPreview))
        multiplayer.addGame("first-id", "first")
        UncivGame.Current.settings.multiplayer.setServer(PINNED_SERVER)
        transport.succeedWith(UncivFiles.gameInfoToString(secondPreview))
        multiplayer.addGame("second-id", "second")
        val first = multiplayer.multiplayerFiles.getGameByName("first")!!
        var failure: Exception? = null

        val renamed = multiplayer.multiplayerFiles.changeGameName(first, "second") {
            failure = it
        }

        assertFalse(renamed)
        assertNotNull(failure)
        assertNotNull(multiplayer.multiplayerFiles.getGameByName("first"))
        assertNotNull(multiplayer.multiplayerFiles.getGameByName("second"))
    }

    @Test
    fun `preview failure after create preserves the exact remote game locally`() = runBlocking {
        val partialTransport = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                val status = if (request.url.endsWith("_Preview")) 503 else 200
                return MultiplayerV1Response(status, "", emptyMap(), request.url)
            }
        }
        val partialMultiplayer = Multiplayer(partialTransport, Dispatchers.Unconfined)
        val createdGame = validOnlineGame(sourceServer = null)

        val exception = try {
            partialMultiplayer.createGame(createdGame)
            throw AssertionError("Expected a partial creation result")
        } catch (exception: MultiplayerGameCreationPartialException) {
            exception
        }

        assertEquals(createdGame.gameId, exception.gameId)
        assertEquals(true, exception.fullUploadConfirmed)
        assertEquals(false, exception.previewConfirmed)
        assertTrue(exception.localRecoveryAvailable)
        val restoredCreation = PendingTurnUploadState.forGame(createdGame, UncivGame.Current.files)
        assertTrue(restoredCreation.requiresRecovery())
        assertTrue(restoredCreation.get()?.isCreationIntent == true)
        assertEquals(createdGame.gameId, restoredCreation.get()?.pendingGame?.gameId)
        assertEquals(createdGame.currentPlayer, restoredCreation.get()?.pendingGame?.currentPlayer)
        assertNotNull(
            partialMultiplayer.multiplayerFiles.getGameByGameId(
                createdGame.gameId,
                CURRENT_SERVER,
            )
        )
    }

    @Test
    fun `lost full upload response is confirmed before finishing creation`() = runBlocking {
        val configuredGame = UncivGame.Current
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        val createdGame = testGame.gameInfo.apply {
            gameId = "game-id"
            currentPlayer = player.civID
            gameParameters.isOnlineMultiplayer = true
        }
        UncivGame.Current = configuredGame
        var uploadedFullGame = ""
        val responseLossTransport = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                if (request.method == com.unciv.logic.multiplayer.storage.MultiplayerV1HttpMethod.PUT
                    && !request.url.endsWith("_Preview")
                ) {
                    uploadedFullGame = request.body.orEmpty()
                    throw MultiplayerNetworkException(MultiplayerNetworkError.READ_TIMEOUT)
                }
                val body = if (request.method ==
                    com.unciv.logic.multiplayer.storage.MultiplayerV1HttpMethod.GET
                ) uploadedFullGame else ""
                return MultiplayerV1Response(200, body, emptyMap(), request.url)
            }
        }
        val responseLossMultiplayer = Multiplayer(responseLossTransport, Dispatchers.Unconfined)

        responseLossMultiplayer.createGame(createdGame)

        assertNotNull(
            responseLossMultiplayer.multiplayerFiles.getGameByGameId(
                createdGame.gameId,
                CURRENT_SERVER,
            )
        )
    }

    @Test
    fun `cancelled full create remains cancellation and preserves local recovery`() = runBlocking {
        val createdGame = validOnlineGame(sourceServer = null)
        var recoveryExistedBeforeFirstRequest = false
        val cancelledTransport = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                recoveryExistedBeforeFirstRequest = PendingTurnUploadState
                    .forGame(createdGame, UncivGame.Current.files)
                    .requiresRecovery()
                throw MultiplayerRequestCancelledException()
            }
        }
        val cancelledMultiplayer = Multiplayer(cancelledTransport, Dispatchers.Unconfined)

        val exception = try {
            cancelledMultiplayer.createGame(createdGame)
            throw AssertionError("Expected creation cancellation")
        } catch (exception: MultiplayerGameCreationCancelledException) {
            exception
        }

        assertEquals(createdGame.gameId, exception.gameId)
        assertEquals(false, exception.fullUploadConfirmed)
        assertTrue(exception.localRecoveryAvailable)
        assertTrue(recoveryExistedBeforeFirstRequest)
        val restoredCreation = PendingTurnUploadState.forGame(createdGame, UncivGame.Current.files)
        assertTrue(restoredCreation.requiresRecovery())
        assertTrue(restoredCreation.get()?.isCreationIntent == true)
        assertEquals(createdGame.gameId, restoredCreation.get()?.pendingGame?.gameId)
        assertEquals(createdGame.currentPlayer, restoredCreation.get()?.pendingGame?.currentPlayer)
    }

    @Test
    fun `cancelled preview create records that full upload was confirmed`() = runBlocking {
        val cancelledPreviewTransport = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                if (request.url.endsWith("_Preview")) throw MultiplayerRequestCancelledException()
                return MultiplayerV1Response(200, "", emptyMap(), request.url)
            }
        }
        val cancelledMultiplayer = Multiplayer(cancelledPreviewTransport, Dispatchers.Unconfined)
        val createdGame = game(sourceServer = null)

        val exception = try {
            cancelledMultiplayer.createGame(createdGame)
            throw AssertionError("Expected creation cancellation")
        } catch (exception: MultiplayerGameCreationCancelledException) {
            exception
        }

        assertEquals(true, exception.fullUploadConfirmed)
        assertTrue(exception.localRecoveryAvailable)
    }

    private fun game(sourceServer: String?) = GameInfo().apply {
        gameId = "game-id"
        gameParameters.isOnlineMultiplayer = true
        gameParameters.multiplayerServerUrl = sourceServer
    }

    private fun validOnlineGame(sourceServer: String?): GameInfo {
        val configuredGame = UncivGame.Current
        return try {
            val testGame = TestGame()
            testGame.makeHexagonalMap(1)
            val player = testGame.addCiv(
                testGame.ruleset.nations.getValue("Rome"),
                isPlayer = true,
            )
            testGame.gameInfo.apply {
                gameId = "game-id"
                currentPlayer = player.civID
                gameParameters.isOnlineMultiplayer = true
                gameParameters.multiplayerServerUrl = sourceServer
            }
        } finally {
            UncivGame.Current = configuredGame
        }
    }

    private suspend fun expectNetworkFailure(action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected multiplayer request to fail")
        } catch (_: MultiplayerNetworkException) {
            // The fake returns 503 after recording the route under test.
        } catch (_: MultiplayerFullGameUploadOutcomeUnknownException) {
            // A failed full PUT has an unknown remote outcome even when the route is the assertion target.
        }
    }

    private class FailingTransport : MultiplayerV1Transport {
        private val requests = CopyOnWriteArrayList<MultiplayerV1Request>()
        @Volatile private var responseStatus = 503
        @Volatile private var responseBody = ""

        override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
            requests += request
            return MultiplayerV1Response(responseStatus, responseBody, emptyMap(), request.url)
        }

        fun succeedWith(body: String) {
            responseStatus = 200
            responseBody = body
        }

        fun singleUrl(): String = requests.single().url
        fun firstUrl(): String = requests.first().url
        fun requestCount(): Int = requests.size
    }

    private class SwitchingTransport(
        private val onRequest: () -> Unit,
    ) : MultiplayerV1Transport {
        private val requests = CopyOnWriteArrayList<MultiplayerV1Request>()

        override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
            requests += request
            onRequest()
            return MultiplayerV1Response(200, "", emptyMap(), request.url)
        }

        fun singleUrl(): String = requests.single().url
    }

    private class PayloadTransport(private val payload: String) : MultiplayerV1Transport {
        override suspend fun execute(request: MultiplayerV1Request) =
            MultiplayerV1Response(200, payload, emptyMap(), request.url)
    }

    private class StrictHttpsGame : UncivGame() {
        override val platformCapabilities = PlatformCapabilities(
            multiplayerServerRequiresHttps = true,
            multiplayerApiV1Only = true,
        )
    }

    private companion object {
        const val CURRENT_SERVER = "https://current.example"
        const val PINNED_SERVER = "https://pinned.example"
        const val OTHER_SERVER = "https://other.example"
    }
}
