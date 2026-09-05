package com.unciv.logic.multiplayer

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.UncivShowableException
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.event.EventBus
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.multiplayer.storage.MultiplayerFileNotFoundException
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkError
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkException
import com.unciv.logic.multiplayer.storage.MultiplayerGameCreationPartialException
import com.unciv.logic.multiplayer.storage.MultiplayerGameCreationCancelledException
import com.unciv.logic.multiplayer.storage.MultiplayerFullGameUploadOutcomeUnknownException
import com.unciv.logic.multiplayer.storage.MultiplayerGameUploadCancelledException
import com.unciv.logic.multiplayer.storage.MultiplayerPreviewUploadException
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import com.unciv.logic.multiplayer.storage.SimpleHttp
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.components.extensions.isLargerThan
import com.unciv.utils.Dispatcher
import com.unciv.utils.Concurrency
import com.unciv.utils.debug
import kotlinx.coroutines.*
import yairm210.purity.annotations.Readonly
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap


/**
 * How often files can be checked for new multiplayer games (could be that the user modified their file system directly). More checks within this time period
 * will do nothing.
 */
private val FILE_UPDATE_THROTTLE_PERIOD = Duration.ofSeconds(60)

/**
 * Provides *online* multiplayer functionality to the rest of the game.
 * Multiplayer data is a mix of local files ([multiplayerFiles]) and server data ([multiplayerServer]).
 * This class handles functions that require a mix of both.
 *
 * See the file of [com.unciv.logic.multiplayer.HasMultiplayerGameName] for all available [EventBus] events.
 */
