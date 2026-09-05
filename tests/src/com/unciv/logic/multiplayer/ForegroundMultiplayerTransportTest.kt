package com.unciv.logic.multiplayer

import com.unciv.logic.multiplayer.storage.MultiplayerV1HttpMethod
import com.unciv.logic.multiplayer.storage.MultiplayerV1Request
import com.unciv.logic.multiplayer.storage.MultiplayerV1Response
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundMultiplayerTransportTest {
    private val response = MultiplayerV1Response(200, "", emptyMap(), "https://example.com/files/game")

    @Test
    fun pauseCancelsAnInFlightDownload() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        val download = async {
            transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.GET, response.finalUrl))
        }
        started.await()

        transport.pause()

        try {
            download.await()
        } catch (_: CancellationException) {
            // Expected lifecycle cancellation.
        }
        assertTrue(download.isCancelled)
    }

    @Test
    fun pauseLeavesTheCallerActiveForCancellationCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        var callerWasActiveDuringCleanup = false
        val download = async {
            try {
                transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.GET, response.finalUrl))
            } catch (ex: CancellationException) {
                callerWasActiveDuringCleanup = currentCoroutineContext().isActive
                throw ex
            }
        }
        started.await()

        transport.pause()

        try {
            download.await()
        } catch (_: CancellationException) {
            // Expected lifecycle cancellation.
        }
        assertTrue(callerWasActiveDuringCleanup)
    }

    @Test
    fun pauseCancelsAnOrdinaryInFlightUpload() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        val upload = async {
            transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.PUT, response.finalUrl))
        }
        started.await()

        transport.pause()

        try {
            upload.await()
        } catch (_: CancellationException) {
            // Only explicitly scoped turn uploads may continue in the background.
        }
        assertTrue(upload.isCancelled)
    }

    @Test
    fun pauseDoesNotCancelAnAuthorizedTurnUpload() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                started.complete(Unit)
                finish.await()
                return response
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        val upload = async {
            transport.withTurnUploadContinuation {
                transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.PUT, response.finalUrl))
            }
        }
        started.await()

        transport.pause()

        assertTrue(upload.isActive)
        assertFalse(upload.isCancelled)
        finish.complete(Unit)
        assertEquals(response, upload.await())
    }

    @Test
    fun backgroundRejectsNewWritesWithoutCallingTheDelegate() = runBlocking {
        var requestCount = 0
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requestCount++
                return response
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        transport.pause()

        try {
            transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.PUT, response.finalUrl))
        } catch (_: CancellationException) {
            // Expected while backgrounded.
        }

        assertEquals(0, requestCount)
    }

    @Test
    fun turnUploadPermissionCannotBeAcquiredInTheBackground() = runBlocking {
        var requestCount = 0
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requestCount++
                return response
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        transport.pause()

        try {
            transport.withTurnUploadContinuation {
                transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.PUT, response.finalUrl))
            }
        } catch (_: CancellationException) {
            // A new upload may not be started after pause.
        }

        assertEquals(0, requestCount)
    }

    @Test
    fun resumeAllowsDownloadsAgain() = runBlocking {
        var requestCount = 0
        val delegate = object : MultiplayerV1Transport {
            override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
                requestCount++
                return response
            }
        }
        val transport = ForegroundMultiplayerTransport(delegate)
        transport.pause()

        try {
            transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.GET, response.finalUrl))
        } catch (_: CancellationException) {
            // Expected while backgrounded.
        }
        assertEquals(0, requestCount)

        transport.resume()

        assertEquals(
            response,
            transport.execute(MultiplayerV1Request(MultiplayerV1HttpMethod.GET, response.finalUrl)),
        )
        assertEquals(1, requestCount)
    }
}
