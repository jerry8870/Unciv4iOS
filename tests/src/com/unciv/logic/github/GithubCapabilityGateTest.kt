package com.unciv.logic.github

import com.sun.net.httpserver.HttpServer
import com.unciv.UncivGame
import com.unciv.logic.UncivShowableException
import com.unciv.logic.github.GithubAPI.downloadAndExtract
import com.unciv.utils.ONLINE_MOD_MANAGEMENT_UNAVAILABLE
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class GithubCapabilityGateTest {
    private var previousGame: UncivGame? = null

    @Before
    fun disableOnlineModManagement() {
        previousGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else null
        UncivGame.Current = object : UncivGame() {
            override val platformCapabilities = PlatformCapabilities(onlineModManagement = false)
        }
    }

    @After
    fun restoreDefaultCapabilities() {
        UncivGame.Current = previousGame ?: UncivGame()
    }

    @Test
    fun apiRequestFailsWhenOnlineModManagementIsUnavailable() {
        val exception = assertThrows(UncivShowableException::class.java) {
            runBlocking { GithubAPI.request {} }
        }

        assertEquals(ONLINE_MOD_MANAGEMENT_UNAVAILABLE, exception.message)
    }

    @Test
    fun modDownloadFailsBeforeAccessingPlatformFiles() {
        val exception = assertThrows(UncivShowableException::class.java) {
            runBlocking { GithubAPI.Repo().downloadAndExtract() }
        }

        assertEquals(ONLINE_MOD_MANAGEMENT_UNAVAILABLE, exception.message)
    }

    @Test
    fun rawPreviewReturnsNullWhenOnlineModManagementIsUnavailable() {
        val result = runBlocking {
            GithubAPI.fetchPreviewImageOrNull(
                "https://github.com/example/example",
                "main",
                "png",
            )
        }

        assertNull(result)
    }

    @Test
    fun previewFallbackDoesNotReachTheAvatarServer() {
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requestCount.incrementAndGet()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            }
            start()
        }

        try {
            val result = runBlocking {
                Github.getPreviewImageOrNull(
                    "https://github.com/example/example",
                    "main",
                    "http://127.0.0.1:${server.address.port}/avatar.png",
                )
            }

            assertNull(result)
            assertEquals(0, requestCount.get())
        } finally {
            server.stop(0)
        }
    }
}
