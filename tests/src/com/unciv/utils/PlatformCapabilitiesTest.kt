package com.unciv.utils

import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCapabilitiesTest {
    @Test
    fun existingPlatformsSupportCurrentFeaturesByDefault() {
        val capabilities = PlatformCapabilities()

        assertTrue(capabilities.onlineMultiplayer)
        assertTrue(capabilities.multiplayerChat)
        assertTrue(capabilities.defaultMusicDownload)
        assertTrue(capabilities.onlineModManagement)
        assertFalse(capabilities.multiplayerServerRequiresHttps)
        assertFalse(capabilities.multiplayerApiV1Only)
        assertFalse(capabilities.secureMultiplayerServerPasswords)
        assertTrue(capabilities.multiplayerAdvancedActions)
    }

    @Test
    fun platformsCanDeclareLocalOnlyCapabilities() {
        val capabilities = PlatformCapabilities(
            onlineMultiplayer = false,
            multiplayerChat = false,
            defaultMusicDownload = false,
            onlineModManagement = false,
            multiplayerServerRequiresHttps = true,
            multiplayerApiV1Only = true,
            secureMultiplayerServerPasswords = true,
            multiplayerAdvancedActions = false,
        )

        assertFalse(capabilities.onlineMultiplayer)
        assertFalse(capabilities.multiplayerChat)
        assertFalse(capabilities.defaultMusicDownload)
        assertFalse(capabilities.onlineModManagement)
        assertTrue(capabilities.multiplayerServerRequiresHttps)
        assertTrue(capabilities.multiplayerApiV1Only)
        assertTrue(capabilities.secureMultiplayerServerPasswords)
        assertFalse(capabilities.multiplayerAdvancedActions)
    }

    @Test
    fun onlineMultiplayerCanBeQueriedBeforeItIsInitialized() {
        val game = UncivGame()

        assertNull(game.onlineMultiplayerOrNull)
    }

    @Test
    fun unsupportedOnlineSetupIsConvertedBeforeNewGameUiUsesIt() {
        val game = object : UncivGame() {
            override val platformCapabilities = PlatformCapabilities(onlineMultiplayer = false)
        }
        game.settings = GameSettings().apply {
            lastGameSetup = GameSetupInfo().apply {
                gameParameters.isOnlineMultiplayer = true
                gameParameters.multiplayerServerUrl = "https://example.invalid"
            }
        }
        UncivGame.Current = game

        val setup = GameSetupInfo.fromSettings()

        assertFalse(setup.gameParameters.isOnlineMultiplayer)
        assertNull(setup.gameParameters.multiplayerServerUrl)
    }
}
