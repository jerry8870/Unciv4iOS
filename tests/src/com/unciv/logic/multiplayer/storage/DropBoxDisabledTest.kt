package com.unciv.logic.multiplayer.storage

import com.unciv.logic.UncivShowableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DropBoxDisabledTest {
    @Test
    fun everyLegacyDropboxEntryPointFailsClosed() {
        val operations = listOf<suspend () -> Unit>(
            { DropBox.saveFileData("game", "save contents") },
            { DropBox.loadFileData("game") },
            { DropBox.getFileMetaData("game") },
            { DropBox.deleteFile("game") },
            { DropBox.authenticate("user", "password") },
            { DropBox.setPassword("password") },
            { DropBox.checkAuthStatus("user", "password") },
        )

        for (operation in operations) {
            val exception = assertThrows(UncivShowableException::class.java) { runBlocking { operation() } }
            assertEquals(DROPBOX_MULTIPLAYER_UNAVAILABLE, exception.message)
        }
    }
}
