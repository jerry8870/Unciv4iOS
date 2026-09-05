package com.unciv.logic.multiplayer

import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.UncivFiles
import com.unciv.json.json
import com.unciv.utils.Log
import yairm210.purity.annotations.Readonly
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class PendingTurnUploadResolution {
    Confirmed,
    RetryUpload,
    RepairPreview,
    ServerChanged,
    InvalidServerGame,
}

enum class TurnUploadUnconfirmedReason {
    UploadFailed,
    AuthenticationCancelled,
    BackgroundTaskExpired,
}

class PendingTurnUpload private constructor(
    private val originalState: TurnUploadState,
    private val originalFingerprint: String,
    val pendingGame: GameInfo,
    val isCreationIntent: Boolean,
) {
    private val pendingState = pendingGame.turnUploadState()
    private val pendingFingerprint: String
    private val pendingPreviewFingerprint: String

    internal constructor(
        originalGame: GameInfo,
        pendingGame: GameInfo,
        isCreationIntent: Boolean = false,
    ) : this(
        originalGame.turnUploadState(),
        originalGame.checksum,
        pendingGame,
        isCreationIntent,
    )

    init {
        // Match the exact full-game representation used by MultiplayerServer.uploadGame().
        pendingGame.version = CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION
        pendingFingerprint = pendingGame.calculateChecksum()
        pendingGame.checksum = pendingFingerprint
        pendingPreviewFingerprint = pendingGame.asPreview().previewFingerprint()
    }

    fun resolve(
        serverGame: GameInfo,
        serverPreview: GameInfoPreview?,
        checksumVerifiedBeforeSourceStamping: Boolean = false,
    ): PendingTurnUploadResolution {
        val serverFingerprint = serverGame.checksum
        if (serverFingerprint.isBlank()
            || !checksumVerifiedBeforeSourceStamping && serverFingerprint != serverGame.calculateChecksum()
        ) {
            return PendingTurnUploadResolution.InvalidServerGame
        }

        val previewState = serverPreview?.turnUploadState()
        return when {
            serverFingerprint == pendingFingerprint
                    && serverPreview?.previewFingerprint() == pendingPreviewFingerprint ->
                PendingTurnUploadResolution.Confirmed
            serverFingerprint == pendingFingerprint ->
                PendingTurnUploadResolution.RepairPreview
            originalFingerprint.isNotBlank()
                    && serverFingerprint == originalFingerprint
                    && (previewState == null || previewState == originalState) ->
                PendingTurnUploadResolution.RetryUpload
            else -> PendingTurnUploadResolution.ServerChanged
        }
    }

    internal fun asRecord(reason: TurnUploadUnconfirmedReason?) = PendingTurnUploadRecord().also {
        it.originalPlayer = originalState.currentPlayer
        it.originalTurns = originalState.turns
        it.originalTurnStartTime = originalState.currentTurnStartTime
        it.originalFingerprint = originalFingerprint
        it.pendingGameData = UncivFiles.gameInfoToString(pendingGame, forceZip = true)
        it.serverUrl = pendingGame.gameParameters.multiplayerServerUrl.orEmpty()
        it.isCreationIntent = isCreationIntent
        // Any process restart makes an in-flight write uncertain, even if no failure was observed.
        it.unconfirmedReason = (reason ?: TurnUploadUnconfirmedReason.UploadFailed).name
    }

    internal companion object {
        fun fromRecord(
            record: PendingTurnUploadRecord,
            expectedGameId: String,
            expectedServerUrl: String?,
        ): PendingTurnUpload {
            val pendingGame = UncivFiles.gameInfoFromString(
                record.pendingGameData,
                validateChecksumBeforeMigrations = true,
            )
            if (pendingGame.gameId != expectedGameId
                || record.serverUrl.normalizedServerUrl() != expectedServerUrl.normalizedServerUrl()
                || pendingGame.gameParameters.multiplayerServerUrl.normalizedServerUrl()
                    != expectedServerUrl.normalizedServerUrl()
            ) throw PendingTurnPersistenceException()

            return PendingTurnUpload(
                TurnUploadState(
                    record.originalPlayer,
                    record.originalTurns,
                    record.originalTurnStartTime,
                ),
                record.originalFingerprint,
                pendingGame,
                record.isCreationIntent,
            )
        }
    }
}

