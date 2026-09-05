package com.unciv.ui.audio

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.GdxTestRunner
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class SoundPlayerTransientStateTest {
    @After
    fun tearDown() {
        SoundPlayer.clearCache()
        UncivGame.Current = UncivGame()
    }

    @Test
    fun uninitializedGameRulesetDoesNotCrashSoundCache() {
        UncivGame.Current = UncivGame().apply {
            settings = GameSettings()
            files = UncivFiles(Gdx.files)
            gameInfo = GameInfo()
        }

        SoundPlayer.initializeForMainMenu()
    }
}
