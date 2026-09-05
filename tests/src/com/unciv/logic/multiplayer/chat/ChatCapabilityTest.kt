package com.unciv.logic.multiplayer.chat

import com.unciv.UncivGame
import com.unciv.utils.PlatformCapabilities
import org.junit.Test
import org.junit.Assert.assertNotSame
import java.util.UUID

class ChatCapabilityTest {
    @Test
    fun disabledChatReturnsBeforeAccessingGdxOrWebSocket() {
        UncivGame.Current = object : UncivGame() {
            override val platformCapabilities = PlatformCapabilities(multiplayerChat = false)
        }

        Chat(UUID.randomUUID()).requestMessageSend("player", "message")
    }

    @Test
    fun sameGameIdOnDifferentServersHasIndependentChatState() {
        ChatStore.clear()
        val gameId = UUID.randomUUID()

        val first = ChatStore.getChatByGameId(gameId, "https://one.example/")
        val second = ChatStore.getChatByGameId(gameId, "https://two.example")

        assertNotSame(first, second)
    }
}
