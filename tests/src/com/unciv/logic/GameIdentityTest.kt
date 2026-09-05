package com.unciv.logic

import com.unciv.UncivGame
import com.unciv.isSameGameForWorldRestore
import com.unciv.models.metadata.GameSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIdentityTest {
    @Test
    fun onlineWorldRestoreIncludesThePinnedServer() {
        val first = game(online = true, server = "https://one.example/")

        assertTrue(isSameGameForWorldRestore(first, game(true, "https://one.example")))
        assertFalse(isSameGameForWorldRestore(first, game(true, "https://two.example")))
    }

    @Test
    fun singlePlayerIdentityKeepsTheExistingGameIdBehavior() {
        assertTrue(isSameGameForWorldRestore(game(false, null), game(false, null)))
        assertFalse(isSameGameForWorldRestore(game(false, null), game(true, "https://one.example")))
    }

    @Test
    fun currentOnlineGameCheckIncludesThePinnedServer() {
        val current = game(true, "https://one.example")
        UncivGame.Current = UncivGame().apply {
            settings = GameSettings()
            gameInfo = current
        }

        assertTrue(UncivGame.isCurrentGame(current.gameId, "https://one.example/"))
        assertFalse(UncivGame.isCurrentGame(current.gameId, "https://two.example"))
    }

    private fun game(online: Boolean, server: String?) = GameInfo().apply {
        gameId = "same-id"
        gameParameters.isOnlineMultiplayer = online
        gameParameters.multiplayerServerUrl = server
    }
}