/** Keeps one advanced clone alive until its upload is confirmed or superseded by the server. */
class PendingTurnUploadState private constructor(
    private val store: PendingTurnUploadStore?,
    private var pendingTurn: PendingTurnUpload?,
    initialReason: TurnUploadUnconfirmedReason?,
    private var recoveryFailed: Boolean,
) {
    constructor() : this(null, null, null, false)

    private val turnProcessing = AtomicBoolean(false)
    @Volatile
    var unconfirmedReason: TurnUploadUnconfirmedReason? = initialReason
        private set

    /** Single-flight gate shared by normal next-turn and retry entry points. */
    fun tryStartTurnProcessing(): Boolean = turnProcessing.compareAndSet(false, true)

    fun finishTurnProcessing() {
        turnProcessing.set(false)
    }

    @Readonly @Suppress("purity")
    fun isTurnProcessing(): Boolean = turnProcessing.get()

    @Synchronized
    fun getOrCreate(
        originalGame: GameInfo,
        isCreationIntent: Boolean = false,
        createPendingGame: () -> GameInfo,
    ): PendingTurnUpload {
        if (recoveryFailed) throw PendingTurnPersistenceException()
        val existing = pendingTurn
        if (existing != null) {
            if (store != null && !store.save(existing, unconfirmedReason)) {
                throw PendingTurnPersistenceException()
            }
            return existing
        }

        val created = PendingTurnUpload(originalGame, createPendingGame(), isCreationIntent)
        pendingTurn = created
        if (store != null && !store.save(created, unconfirmedReason)) {
            unconfirmedReason = TurnUploadUnconfirmedReason.UploadFailed
            throw PendingTurnPersistenceException()
        }
        return created
    }

    @Synchronized
    fun get(): PendingTurnUpload? = pendingTurn

    @Synchronized
    fun markUnconfirmed(reason: TurnUploadUnconfirmedReason): Boolean {
        if (pendingTurn == null) return false
        if (unconfirmedReason == reason) return true
        unconfirmedReason = reason
        store?.save(pendingTurn!!, reason)
        return true
    }

    @Synchronized
    fun markBackgroundTaskExpired() {
        // Do not cancel a PUT: supported servers may stream directly into the destination file,
        // and client-side cancellation could leave the previous valid game truncated.
        // The full pending game was persisted before the request. Do not serialize it again from
        // iOS's expiration callback, where the background execution window is already exhausted.
        if (pendingTurn != null) unconfirmedReason = TurnUploadUnconfirmedReason.BackgroundTaskExpired
    }

    @Synchronized
    fun ensureUnconfirmed(reason: TurnUploadUnconfirmedReason) {
        if (pendingTurn != null && unconfirmedReason == null) markUnconfirmed(reason)
    }

    @Synchronized
    fun clear() {
        store?.clear()
        pendingTurn = null
        unconfirmedReason = null
        recoveryFailed = false
    }

    @Readonly
    fun requiresRecovery(): Boolean = recoveryFailed || pendingTurn != null

    @Readonly
    fun hasRecoveryFailure(): Boolean = recoveryFailed

    companion object {
        fun forGame(gameInfo: GameInfo, files: UncivFiles): PendingTurnUploadState {
            if (!gameInfo.gameParameters.isOnlineMultiplayer) return PendingTurnUploadState()

            return forRemoteGame(
                gameInfo.gameId,
                gameInfo.gameParameters.multiplayerServerUrl,
                files,
            )
        }

        fun forRemoteGame(
            gameId: String,
            serverUrl: String?,
            files: UncivFiles,
        ): PendingTurnUploadState {
            val store = PendingTurnUploadStore(
                files,
                gameId,
                serverUrl,
            )
            return when (val loaded = store.load()) {
                PendingTurnLoadResult.Missing -> PendingTurnUploadState(store, null, null, false)
                PendingTurnLoadResult.Corrupt -> PendingTurnUploadState(
                    store,
                    null,
                    TurnUploadUnconfirmedReason.UploadFailed,
                    true,
                )
                is PendingTurnLoadResult.Loaded -> PendingTurnUploadState(
                    store,
                    loaded.pendingTurn,
                    loaded.reason,
                    false,
                )
            }
        }
    }
}