class Multiplayer(
    internal val multiplayerV1Transport: MultiplayerV1Transport = SimpleHttp(),
    internal val multiplayerNetworkDispatcher: CoroutineDispatcher = Dispatcher.DAEMON,
) {
    private val foregroundTransport = ForegroundMultiplayerTransport(multiplayerV1Transport)
    /** Handles SERVER DATA only */
    val multiplayerServer = MultiplayerServer(
        transport = foregroundTransport,
        networkDispatcher = multiplayerNetworkDispatcher,
    )
    /** Handles LOCAL FILES only */
    val multiplayerFiles = MultiplayerFiles()


    private val lastFileUpdate: AtomicReference<Instant?> = AtomicReference()
    private val lastAllGamesRefresh: AtomicReference<Instant?> = AtomicReference()
    private val lastCurGameRefresh: AtomicReference<Instant?> = AtomicReference()
    private val pendingTurnUploadStates = ConcurrentHashMap<String, PendingTurnUploadState>()

    val games: Set<MultiplayerGamePreview> get() = multiplayerFiles.savedGames.values.toSet()
    private val multiplayerGameUpdater = MultiplayerUpdater { forceUpdate ->
        Concurrency.run("Multiplayer updater") { runMultiplayerUpdater(forceUpdate) }
    }

    internal fun serverFor(fileStorageIdentifier: String?): MultiplayerServer {
        if (fileStorageIdentifier == null
            && UncivGame.Current.platformCapabilities.multiplayerServerRequiresHttps
        ) {
            // iOS has no trustworthy source for a legacy save that predates source pinning.
            // Only the explicit "join by Game ID" path may select the current global server.
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
        }
        return MultiplayerServer(
            fileStorageIdentifier = fileStorageIdentifier,
            transport = foregroundTransport,
            networkDispatcher = multiplayerNetworkDispatcher,
        )
    }

    internal fun pendingTurnUploadStateFor(gameInfo: GameInfo): PendingTurnUploadState {
        if (!gameInfo.gameParameters.isOnlineMultiplayer) return PendingTurnUploadState()
        if (gameInfo.gameParameters.multiplayerServerUrl == null
            && !UncivGame.Current.platformCapabilities.multiplayerServerRequiresHttps
        ) {
            // Preserve the legacy current-server behavior once, then keep this game pinned.
            gameInfo.gameParameters.multiplayerServerUrl =
                UncivGame.Current.settings.multiplayer.getServer().trimEnd('/')
        }
        return pendingTurnUploadStateFor(
            gameInfo.gameId,
            gameInfo.gameParameters.multiplayerServerUrl,
        )
    }

    private fun pendingTurnUploadStateFor(
        gameId: String,
        serverUrl: String?,
    ): PendingTurnUploadState {
        val key = "${serverUrl?.trimEnd('/')}\n$gameId"
        return synchronized(pendingTurnUploadStates) {
            val existing = pendingTurnUploadStates[key]
            if (existing != null) return@synchronized existing

            val created = PendingTurnUploadState.forRemoteGame(
                gameId,
                serverUrl,
                UncivGame.Current.files,
            )
            pendingTurnUploadStates[key] = created
            created
        }
    }

    internal suspend fun <T> withTurnUploadContinuation(block: suspend () -> T): T =
        foregroundTransport.withTurnUploadContinuation(block)

    /** Stops foreground polling. Cancelling this job also cancels its in-flight download. */
    fun pause() {
        foregroundTransport.pause()
        multiplayerGameUpdater.pause()
    }

    /** Starts exactly one updater and immediately refreshes the current game and game list. */
    fun resume() {
        foregroundTransport.resume()
        multiplayerGameUpdater.resume()
    }

    @Readonly
    fun isUpdaterRunning() = multiplayerGameUpdater.isRunning()

    private suspend fun runMultiplayerUpdater(forceUpdate: Boolean) {
        try {
            refreshCurrentAndAllGames(forceUpdate)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            debug("Initial multiplayer refresh failed: %s", ex.message)
        }
        while (currentCoroutineContext().isActive) {
            delay(500)
            val multiplayerSettings: GameSettings.GameSettingsMultiplayer
            try { // Fails in unknown cases - cannot debug :/ This is just so it doesn't appear in GP analytics
                multiplayerSettings = UncivGame.Current.settings.multiplayer
            } catch (_: Exception) { continue }

            val currentGame = getCurrentGame()
            val preview = currentGame?.preview
            if (currentGame != null && (usesCustomServer() || preview == null || !preview.isUsersTurn())) {
                throttle(lastCurGameRefresh, multiplayerSettings.currentGameRefreshDelay, {}, {}) { currentGame.requestUpdate() }
            }

            val doNotUpdate = if (currentGame == null) listOf() else listOf(currentGame)
            throttle(lastAllGamesRefresh, multiplayerSettings.allGameRefreshDelay, {}, {}) { requestUpdate(doNotUpdate = doNotUpdate) }
        }
    }

    private suspend fun refreshCurrentAndAllGames(forceUpdate: Boolean) {
        // Populate the in-memory list first so the active game can be refreshed before the rest.
        multiplayerFiles.updateSavesFromFiles()
        val currentGame = getCurrentGame()
        currentGame?.requestUpdate(forceUpdate)
        currentCoroutineContext().ensureActive()
        requestUpdate(forceUpdate, if (currentGame == null) listOf() else listOf(currentGame))
    }

    @Readonly
    private fun getCurrentGame(): MultiplayerGamePreview? {
        val gameInfo = UncivGame.Current.gameInfo
        return if (gameInfo != null && gameInfo.gameParameters.isOnlineMultiplayer) {
            multiplayerFiles.getGameByGameId(
                gameInfo.gameId,
                gameInfo.gameParameters.multiplayerServerUrl,
                allowLegacySource = isCurrentServer(gameInfo.gameParameters.multiplayerServerUrl),
            )
        } else null
    }

    /**
     * Requests an update of all multiplayer game state. Does automatic throttling to try to prevent hitting rate limits.
     *
     * Use [forceUpdate] = true to circumvent this throttling.
     *
     * Fires: [MultiplayerGameUpdateStarted], [MultiplayerGameUpdated], [MultiplayerGameUpdateUnchanged], [MultiplayerGameUpdateFailed]
     */
    suspend fun requestUpdate(forceUpdate: Boolean = false, doNotUpdate: List<MultiplayerGamePreview> = listOf()) {
        val fileThrottleInterval = if (forceUpdate) Duration.ZERO else FILE_UPDATE_THROTTLE_PERIOD
        // An exception only happens muhere if the files can't be listed, should basically never happen
        throttle(lastFileUpdate, fileThrottleInterval, {}, {}, action = {multiplayerFiles.updateSavesFromFiles()})

        for (game in multiplayerFiles.savedGames.values.toList()) { // since updates are long, .toList for immutability
            if (game in doNotUpdate) continue
            // Any games that haven't been updated in 2 weeks (!) are inactive, don't waste your time
            if (Duration.between(Instant.ofEpochMilli(game.fileHandle.lastModified()), Instant.now())
                .isLargerThan(Duration.ofDays(14))) continue
            game.requestUpdate(forceUpdate) // DO NOT spawn in thread, since that leads to OOMs when many games try at once
        }
    }


    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     */
    suspend fun createGame(newGame: GameInfo) {
        val serverUrl = multiplayerServer.getValidatedServerUrl()
        newGame.gameParameters.multiplayerServerUrl = serverUrl
        val gameServer = serverFor(serverUrl)
        val pendingState = pendingTurnUploadStateFor(newGame)

        // A process can be suspended or killed immediately after the first PUT reaches the server.
        // Persist the exact game and a discoverable local preview before any remote write begins.
        val creationPersisted = try {
            UncivFiles.gameInfoToString(newGame, forceZip = true, updateChecksum = true)
            pendingState.getOrCreate(newGame, isCreationIntent = true) { newGame }
            true
        } catch (_: Exception) {
            false
        }
        val localPreviewSaved = if (creationPersisted) try {
            multiplayerFiles.addGame(newGame)
            true
        } catch (_: Exception) {
            false
        } else false
        val localRecoveryAvailable = creationPersisted && localPreviewSaved
        if (!localRecoveryAvailable) {
            throw MultiplayerGameCreationPartialException(
                newGame.gameId,
                fullUploadConfirmed = false,
                previewConfirmed = false,
                localRecoveryAvailable = false,
            )
        }

        var fullUploadConfirmed = false
        var previewConfirmed = false
        var uploadCancellation: CancellationException? = null
        try {
            try {
                gameServer.uploadGame(newGame, withPreview = true)
                fullUploadConfirmed = true
                previewConfirmed = true
            } catch (_: MultiplayerPreviewUploadException) {
                fullUploadConfirmed = true
                previewConfirmed = false
            } catch (_: MultiplayerFullGameUploadOutcomeUnknownException) {
                fullUploadConfirmed = try {
                    gameServer.tryDownloadVerifiedGame(newGame.gameId).checksum == newGame.checksum
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    false
                }
                previewConfirmed = false
                if (fullUploadConfirmed) {
                    try {
                        gameServer.tryUploadGamePreview(newGame.asPreview())
                        previewConfirmed = true
                    } catch (exception: CancellationException) {
                        throw MultiplayerGameUploadCancelledException(
                            fullUploadConfirmed = true,
                            cause = exception,
                        )
                    } catch (_: Exception) {
                        // The confirmed full game remains recoverable by ID.
                    }
                }
            }
        } catch (exception: CancellationException) {
            fullUploadConfirmed =
                (exception as? MultiplayerGameUploadCancelledException)?.fullUploadConfirmed
                    ?: false
            previewConfirmed = false
            uploadCancellation = exception
        }
        uploadCancellation?.let { cancellation ->
            throw MultiplayerGameCreationCancelledException(
                newGame.gameId,
                fullUploadConfirmed,
                localRecoveryAvailable,
                cancellation,
            )
        }
        if (!fullUploadConfirmed || !previewConfirmed || !localRecoveryAvailable) {
            throw MultiplayerGameCreationPartialException(
                newGame.gameId,
                fullUploadConfirmed,
                previewConfirmed,
                localRecoveryAvailable,
            )
        }
        pendingState.clear()
    }

    /**
     * @param gameName if this is null or blank, will use the gameId as the game name
     * @return the final name the game was added under
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerFileNotFoundException if the file can't be found
     */
    suspend fun addGame(gameId: String, gameName: String? = null) {
        val saveFileName = if (gameName.isNullOrBlank()) gameId else gameName
        val gamePreview: GameInfoPreview = try {
            multiplayerServer.tryDownloadGamePreview(gameId)
        } catch (_: MultiplayerFileNotFoundException) {
            // Game is so old that a preview could not be found on dropbox lets try the real gameInfo instead
            multiplayerServer.tryDownloadGame(gameId).asPreview()
        }
        multiplayerFiles.addGame(gamePreview, saveFileName)
    }


    /**
     * Resigns from the given multiplayer [game]. Can only resign if it's currently the user's turn,
     * to ensure that no one else can upload the game in the meantime.
     *
     * Fires [MultiplayerGameUpdated]
     * 
     * @param responsibleCivNameOrPlayerId Who caused the player to resign? Can be the name of a civ, or for example a player id
     *
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerFileNotFoundException if the file can't be found
     * @throws MultiplayerAuthException if the authentication failed
     * @return false if it's not the user's turn and thus resigning did not happen
     */
    suspend fun resignPlayer(game: MultiplayerGamePreview, playerCivName: String, responsibleCivNameOrPlayerId: String): String {
        val preview = game.preview ?: throw game.error!!
        val server = serverFor(preview.gameParameters.multiplayerServerUrl)
        // download to work with the latest game state
        val gameInfo = server.tryDownloadGame(preview.gameId)
        
        if (gameInfo.currentPlayer != preview.currentPlayer) {
            game.updatePreview(gameInfo.asPreview())
            return "Game was out of sync with server - updated"
        }

        val playerCiv = gameInfo.getCivilization(playerCivName)

        //Set civ info to AI
        playerCiv.playerType = PlayerType.AI
        playerCiv.playerId = ""

        //call next turn so turn gets simulated by AI
        if (gameInfo.currentPlayer == playerCivName) gameInfo.nextTurn()

        //Add notification so everyone knows what happened
        //call for every civ cause AI players are skipped anyway

        val notificationText = if (responsibleCivNameOrPlayerId == playerCivName || responsibleCivNameOrPlayerId.isEmpty()) {
            "[$playerCivName] resigned and is now controlled by AI"
        } else {
            "[$playerCivName] was forcibly resigned by [$responsibleCivNameOrPlayerId] and is now controlled by AI"
        }
        
        for (civ in gameInfo.civilizations)
            civ.addNotification(notificationText, NotificationCategory.General, playerCivName)

        server.uploadGame(gameInfo, withPreview = true)
        game.updatePreview(gameInfo.asPreview())
        return ""
    }

    /** 
     * Returns false if game was not up to date
     * Returned value indicates an error string - will be null if successful
     * We always pass in the player name to ensure if the button was clicked twice we don't skip 2 turns 
     *
     * @param responsibleCivNameOrPlayerId Who skipped the player's turn? Can be the name of a civ, or for example a player id
     */
    suspend fun skipCurrentPlayerTurn(game: MultiplayerGamePreview, playerCivName: String, responsibleCivNameOrPlayerId: String): String? {
        val preview = game.preview ?: return game.error!!.message
        val server = serverFor(preview.gameParameters.multiplayerServerUrl)
        // download to work with the latest game state
        val gameInfo: GameInfo
        try {
            gameInfo = server.tryDownloadGame(preview.gameId)
        }
        catch (ex: Exception){
            return ex.message
        }
        
        if (gameInfo.currentPlayer != preview.currentPlayer) {
            game.updatePreview(gameInfo.asPreview())
            return "The game was out of sync with the server"
        }
        
        if (gameInfo.currentPlayer != playerCivName) {
            return "Could not skip turn - current player is [${gameInfo.currentPlayer}], not [$playerCivName]"
        }

        val playerCiv = gameInfo.getCurrentPlayerCivilization()
        NextTurnAutomation.automateCivMoves(playerCiv, false)
        gameInfo.nextTurn()

        //Add notification so everyone knows what happened
        //call for every civ cause AI players are skipped anyway

        val notificationText = if (responsibleCivNameOrPlayerId == playerCivName || responsibleCivNameOrPlayerId.isEmpty()) {
            "[$playerCivName] skipped their own turn"
        } else {
            "[$playerCivName]'s turn was skipped by [$responsibleCivNameOrPlayerId]"
        }

        for (civ in gameInfo.civilizations)
            civ.addNotification(notificationText, NotificationCategory.General, playerCiv.civName)

        server.uploadGame(gameInfo, withPreview = true)
        game.updatePreview(gameInfo.asPreview())
        return null
    }

    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerFileNotFoundException if the file can't be found
     */
    suspend fun downloadGame(game: MultiplayerGamePreview) {
        val preview = game.preview ?: throw game.error!!
        val pendingCreation = pendingTurnUploadStateFor(
            preview.gameId,
            preview.gameParameters.multiplayerServerUrl,
        ).get()?.takeIf { it.isCreationIntent }?.pendingGame
        downloadGame(
            preview.gameId,
            serverFor(preview.gameParameters.multiplayerServerUrl),
            pendingCreation,
        )
    }

    /** Downloads game, and updates it locally
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerFileNotFoundException if the file can't be found
     */
    suspend fun downloadGame(gameId: String) = coroutineScope {
        downloadGame(gameId, multiplayerServer)
    }

    private suspend fun downloadGame(
        gameId: String,
        server: MultiplayerServer,
        pendingCreation: GameInfo? = null,
    ) = coroutineScope {
        val gameInfo = try {
            server.downloadGame(gameId)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            pendingCreation?.clone()?.apply {
                setTransients()
                isUpToDate = false
            } ?: throw ex
        }
        val preview = gameInfo.asPreview()
        val onlineGame = multiplayerFiles.getGameByGameId(
            gameId,
            preview.gameParameters.multiplayerServerUrl,
            allowLegacySource = isCurrentServer(preview.gameParameters.multiplayerServerUrl),
        )
        val onlinePreview = onlineGame?.preview
        if (onlineGame == null) {
            multiplayerFiles.addGame(gameInfo)
        } else if (onlinePreview != null && hasNewerGameState(preview, onlinePreview)) {
            onlineGame.updatePreview(preview)
        }
        UncivGame.Current.loadGame(gameInfo)
    }

    /**
     * Checks if the given game is current and loads it, otherwise loads the game from the server
     */
    suspend fun downloadGame(gameInfo: GameInfo) = coroutineScope {
        val gameId = gameInfo.gameId
        val server = serverFor(gameInfo.gameParameters.multiplayerServerUrl)
        val preview = server.tryDownloadGamePreview(gameId)
        if (hasLatestGameState(gameInfo, preview)) {
            val previousServer = gameInfo.gameParameters.multiplayerServerUrl
            gameInfo.gameParameters.multiplayerServerUrl = preview.gameParameters.multiplayerServerUrl
            if (previousServer != gameInfo.gameParameters.multiplayerServerUrl
                && !UncivGame.Current.files.autosaves.autoSave(gameInfo)
            ) throw UncivShowableException("Could not persist the multiplayer server for this game.")
            gameInfo.isUpToDate = true
            UncivGame.Current.loadGame(gameInfo)
        } else {
            downloadGame(gameId, server)
        }
    }




    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerFileNotFoundException if the file can't be found
     * @throws MultiplayerAuthException if the authentication failed
     */
    suspend fun updateGame(gameInfo: GameInfo) {
        debug("Updating remote game %s", gameInfo.gameId)
        serverFor(gameInfo.gameParameters.multiplayerServerUrl)
            .uploadGame(gameInfo, withPreview = true)
        persistGamePreviewLocally(gameInfo)
    }

    /** Repairs only the preview after a verified full-game PUT already reached the server. */
    suspend fun updateGamePreview(gameInfo: GameInfo) {
        val preview = gameInfo.asPreview()
        serverFor(gameInfo.gameParameters.multiplayerServerUrl).tryUploadGamePreview(preview)
        persistGamePreviewLocally(gameInfo)
    }

    internal suspend fun persistGamePreviewLocally(gameInfo: GameInfo) {
        val game = multiplayerFiles.getGameByGameId(
            gameInfo.gameId,
            gameInfo.gameParameters.multiplayerServerUrl,
            allowLegacySource = isCurrentServer(gameInfo.gameParameters.multiplayerServerUrl),
        )
        debug("Existing OnlineMultiplayerGame: %s", game)
        if (game == null) multiplayerFiles.addGame(gameInfo)
        else game.updatePreview(gameInfo.asPreview())
    }

    @Readonly
    private fun isCurrentServer(serverUrl: String?): Boolean {
        if (serverUrl == null) return false
        return serverUrl.trimEnd('/') ==
            UncivGame.Current.settings.multiplayer.getServer().trimEnd('/')
    }

    /**
     * Checks if [gameInfo] and [preview] are up-to-date with each other.
     */
    @Readonly
    fun hasLatestGameState(gameInfo: GameInfo, preview: GameInfoPreview): Boolean {
        // TODO look into how to maybe extract interfaces to not make this take two different methods
        return gameInfo.currentPlayer == preview.currentPlayer
                && gameInfo.turns == preview.turns
    }


    /**
     * Checks if [preview1] has a more recent game state than [preview2]
     */
    @Readonly
    private fun hasNewerGameState(preview1: GameInfoPreview, preview2: GameInfoPreview): Boolean {
        return preview1.turns > preview2.turns
    }

    companion object {
        fun usesCustomServer() = UncivGame.Current.settings.multiplayer.getServer() != Constants.dropboxMultiplayerServer
        fun usesDropbox() = !usesCustomServer()
    }
}

