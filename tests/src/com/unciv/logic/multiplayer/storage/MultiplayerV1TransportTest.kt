package com.unciv.logic.multiplayer.storage

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MultiplayerV1TransportTest {
    @Before
    fun initializeGameSettings() {
        UncivGame.Current = UncivGame().apply { settings = GameSettings() }
    }

    @After
    fun clearGameSettings() {
        UncivGame.Current = UncivGame()
    }

    @Test
    fun `server status uses the injected API v1 transport`() = runBlocking {
        val transport = FakeTransport { request ->
            response(request, body = """{"authVersion":1,"chatVersion":2}""")
        }
        val server = MultiplayerServer(
            fileStorageIdentifier = "https://example.test",
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(server.checkServerStatus())
        assertEquals(1, server.getFeatureSet().authVersion)
        assertEquals(2, server.getFeatureSet().chatVersion)
        assertEquals(
            listOf(MultiplayerV1HttpMethod.GET to "https://example.test/isalive"),
            transport.requests.map { it.method to it.url },
        )
    }

    @Test
    fun `server status accepts legacy true and rejects invalid success body`() = runBlocking {
        val legacyServer = MultiplayerServer(
            fileStorageIdentifier = "https://example.test",
            transport = FakeTransport { request -> response(request, body = "  true\n") },
            networkDispatcher = Dispatchers.Unconfined,
        )
        assertTrue(legacyServer.checkServerStatus())
        assertEquals(0, legacyServer.getFeatureSet().authVersion)

        val invalidServer = MultiplayerServer(
            fileStorageIdentifier = "https://example.test",
            transport = FakeTransport { request ->
                response(request, body = "not an Unciv server; private response data")
            },
            networkDispatcher = Dispatchers.Unconfined,
        )
        val exception = expectThrows<MultiplayerNetworkException> {
            invalidServer.checkServerStatus()
        }
        assertEquals(MultiplayerNetworkError.INVALID_RESPONSE, exception.error)
        assertFalse(exception.message.orEmpty().contains("private response data"))
    }

    @Test
    fun `iOS server policy accepts public HTTPS only`() {
        val capabilities = PlatformCapabilities(
            multiplayerServerRequiresHttps = true,
            multiplayerApiV1Only = true,
        )

        assertEquals(
            "https://example.test/api",
            MultiplayerServer.validateServerUrl("https://example.test/api/", capabilities),
        )
        for (invalidUrl in listOf(
            "http://example.test",
            "example.test",
            "Dropbox",
            "https://localhost",
            "https://localhost.",
            "https://server.local",
            "https://server.local.",
            "https://server.lan",
            "https://router.home.arpa",
            "https://127.0.0.1",
            "https://10.0.0.2",
            "https://0x7f000001",
            "https://[::1]",
            "https://example.test?target=other",
            "https://example.test/#fragment",
        )) {
            expectThrowsBlocking<MultiplayerNetworkException> {
                MultiplayerServer.validateServerUrl(invalidUrl, capabilities)
            }
        }
    }

    @Test
    fun `iOS capability marks every transport request as public HTTPS only`() = runBlocking {
        UncivGame.Current = object : UncivGame() {
            override val platformCapabilities = PlatformCapabilities(
                multiplayerServerRequiresHttps = true,
                multiplayerApiV1Only = true,
            )
        }.apply { settings = GameSettings() }
        val transport = FakeTransport { request -> response(request, body = "true") }
        val server = MultiplayerServer(
            fileStorageIdentifier = "https://example.test",
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        server.checkServerStatus()
        server.fileStorage().loadFileData("game-id")

        assertTrue(transport.requests.all { it.requiresPublicHttps })
    }

    @Test
    fun `existing platforms retain HTTP custom server parsing`() {
        assertEquals(
            "http://localhost:8080",
            MultiplayerServer.validateServerUrl("http://localhost:8080/", PlatformCapabilities()),
        )
        for (invalidUrl in listOf("server.example", "file:///tmp/server", "https://example.test?q=1")) {
            expectThrowsBlocking<MultiplayerNetworkException> {
                MultiplayerServer.validateServerUrl(invalidUrl, PlatformCapabilities())
            }
        }
    }

    @Test
    fun `auth distinguishes registered unregistered and unauthorized`() = runBlocking {
        val statuses = ArrayDeque(listOf(200, 204, 401))
        val storage = storage(FakeTransport { request -> response(request, statuses.removeFirst()) })

        assertEquals(AuthStatus.VERIFIED, storage.checkAuthStatus("user", "password"))
        assertEquals(AuthStatus.UNREGISTERED, storage.checkAuthStatus("user", "password"))
        assertEquals(AuthStatus.UNAUTHORIZED, storage.checkAuthStatus("user", "password"))
    }

    @Test
    fun `file operations use API v1 methods and paths`() = runBlocking {
        val transport = FakeTransport { request ->
            response(request, body = if (request.method == MultiplayerV1HttpMethod.GET) "game-data" else "")
        }
        val storage = storage(transport)

        storage.saveFileData("game-id", "upload-data")
        assertEquals("game-data", storage.loadFileData("game-id"))
        storage.deleteFile("game-id")

        assertEquals(
            listOf(
                MultiplayerV1HttpMethod.PUT to "https://example.test/files/game-id",
                MultiplayerV1HttpMethod.GET to "https://example.test/files/game-id",
                MultiplayerV1HttpMethod.DELETE to "https://example.test/files/game-id",
            ),
            transport.requests.map { it.method to it.url },
        )
        assertEquals("upload-data", transport.requests.first().body)
    }

    @Test
    fun `downloaded preview cannot replace its validated source server`() = runBlocking {
        val serializedPreview = UncivFiles.gameInfoToString(
            GameInfo().asPreview().apply {
                gameId = "game-id"
                gameParameters.multiplayerServerUrl = "http://127.0.0.1/attacker"
            }
        )
        val transport = FakeTransport { request -> response(request, body = serializedPreview) }
        val server = MultiplayerServer(
            fileStorageIdentifier = "https://trusted.example/api/",
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        val preview = server.tryDownloadGamePreview("game-id")

        assertEquals("https://trusted.example/api", server.getValidatedServerUrl())
        assertEquals("https://trusted.example/api", preview.gameParameters.multiplayerServerUrl)
    }

    @Test
    fun `downloaded preview must match the requested id`() = runBlocking {
        val otherGame = GameInfo().apply { gameId = "other-game" }
        val previewPayload = UncivFiles.gameInfoToString(otherGame.asPreview())
        val transport = FakeTransport { request ->
            response(request, body = previewPayload)
        }
        val server = MultiplayerServer(
            fileStorageIdentifier = "https://trusted.example",
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(
            MultiplayerNetworkError.INVALID_RESPONSE,
            expectThrows<MultiplayerNetworkException> {
                server.tryDownloadGamePreview("requested-game")
            }.error,
        )
    }

    @Test
    fun `preview source stays on the server captured before a slow response`() = runBlocking {
        val firstServer = "https://first.example"
        val secondServer = "https://second.example"
        UncivGame.Current.settings.multiplayer.setServer(firstServer)
        val serializedPreview = UncivFiles.gameInfoToString(
            GameInfo().asPreview().apply { gameId = "game-id" }
        )
        val transport = FakeTransport { request ->
            assertEquals("$firstServer/files/game-id_Preview", request.url)
            UncivGame.Current.settings.multiplayer.setServer(secondServer)
            response(request, body = serializedPreview)
        }
        val server = MultiplayerServer(
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        val preview = server.tryDownloadGamePreview("game-id")

        assertEquals(firstServer, preview.gameParameters.multiplayerServerUrl)
        assertEquals(secondServer, UncivGame.Current.settings.multiplayer.getServer())
    }

    @Test
    fun `file names cannot inject another path query or fragment`() = runBlocking {
        val storage = storage(FakeTransport { request -> response(request) })

        for (invalidName in listOf(
            "../auth",
            "game?target=auth",
            "game#fragment",
            "game\\name",
            "game%2Fauth",
            "game name",
        )) {
            val exception = expectThrows<MultiplayerNetworkException> {
                storage.loadFileData(invalidName)
            }
            assertEquals(MultiplayerNetworkError.INVALID_URL, exception.error)
        }
    }

    @Test
    fun `HTTP status codes map to safe distinct failures`() = runBlocking {
        val statuses = ArrayDeque(listOf(401, 404, 429, 503))
        val transport = FakeTransport { request ->
            val status = statuses.removeFirst()
            response(
                request,
                status,
                body = "sensitive server response",
                headers = if (status == 429) mapOf("Retry-After" to listOf("17")) else emptyMap(),
            )
        }
        val storage = storage(transport)

        expectThrows<MultiplayerAuthException> { storage.saveFileData("game", "secret game") }
        expectThrows<MultiplayerFileNotFoundException> { storage.loadFileData("game") }
        assertEquals(
            17,
            expectThrows<FileStorageRateLimitReached> { storage.loadFileData("game") }
                .limitRemainingSeconds,
        )
        val serverFailure = expectThrows<MultiplayerNetworkException> {
            storage.loadFileData("game")
        }
        assertEquals(MultiplayerNetworkError.SERVER_ERROR, serverFailure.error)
        assertEquals(503, serverFailure.statusCode)
        assertFalse(serverFailure.message.orEmpty().contains("sensitive server response"))
    }

    @Test
    fun `game upload completes before preview upload begins`() = runBlocking {
        val completedUrls = CopyOnWriteArrayList<String>()
        val transport = FakeTransport { request ->
            completedUrls += request.url
            response(request)
        }
        val server = MultiplayerServer(
            fileStorageIdentifier = "https://example.test",
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )
        val game = GameInfo().apply { gameId = "game-id" }

        server.uploadGame(game, withPreview = true)

        assertEquals(
            listOf(
                "https://example.test/files/game-id",
                "https://example.test/files/game-id_Preview",
            ),
            completedUrls,
        )
    }

    @Test
    fun `failed game upload never starts preview upload`() = runBlocking {
        val transport = FakeTransport { request -> response(request, statusCode = 503) }
        val server = MultiplayerServer(
            fileStorageIdentifier = "https://example.test",
            transport = transport,
            networkDispatcher = Dispatchers.Unconfined,
        )

        val exception = expectThrows<MultiplayerFullGameUploadOutcomeUnknownException> {
            server.uploadGame(GameInfo().apply { gameId = "game-id" }, withPreview = true)
        }

        assertTrue(exception.cause is MultiplayerNetworkException)
        assertEquals(listOf("https://example.test/files/game-id"), transport.requests.map { it.url })
    }

    @Test
    fun `concurrent servers keep URL and authorization isolated`() = runBlocking {
        val transport = FakeTransport { request -> response(request) }
        val first = storage(
            transport,
            serverUrl = "https://one.example",
            authHeader = mapOf("Authorization" to "Basic first"),
        )
        val second = storage(
            transport,
            serverUrl = "https://two.example",
            authHeader = mapOf("Authorization" to "Basic second"),
        )

        coroutineScope {
            val firstRequest = async { first.saveFileData("first-game", "first-data") }
            val secondRequest = async { second.saveFileData("second-game", "second-data") }
            firstRequest.await()
            secondRequest.await()
        }

        assertEquals(
            setOf(
                "https://one.example/files/first-game" to "Basic first",
                "https://two.example/files/second-game" to "Basic second",
            ),
            transport.requests.map {
                it.url to it.headers.entries.first { header ->
                    header.key.equals("Authorization", ignoreCase = true)
                }.value
            }.toSet(),
        )
    }

    @Test
    fun `storage invokes transport away from caller GL thread`() {
        val callerExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "GL-test") }
        val networkExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "network-test") }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        val networkDispatcher = networkExecutor.asCoroutineDispatcher()
        try {
            var transportThread = ""
            val storage = storage(
                FakeTransport { request ->
                    transportThread = Thread.currentThread().name
                    response(request, body = "game-data")
                },
                networkDispatcher = networkDispatcher,
            )

            runBlocking(callerDispatcher) { storage.loadFileData("game-id") }

            assertTrue(transportThread.startsWith("network-test"))
            assertFalse(transportThread.startsWith("GL-test"))
        } finally {
            callerDispatcher.close()
            networkDispatcher.close()
        }
    }

    private fun storage(
        transport: MultiplayerV1Transport,
        serverUrl: String = "https://example.test",
        authHeader: Map<String, String> = mapOf("Authorization" to "Basic hidden"),
        networkDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = UncivServerFileStorage(
        serverUrl = serverUrl,
        authHeaderProvider = { authHeader },
        transport = transport,
        networkDispatcher = networkDispatcher,
    )

    private class FakeTransport(
        private val handler: suspend (MultiplayerV1Request) -> MultiplayerV1Response,
    ) : MultiplayerV1Transport {
        val requests = CopyOnWriteArrayList<MultiplayerV1Request>()

        override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
            requests += request
            return handler(request)
        }
    }

    private fun response(
        request: MultiplayerV1Request,
        statusCode: Int = 200,
        body: String = "",
        headers: Map<String, List<String>> = emptyMap(),
    ) = MultiplayerV1Response(
        statusCode = statusCode,
        body = body,
        headers = headers,
        finalUrl = request.url,
    )

    private suspend inline fun <reified T : Throwable> expectThrows(
        crossinline action: suspend () -> Unit,
    ): T {
        try {
            action()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private inline fun <reified T : Throwable> expectThrowsBlocking(action: () -> Unit): T {
        try {
            action()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }
}