class PendingTurnPersistenceException : UncivShowableException(
    "The pending turn could not be stored or recovered safely. It was not uploaded."
)

internal class PendingTurnUploadRecord {
    var originalPlayer = ""
    var originalTurns = 0
    var originalTurnStartTime = 0L
    var originalFingerprint = ""
    var pendingGameData = ""
    var serverUrl = ""
    var isCreationIntent = false
    var unconfirmedReason = TurnUploadUnconfirmedReason.UploadFailed.name
}

private sealed class PendingTurnLoadResult {
    data object Missing : PendingTurnLoadResult()
    data object Corrupt : PendingTurnLoadResult()
    data class Loaded(
        val pendingTurn: PendingTurnUpload,
        val reason: TurnUploadUnconfirmedReason,
    ) : PendingTurnLoadResult()
}

private class PendingTurnUploadStore(
    files: UncivFiles,
    private val gameId: String,
    private val serverUrl: String?,
) {
    private val storageKey = "${serverUrl.normalizedServerUrl()}\n$gameId"
    private val file = files.getLocalFile("MultiplayerPendingTurns/${storageKey.fileSafeHash()}.json")
    private val temporaryFile = file.sibling("${file.name()}.tmp")

    fun load(): PendingTurnLoadResult {
        if (!file.exists() && !temporaryFile.exists()) return PendingTurnLoadResult.Missing

        val primaryFileExists = file.exists()

        for (candidate in sequenceOf(temporaryFile, file).filter { it.exists() }) {
            try {
                val record = json().fromJson(PendingTurnUploadRecord::class.java, candidate)
                    ?: continue
                val pendingTurn = PendingTurnUpload.fromRecord(record, gameId, serverUrl)
                val reason = TurnUploadUnconfirmedReason.entries
                    .firstOrNull { it.name == record.unconfirmedReason }
                    ?: TurnUploadUnconfirmedReason.UploadFailed
                return PendingTurnLoadResult.Loaded(pendingTurn, reason)
            } catch (ex: Exception) {
                Log.error("Could not recover a pending multiplayer turn from local storage.", ex)
            }
        }
        if (!primaryFileExists && temporaryFile.exists()) {
            // The first network write starts only after save() returns. A malformed lone temp file
            // therefore cannot represent a turn that may have reached the server.
            temporaryFile.delete()
            return PendingTurnLoadResult.Missing
        }
        return PendingTurnLoadResult.Corrupt
    }

    fun save(pendingTurn: PendingTurnUpload, reason: TurnUploadUnconfirmedReason?): Boolean {
        return try {
            file.parent().mkdirs()
            temporaryFile.writeString(
                json().toJson(pendingTurn.asRecord(reason)),
                false,
                Charsets.UTF_8.name(),
            )
            if (file.exists() && !file.delete()) throw IllegalStateException("Could not replace pending turn")
            temporaryFile.moveTo(file)
            true
        } catch (ex: Exception) {
            Log.error("Could not persist a pending multiplayer turn.", ex)
            false
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
        if (temporaryFile.exists()) temporaryFile.delete()
    }
}

private data class TurnUploadState(
    val currentPlayer: String,
    val turns: Int,
    val currentTurnStartTime: Long,
)

private fun String.fileSafeHash(): String = MessageDigest
    .getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun String?.normalizedServerUrl() = this?.trimEnd('/').orEmpty()

@Readonly
private fun GameInfo.turnUploadState() =
    TurnUploadState(currentPlayer, turns, currentTurnStartTime)

@Readonly
private fun GameInfoPreview.turnUploadState() =
    TurnUploadState(currentPlayer, turns, currentTurnStartTime)

private fun GameInfoPreview.previewFingerprint(): String = MessageDigest
    .getInstance("SHA-256")
    .digest(json().toJson(this).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
