package com.unciv.logic.multiplayer.storage

import com.unciv.logic.UncivShowableException
import kotlinx.coroutines.CancellationException
import java.util.Date
import java.io.FileNotFoundException  // Kdoc only

class FileStorageConflictException : Exception()
class FileStorageRateLimitReached(val limitRemainingSeconds: Int) : UncivShowableException("Server limit reached! Please wait for [${limitRemainingSeconds}] seconds")
class MultiplayerFileNotFoundException(cause: Throwable?) : UncivShowableException("File could not be found on the multiplayer server", cause)
class MultiplayerAuthException(cause: Throwable?) : UncivShowableException("Authentication failed", cause)
class MultiplayerPreviewUploadException : UncivShowableException(
    "The game was uploaded, but its multiplayer preview is not confirmed.",
)
class MultiplayerFullGameUploadOutcomeUnknownException(cause: Throwable) : UncivShowableException(
    "The multiplayer server may have stored the game, but did not confirm the upload.",
    cause,
)
class MultiplayerGameUploadCancelledException(
    val fullUploadConfirmed: Boolean,
    cause: CancellationException,
) : CancellationException("Multiplayer game upload cancelled") {
    init { initCause(cause) }
}
class MultiplayerGameCreationCancelledException(
    val gameId: String,
    val fullUploadConfirmed: Boolean,
    val localRecoveryAvailable: Boolean,
    cause: CancellationException,
) : CancellationException("Multiplayer game creation cancelled") {
    init { initCause(cause) }
}
class MultiplayerGameCreationPartialException(
    val gameId: String,
    val fullUploadConfirmed: Boolean,
    val previewConfirmed: Boolean,
    val localRecoveryAvailable: Boolean,
) : UncivShowableException(
    "The remote game may have been created, but creation did not finish locally.",
)

interface FileMetaData {
    fun getLastModified(): Date?
}

enum class AuthStatus {
    UNAUTHORIZED,
    UNREGISTERED,
    VERIFIED,
    UNKNOWN
}

interface FileStorage {
    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     */
    suspend fun saveFileData(fileName: String, data: String)
    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws FileNotFoundException if the file can't be found
     */
    suspend fun loadFileData(fileName: String): String
    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws FileNotFoundException if the file can't be found
     */
    suspend fun getFileMetaData(fileName: String): FileMetaData
    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws FileNotFoundException if the file can't be found
     * @throws MultiplayerAuthException if the authentication failed
     */
    suspend fun deleteFile(fileName: String)
    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     */
    suspend fun authenticate(userId: String, password: String): Boolean
    /**
     * @throws FileStorageRateLimitReached if the file storage backend can't handle any additional actions for a time
     * @throws MultiplayerAuthException if the authentication failed
     */
    suspend fun setPassword(newPassword: String): Boolean
    
    suspend fun checkAuthStatus(userId: String, password: String): AuthStatus
}
