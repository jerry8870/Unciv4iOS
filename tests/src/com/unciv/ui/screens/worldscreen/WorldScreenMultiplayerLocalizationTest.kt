package com.unciv.ui.screens.worldscreen

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.UncivShowableException
import com.unciv.models.metadata.GameSettings
import com.unciv.models.translations.TranslationEntry
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class WorldScreenMultiplayerLocalizationTest {
    private fun setTranslation(source: String, translation: String) {
        UncivGame.Current = UncivGame()
        UncivGame.Current.settings = GameSettings()
        UncivGame.Current.translations[source] = TranslationEntry(source).apply {
            this[Constants.english] = translation
        }
    }

    @Test
    fun uploadFailureReasonLocalizesShowableMessage() {
        setTranslation("The multiplayer server address is invalid.", "Localized server error")
        val exception = UncivShowableException("The multiplayer server address is invalid.")

        assertEquals(
            "Localized server error",
            multiplayerUploadFailureReason(exception),
        )
    }

    @Test
    fun uploadFailureReasonHidesRawExceptionMessageAndLocalizesFallback() {
        setTranslation("Unknown", "Localized unknown")
        val exception = IllegalStateException("Connection reset by peer")

        assertEquals("Localized unknown", multiplayerUploadFailureReason(exception))
    }
}
