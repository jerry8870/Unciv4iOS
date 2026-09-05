package com.unciv.logic.multiplayer.storage

import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class SimpleHttpTest {
    @Before
    fun initializeGameSettings() {
        UncivGame.Current = UncivGame().apply { settings = GameSettings() }
    }

    @Test
    fun `connection sets both timeouts closes streams and disconnects`() = runBlocking {
        val input = CloseTrackingInputStream("response")
        val connection = FakeHttpConnection(URL("https://example.test/files/game"), input = input)
        val transport = SimpleHttp(Dispatchers.Unconfined) { connection }

        val response = transport.execute(
            request(
                MultiplayerV1HttpMethod.GET,
                connectTimeoutMillis = 123,
                readTimeoutMillis = 456,
            )
        )

        assertEquals("response", response.body)
        assertEquals(123, connection.connectTimeout)
        assertEquals(456, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(input.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `PUT closes output and sends exact body`() = runBlocking {
        val connection = FakeHttpConnection(URL("https://example.test/files/game"))
        val transport = SimpleHttp(Dispatchers.Unconfined) { connection }

        transport.execute(request(MultiplayerV1HttpMethod.PUT, body = "game-data"))

        assertEquals("game-data", connection.output.toString(Charsets.UTF_8.name()))
        assertTrue(connection.output.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `connect and read timeouts remain distinguishable`() = runBlocking {
        val connectTimeout = FakeHttpConnection(URL("https://example.test/isalive")).apply {
            connectFailure = SocketTimeoutException("connect timed out")
        }
        val connectError = expectThrows<MultiplayerNetworkException> {
            SimpleHttp(Dispatchers.Unconfined) { connectTimeout }.execute(request())
        }
        assertEquals(MultiplayerNetworkError.CONNECT_TIMEOUT, connectError.error)
        assertTrue(connectTimeout.disconnected)

        val readTimeout = FakeHttpConnection(URL("https://example.test/isalive")).apply {
            responseFailure = SocketTimeoutException("read timed out")
        }
        val readError = expectThrows<MultiplayerNetworkException> {
            SimpleHttp(Dispatchers.Unconfined) { readTimeout }.execute(request())
        }
        assertEquals(MultiplayerNetworkError.READ_TIMEOUT, readError.error)
        assertTrue(readTimeout.disconnected)
    }

    @Test
    fun `DNS TLS and ordinary connection failures remain distinguishable`() = runBlocking {
        val failures = listOf(
            UnknownHostException("host and credentials must not leak") to MultiplayerNetworkError.DNS,
            SSLException("certificate details must not leak") to MultiplayerNetworkError.TLS,
            IOException("response body must not leak") to MultiplayerNetworkError.CONNECTION,
        )

        for ((failure, expectedError) in failures) {
            val connection = FakeHttpConnection(URL("https://example.test/isalive")).apply {
                connectFailure = failure
            }
            val exception = expectThrows<MultiplayerNetworkException> {
                SimpleHttp(Dispatchers.Unconfined) { connection }.execute(request())
            }

            assertEquals(expectedError, exception.error)
            assertFalse(exception.message.orEmpty().contains(failure.message.orEmpty()))
            assertNull(exception.cause)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `manual redirects reject downgrade and every cross origin`() {
        val downgrade = expectThrowsBlocking<MultiplayerNetworkException> {
            SimpleHttp.validateRedirect(
                "https://example.test/isalive",
                "http://example.test/isalive",
                emptyMap(),
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, downgrade.error)

        val crossOrigin = expectThrowsBlocking<MultiplayerNetworkException> {
            SimpleHttp.validateRedirect(
                "https://one.example/files/game",
                "https://two.example/files/game",
                mapOf("authorization" to "Basic hidden"),
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, crossOrigin.error)

        val unauthenticatedCrossOrigin = expectThrowsBlocking<MultiplayerNetworkException> {
            SimpleHttp.validateRedirect(
                "https://one.example/isalive",
                "https://two.example/isalive",
                emptyMap(),
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, unauthenticatedCrossOrigin.error)

        val authenticatedSameOrigin = expectThrowsBlocking<MultiplayerNetworkException> {
            SimpleHttp.validateRedirect(
                "https://example.test/tenant-a/files/game",
                "https://example.test/tenant-b/files/game",
                mapOf("Authorization" to "Basic hidden"),
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, authenticatedSameOrigin.error)

        val unauthenticatedFileRedirect = expectThrowsBlocking<MultiplayerNetworkException> {
            SimpleHttp.validateRedirect(
                "https://example.test/files/game",
                "https://example.test/archive/files/game",
                emptyMap(),
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, unauthenticatedFileRedirect.error)
    }

    @Test
    fun `manual same-origin redirect returns final URL and disconnects each connection`() = runBlocking {
        val first = FakeHttpConnection(
            URL("https://example.test/isalive"),
            statusCode = 307,
            responseHeaders = mapOf("Location" to listOf("/health")),
        )
        val second = FakeHttpConnection(
            URL("https://example.test/health"),
            input = CloseTrackingInputStream("ok"),
        )
        val openedUrls = mutableListOf<String>()
        val transport = SimpleHttp(Dispatchers.Unconfined) { url ->
            openedUrls += url.toString()
            if (openedUrls.size == 1) first else second
        }

        val response = transport.execute(request(url = "https://example.test/isalive"))

        assertEquals(listOf("https://example.test/isalive", "https://example.test/health"), openedUrls)
        assertEquals("https://example.test/health", response.finalUrl)
        assertEquals("ok", response.body)
        assertTrue(first.disconnected)
        assertTrue(second.disconnected)
    }

    @Test
    fun `public HTTPS request rejects local redirect before opening it`() = runBlocking {
        val first = FakeHttpConnection(
            URL("https://example.test/isalive"),
            statusCode = 302,
            responseHeaders = mapOf("Location" to listOf("https://127.0.0.1/isalive")),
        )
        val openedUrls = mutableListOf<String>()
        val transport = SimpleHttp(Dispatchers.Unconfined) { url ->
            openedUrls += url.toString()
            first
        }

        val exception = expectThrows<MultiplayerNetworkException> {
            transport.execute(request(requiresPublicHttps = true))
        }

        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, exception.error)
        assertEquals(listOf("https://example.test/isalive"), openedUrls)
        assertTrue(first.disconnected)
    }

    @Test
    fun `coroutine cancellation disconnects in-flight request and stays cancellation`() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = FakeHttpConnection(URL("https://example.test/files/game")).apply {
            responseCodeProvider = {
                responseStarted.countDown()
                disconnected.await(5, TimeUnit.SECONDS)
                throw IOException("connection closed")
            }
            onDisconnect = { disconnected.countDown() }
        }
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val deferred = async(Dispatchers.Default) {
                SimpleHttp(dispatcher) { connection }.execute(request())
            }
            assertTrue(responseStarted.await(5, TimeUnit.SECONDS))

            deferred.cancel()
            val cancellation = expectThrows<CancellationException> { deferred.await() }

            assertFalse(cancellation is MultiplayerRequestCancelledException)
            assertTrue(connection.disconnected)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `explicit transport cancellation has a safe distinct type`() = runBlocking {
        val cancellation = MultiplayerRequestCancelledException()

        assertEquals(MultiplayerNetworkError.CANCELLED, cancellation.error)
        assertEquals("The multiplayer request was cancelled.", cancellation.message)
        assertNull(cancellation.cause)
    }

    private fun request(
        method: MultiplayerV1HttpMethod = MultiplayerV1HttpMethod.GET,
        url: String = "https://example.test/isalive",
        body: String? = null,
        connectTimeoutMillis: Int = 1_000,
        readTimeoutMillis: Int = 1_000,
        requiresPublicHttps: Boolean = false,
    ) = MultiplayerV1Request(
        method = method,
        url = url,
        body = body,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        requiresPublicHttps = requiresPublicHttps,
    )

    private class CloseTrackingInputStream(text: String) :
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeHttpConnection(
        url: URL,
        private val statusCode: Int = 200,
        private val input: InputStream = CloseTrackingInputStream(""),
        private val responseHeaders: Map<String, List<String>> = emptyMap(),
    ) : HttpURLConnection(url) {
        val output = CloseTrackingOutputStream()
        var disconnected = false
        var connectFailure: IOException? = null
        var responseFailure: IOException? = null
        var responseCodeProvider: (() -> Int)? = null
        var onDisconnect: () -> Unit = {}

        override fun connect() {
            connectFailure?.let { throw it }
            connected = true
        }

        override fun disconnect() {
            disconnected = true
            connected = false
            onDisconnect()
        }

        override fun usingProxy() = false

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return responseCodeProvider?.invoke() ?: statusCode
        }

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream? = if (statusCode >= 400) input else null

        override fun getOutputStream(): OutputStream = output

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> = responseHeaders
            .mapValuesTo(linkedMapOf()) { (_, values) -> values.toMutableList() }
    }

    private suspend inline fun <reified T : Throwable> expectThrows(
        crossinline action: suspend () -> Unit,
    ): T {
        try {
            action()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private inline fun <reified T : Throwable> expectThrowsBlocking(action: () -> Unit): T {
        try {
            action()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.java.name}, got ${throwable::class.java.name}", throwable)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }
}
