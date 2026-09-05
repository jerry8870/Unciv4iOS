package com.unciv.logic.multiplayer.chat

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.ui.screens.worldscreen.chat.ChatPopup
import java.util.Collections.synchronizedMap
import java.util.LinkedList
import java.util.Queue
import java.util.UUID

data class Chat(
    val gameId: UUID,
    val serverUrl: String? = null,
) {
    var unreadCount = 0

    // <civName, message> pairs
    private val messages: MutableList<Pair<String, String>> = mutableListOf(INITIAL_MESSAGE)

    val length: Int get() = messages.size

    /**
     * Only requests a message to be sent.
     * Does not guarantee delivery.
     * Failures are mosly ignored.
     *
     * The server will relay it back if a delivery was acknowledged and that is when we should display it.
     */
    fun requestMessageSend(civName: String, message: String) {
        if (!UncivGame.Current.platformCapabilities.multiplayerChat) return
        if (serverUrl != null
            && serverUrl.normalizedServerUrl() !=
                UncivGame.Current.settings.multiplayer.getServer().normalizedServerUrl()
        ) return
        Gdx.app.postRunnable {
            ChatWebSocket.requestMessageSend(Message.Chat(civName, message, gameId.toString()))
        }
    }

    /**
     * Although public, this should only be called when a ChatMessageReceivedEvent is received once.
     */
    fun addMessage(civName: String, message: String) {
        messages.add(Pair(civName, message))
    }

    fun forEachMessage(action: (String, String) -> Unit) {
        for ((civName, message) in messages) {
            action(civName, message)
        }
    }

    companion object {
        val INITIAL_MESSAGE = "System" to "Welcome to Chat!"
    }
}

object ChatStore {
    private data class ChatKey(val serverUrl: String, val gameId: UUID)

    /** The reference to the [ChatPopup] instance which trying to take a peek at our messages currently.
     * What audacity this popup has!!
     */
    var chatPopup: ChatPopup? = null

    var hasGlobalMessage = false

    private var gameIdToChat: MutableMap<ChatKey, Chat> = synchronizedMap(mutableMapOf())

    /** When no [ChatPopup] is open to receive these oddities, we keep them here.
     * Certainly better than not knowing why the socket closed.
     */
    private var globalMessages: Queue<Pair<String, String>> = LinkedList()

    fun getChatByGameId(gameId: UUID, serverUrl: String? = null): Chat {
        val key = ChatKey(serverUrl.normalizedServerUrl(), gameId)
        return gameIdToChat.getOrPut(key) { Chat(gameId, serverUrl) }
    }
    fun getChatByGameId(gameId: String, serverUrl: String? = null): Chat =
        getChatByGameId(UUID.fromString(gameId), serverUrl)

    fun getGameIds() = gameIdToChat.keys.map { it.gameId.toString() }.distinct()

    /**
     * Clears chat by triggering a garbage collection.
     */
    fun clear() {
        gameIdToChat = synchronizedMap(mutableMapOf())
        globalMessages = LinkedList()
    }

    fun relayChatMessage(incomingChatMsg: Response.Chat, serverUrl: String) {
        Gdx.app.postRunnable {
            if (incomingChatMsg.gameId == null || incomingChatMsg.gameId.isBlank()) {
                relayGlobalMessage(incomingChatMsg.message, incomingChatMsg.civName)
            } else {
                val gameId = try {
                    UUID.fromString(incomingChatMsg.gameId)
                } catch (_: Throwable) {
                    // Discard messages with invalid UUID
                    return@postRunnable
                }

                val popupChat = chatPopup?.chat?.takeIf {
                    it.gameId == gameId &&
                        it.serverUrl.normalizedServerUrl() == serverUrl.normalizedServerUrl()
                }
                val chat = popupChat ?: getChatByGameId(gameId, serverUrl)
                chat.addMessage(incomingChatMsg.civName, incomingChatMsg.message)
                if (popupChat != null) {
                    chatPopup?.addMessage(incomingChatMsg.civName, incomingChatMsg.message)
                }

                if (popupChat == null && incomingChatMsg.civName != "System") {
                    if (!UncivGame.isCurrentGame(gameId.toString(), serverUrl)) {
                        // user is out of world screen or
                        // some other game not currently on screen has a message
                        chat.unreadCount++

                    }
                    // ensures that you are not getting notified for your own messages
                    else if (UncivGame.Current.worldScreen?.gameInfo?.currentPlayer != incomingChatMsg.civName) {
                        chat.unreadCount++
                        UncivGame.Current.worldScreen?.chatButton?.triggerChatIndication()
                    }
                }
            }
        }
    }

    fun pollGlobalMessages(action: (String, String) -> Unit) {
        Gdx.app.postRunnable {
            while (globalMessages.isNotEmpty()) {
                val item = globalMessages.poll()
                action(item.first, item.second)
            }
        }
    }

    fun relayGlobalMessage(message: String, civName: String = "System") {
        Gdx.app.postRunnable {
            if (civName != "System") {
                hasGlobalMessage = chatPopup == null
                if (hasGlobalMessage) UncivGame.Current.worldScreen?.chatButton?.triggerChatIndication()
            }

            chatPopup?.addMessage(civName, message, suffix = "one time")
                ?: globalMessages.add(Pair(civName, message))
        }
    }
}

private fun String?.normalizedServerUrl() = this?.trimEnd('/').orEmpty()
