package com.unciv.app

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.headersOf
import io.ktor.utils.io.readByte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class IOSModHttpClientEngineTests {
    @Test
    fun streamsBinaryResponseBeforeNativeCompletion() = runBlocking {
        val transport = FakeTransport()
        val client = HttpClient(IOSModHttpClientEngine(transport))
        try {
            val firstByte = kotlinx.coroutines.CompletableDeferred<Byte>()
            val remaining = async {
                client.prepareGet("https://example.com/mod.zip").execute {
                    val body = it.bodyAsChannel()
                    firstByte.complete(body.readByte())
                    val bytes = ArrayList<Byte>()
                    while (!body.isClosedForRead) {
                        if (!body.awaitContent()) break
                        bytes.add(body.readByte())
                    }
                    bytes.toByteArray()
                }
            }
            val pending = transport.requests.receive()
            pending.listener.headers(200, Headers.Empty)
            pending.listener.bytes(byteArrayOf(0x50))
            assertEquals(0x50.toByte(), withTimeout(5_000) { firstByte.await() })
            val payload = ByteArray(128 * 1024) { (it % 256).toByte() }
            val producer = async(Dispatchers.IO) {
                payload.asList().chunked(8192).forEach { pending.listener.bytes(it.toByteArray()) }
                pending.listener.complete(null)
            }
            assertArrayEquals(payload, withTimeout(5_000) { remaining.await() })
            producer.await()
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellingBeforeHeadersCancelsNativeTask() = runBlocking {
        val transport = FakeTransport()
        val client = HttpClient(IOSModHttpClientEngine(transport))
        try {
            val request = async { client.get("https://example.com/mod.zip") }
            val pending = transport.requests.receive()
            request.cancelAndJoin()
            assertTrue(pending.cancelled.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun propagatesNativeConnectionFailure() = runBlocking {
        val transport = FakeTransport()
        val client = HttpClient(IOSModHttpClientEngine(transport))
        try {
            val result = async { runCatching { client.get("https://example.com/mod.zip").bodyAsBytes() } }
            transport.requests.receive().listener.complete(IOException("connection failed"))
            assertEquals("connection failed", result.await().exceptionOrNull()?.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun leavesRedirectsToTheExistingKtorPipeline() = runBlocking {
        val transport = FakeTransport()
        val client = HttpClient(IOSModHttpClientEngine(transport))
        try {
            val result = async { client.get("https://github.com/example/mod/archive/main.zip").bodyAsText() }
            val first = transport.requests.receive()
            first.listener.headers(302, headersOf("Location", "https://codeload.github.com/example/mod/zip/main"))
            first.listener.complete(null)
            val redirected = withTimeout(5_000) { transport.requests.receive() }
            assertEquals("codeload.github.com", redirected.request.url.host)
            redirected.listener.headers(200, Headers.Empty)
            redirected.listener.bytes("archive".toByteArray())
            redirected.listener.complete(null)
            assertEquals("archive", result.await())
        } finally {
            client.close()
        }
    }

    private class FakeTransport : IOSModHttpTransport {
        val requests = Channel<PendingRequest>(Channel.UNLIMITED)
        override fun open(request: HttpRequestData, listener: IOSModHttpListener): IOSModHttpTask =
            PendingRequest(request, listener) { requests.trySend(it).getOrThrow() }
    }

    private class PendingRequest(
        val request: HttpRequestData,
        val listener: IOSModHttpListener,
        private val onResume: (PendingRequest) -> Unit,
    ) : IOSModHttpTask {
        val cancelled = AtomicBoolean()
        override fun resume() = onResume(this)
        override fun cancel() { cancelled.set(true) }
    }
}
