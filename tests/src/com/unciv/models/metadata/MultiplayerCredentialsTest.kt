package com.unciv.models.metadata

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.UncivShowableException
import com.unciv.utils.PlatformCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MultiplayerCredentialsTest {
    private class SecureCredentialGame(
        private val acceptsWrites: Boolean = true,
        private val rejectedServers: Set<String> = emptySet(),
    ) : UncivGame() {
        override val platformCapabilities = PlatformCapabilities(
            secureMultiplayerServerPasswords = true,
        )
        val passwords = mutableMapOf<String, String>()

        override fun getMultiplayerServerPassword(serverUrl: String) = passwords[serverUrl]

        override fun setMultiplayerServerPassword(serverUrl: String, password: String): Boolean {
            if (!acceptsWrites || serverUrl in rejectedServers) return false
            passwords[serverUrl] = password
            return true
        }
    }

    @Test
    fun securePlatformsRoutePasswordsToPlatformStorage() {
        val game = SecureCredentialGame()
        UncivGame.Current = game
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setServer("https://one.example")

        settings.setCurrentServerPassword("first-secret")
        settings.setServer("https://two.example")
        settings.setCurrentServerPassword("second-secret")

        assertEquals("first-secret", settings.getPassword("https://one.example"))
        assertEquals("second-secret", settings.getCurrentServerPassword())
        assertEquals(2, game.passwords.size)
    }

    @Test
    fun securePlatformsStorePasswordForTheExplicitGameServer() {
        val game = SecureCredentialGame()
        UncivGame.Current = game
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setServer("https://current.example")

        settings.setPassword("https://pinned.example", "pinned-secret")

        assertEquals("pinned-secret", settings.getPassword("https://pinned.example"))
        assertEquals(null, settings.getCurrentServerPassword())
    }

    @Test
    fun securePlatformsNeverFallBackToPlaintextWhenStorageFails() {
        UncivGame.Current = SecureCredentialGame(acceptsWrites = false)
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setServer("https://one.example")

        val error = assertThrows(UncivShowableException::class.java) {
            settings.setCurrentServerPassword("must-not-fall-back")
        }

        assertEquals("Could not store the multiplayer password securely.", error.message)
        assertEquals(null, settings.getCurrentServerPassword())
    }

    @Test
    fun legacyPlaintextIsRemovedAfterSecureMigration() {
        UncivGame.Current = UncivGame()
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setServer("https://one.example")
        settings.setCurrentServerPassword("legacy-secret")

        val secureGame = SecureCredentialGame()
        UncivGame.Current = secureGame

        assertEquals(true, settings.migratePasswordsToSecureStorage())
        assertEquals("legacy-secret", secureGame.passwords["https://one.example"])
        assertEquals(false, json().toJson(settings).contains("legacy-secret"))
    }

    @Test
    fun failedSecureMigrationRemovesPlaintextAndRaisesWarning() {
        UncivGame.Current = UncivGame()
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setPassword("https://one.example", "legacy-secret")

        UncivGame.Current = SecureCredentialGame(acceptsWrites = false)

        assertEquals(true, settings.migratePasswordsToSecureStorage())
        assertEquals(false, json().toJson(settings).contains("legacy-secret"))
        assertEquals(true, settings.securePasswordMigrationFailed)
    }

    @Test
    fun partialSecureMigrationRemovesAllPlaintextAndRaisesWarning() {
        UncivGame.Current = UncivGame()
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setPassword("https://one.example", "first-legacy-secret")
        settings.setPassword("https://two.example", "second-legacy-secret")

        val secureGame = SecureCredentialGame(rejectedServers = setOf("https://two.example"))
        UncivGame.Current = secureGame

        assertEquals(true, settings.migratePasswordsToSecureStorage())
        assertEquals("first-legacy-secret", secureGame.passwords["https://one.example"])
        val serializedSettings = json().toJson(settings)
        assertEquals(false, serializedSettings.contains("first-legacy-secret"))
        assertEquals(false, serializedSettings.contains("second-legacy-secret"))
        assertEquals(true, settings.securePasswordMigrationFailed)
    }

    @Test
    fun existingSecurePasswordWinsOverStaleLegacyPlaintext() {
        UncivGame.Current = UncivGame()
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setPassword("https://one.example", "stale-legacy-secret")

        val secureGame = SecureCredentialGame().apply {
            passwords["https://one.example"] = "new-secure-secret"
        }
        UncivGame.Current = secureGame

        assertEquals(true, settings.migratePasswordsToSecureStorage())
        assertEquals("new-secure-secret", secureGame.passwords["https://one.example"])
        assertEquals(false, json().toJson(settings).contains("stale-legacy-secret"))
        assertEquals(false, settings.securePasswordMigrationFailed)
    }

    @Test
    fun trailingSlashCredentialsMigrateAndReadThroughCanonicalServerKey() {
        UncivGame.Current = UncivGame()
        val settings = GameSettings.GameSettingsMultiplayer()
        settings.setPassword("https://one.example/", "legacy-secret")

        val secureGame = SecureCredentialGame()
        UncivGame.Current = secureGame

        assertEquals(true, settings.migratePasswordsToSecureStorage())
        assertEquals("legacy-secret", secureGame.passwords["https://one.example"])
        assertEquals("legacy-secret", settings.getPassword("https://one.example"))

        secureGame.passwords.clear()
        secureGame.passwords["https://one.example/"] = "old-keychain-secret"
        assertEquals("old-keychain-secret", settings.getPassword("https://one.example"))
    }
}
