package com.unciv.logic.files.cloud

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class UncivFilesCloudSaveHookTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `successful local save invokes injected hook`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val sync = RecordingSync()
        files.cloudSaveSync = sync
        val game = GameInfo().apply { gameId = "game-id" }
        var saveError: Exception? = IllegalStateException("callback not invoked")

        files.saveGame(game, "My save") { saveError = it }

        assertNull(saveError)
        assertEquals("My save", sync.saved.single().name())
    }

    @Test
    fun `cloud hook exception cannot fail completed local save`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        files.cloudSaveSync = RecordingSync(throwOnSave = true)
        var saveError: Exception? = IllegalStateException("callback not invoked")

        files.saveGame(GameInfo(), "My save") { saveError = it }

        assertNull(saveError)
        assertEquals(true, files.getSave("My save").exists())
    }

    @Test
    fun `cloud hook exception cannot fail completed local deletion`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val save = files.getSave("My save")
        save.writeString("save", false)
        val sync = RecordingSync(throwOnDelete = true)
        files.cloudSaveSync = sync

        val deleted = files.deleteSave(save)

        assertEquals(true, deleted)
        assertEquals(false, save.exists())
    }

    @Test
    fun `multiplayer preview saves do not enter personal cloud sync`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val sync = RecordingSync()
        files.cloudSaveSync = sync
        var saveError: Exception? = IllegalStateException("callback not invoked")

        files.saveMultiplayerGamePreview(GameInfoPreview(), "multiplayer") { saveError = it }

        assertNull(saveError)
        assertEquals(0, sync.saved.size)
    }

    private class RecordingSync(
        private val throwOnSave: Boolean = false,
        private val throwOnDelete: Boolean = false,
    ) : CloudSaveSync {
        val saved = arrayListOf<FileHandle>()
        override val isSupported = true
        override val status = CloudSaveStatus(CloudSaveState.Available)
        override fun onLocalSave(file: FileHandle, game: GameInfo) {
            if (throwOnSave) throw IllegalStateException("offline")
            saved += file
        }
        override fun onLocalDelete(file: FileHandle) {
            if (throwOnDelete) throw IllegalStateException("offline")
        }
        override fun requestSync(trigger: CloudSyncTrigger) = Unit
        override fun addStatusListener(listener: (CloudSaveStatus) -> Unit) = Unit
        override fun removeStatusListener(listener: (CloudSaveStatus) -> Unit) = Unit
        override fun close() = Unit
    }
}
