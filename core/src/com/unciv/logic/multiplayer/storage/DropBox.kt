package com.unciv.logic.multiplayer.storage

import com.unciv.logic.UncivShowableException

const val DROPBOX_MULTIPLAYER_UNAVAILABLE = "Dropbox storage is no longer available. Use an Unciv API v1 server instead."

/**
 * Compatibility stub for old settings and saves that still reference Dropbox.
 *
 * The shared bearer credential and all network access were deliberately removed. Keeping a fail-closed
 * implementation gives legacy callers a clear error without silently moving their games to another server.
 */
object DropBox : FileStorage {
    private fun unavailable(): Nothing = throw UncivShowableException(DROPBOX_MULTIPLAYER_UNAVAILABLE)

    override suspend fun saveFileData(fileName: String, data: String) = unavailable()
    override suspend fun loadFileData(fileName: String): String = unavailable()
    override suspend fun getFileMetaData(fileName: String): FileMetaData = unavailable()
    override suspend fun deleteFile(fileName: String) = unavailable()
    override suspend fun authenticate(userId: String, password: String): Boolean = unavailable()
    override suspend fun setPassword(newPassword: String): Boolean = unavailable()
    override suspend fun checkAuthStatus(userId: String, password: String): AuthStatus = unavailable()
}
