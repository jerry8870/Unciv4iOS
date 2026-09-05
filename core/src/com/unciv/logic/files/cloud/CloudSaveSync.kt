package com.unciv.logic.files.cloud

import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo

enum class CloudSyncTrigger {
    Startup,
    Foreground,
    LoadScreen,
    LocalSave,
    Manual,
    Restore,
    Retry,
}

enum class CloudSaveState {
    Unsupported,
    Checking,
    Available,
    NoAccount,
    Restricted,
    AccountChanged,
    WaitingForNetwork,
    Syncing,
    Failed,
    Synced,
}

data class CloudSaveStatus(
    val state: CloudSaveState = CloudSaveState.Unsupported,
    val lastSuccessfulSyncAt: Long = 0L,
    val localChangeRevision: Long = 0L,
)

interface CloudSaveSync {
    val isSupported: Boolean
    val status: CloudSaveStatus

    /** Must return without waiting for network I/O. */
    fun onLocalSave(file: FileHandle, game: GameInfo)

    /** Local deletion is deliberately conservative and never implies a remote deletion. */
    fun onLocalDelete(file: FileHandle)

    fun requestSync(trigger: CloudSyncTrigger)
    fun addStatusListener(listener: (CloudSaveStatus) -> Unit)
    fun removeStatusListener(listener: (CloudSaveStatus) -> Unit)
    fun close()

    object None : CloudSaveSync {
        override val isSupported = false
        override val status = CloudSaveStatus()
        override fun onLocalSave(file: FileHandle, game: GameInfo) = Unit
        override fun onLocalDelete(file: FileHandle) = Unit
        override fun requestSync(trigger: CloudSyncTrigger) = Unit
        override fun addStatusListener(listener: (CloudSaveStatus) -> Unit) = Unit
        override fun removeStatusListener(listener: (CloudSaveStatus) -> Unit) = Unit
        override fun close() = Unit
    }
}
