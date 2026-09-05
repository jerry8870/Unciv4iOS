package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.files.FileHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadOrSaveScreenSelectionTest {
    @Test
    fun appliesAsyncSaveDetailsOnlyToTheCurrentSelection() {
        val first = FileHandle("saves/first")
        val second = FileHandle("saves/second")

        assertTrue(isCurrentSaveInfoSelection(first, first))
        assertFalse(isCurrentSaveInfoSelection(second, first))
        assertFalse(isCurrentSaveInfoSelection(null, first))
    }
}