/**
 * Calls the given [action] when [lastSuccessfulExecution] lies further in the past than [throttleInterval].
 *
 * Also updates [lastSuccessfulExecution] to [Instant.now], but only when [action] did not result in an exception.
 *
 * Any exception thrown by [action] is propagated.
 *
 * @return true if the update happened
 */
suspend fun <T> throttle(
    lastSuccessfulExecution: AtomicReference<Instant?>,
    throttleInterval: Duration,
    onNoExecution: () -> T,
    onFailed: (Throwable) -> T,
    action: suspend () -> T
): T {
    val lastExecution = lastSuccessfulExecution.get()
    val now = Instant.now()
    val shouldRunAction = lastExecution == null || Duration.between(lastExecution, now).isLargerThan(throttleInterval)
    return if (shouldRunAction) {
        attemptAction(lastSuccessfulExecution, onNoExecution, onFailed, action)
    } else {
        onNoExecution()
    }
}

/**
 * Attempts to run the [action], changing [lastSuccessfulExecution], but only if no other thread changed [lastSuccessfulExecution] in the meantime
 * and [action] did not throw an exception.
 */
suspend fun <T> attemptAction(
    lastSuccessfulExecution: AtomicReference<Instant?>,
    onNoExecution: () -> T,
    onFailed: (Throwable) -> T = { throw it },
    action: suspend () -> T
): T {
    val lastExecution = lastSuccessfulExecution.get()
    val now = Instant.now()
    return if (lastSuccessfulExecution.compareAndSet(lastExecution, now)) {
        try {
            action()
        } catch (e: CancellationException) {
            lastSuccessfulExecution.compareAndSet(now, lastExecution)
            throw e
        } catch (e: Throwable) {
            lastSuccessfulExecution.compareAndSet(now, lastExecution)
            onFailed(e)
        }
    } else {
        onNoExecution()
    }
}


fun GameInfoPreview.isUsersTurn() = getCivilization(currentPlayer).playerId == UncivGame.Current.settings.multiplayer.getUserId()
fun GameInfo.isUsersTurn() = currentPlayer.isNotEmpty() && getCivilization(currentPlayer).playerId == UncivGame.Current.settings.multiplayer.getUserId()
