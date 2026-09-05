package com.unciv.app

import com.unciv.logic.multiplayer.storage.MultiplayerNetworkError
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkException
import com.unciv.logic.multiplayer.storage.MultiplayerRequestCancelledException
import com.unciv.logic.multiplayer.storage.MultiplayerV1HttpMethod
import com.unciv.logic.multiplayer.storage.MultiplayerV1Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IOSMultiplayerV1TransportTests {
    @Test
    fun `request and response cross the adapter without losing API v1 fields`() = runBlocking {
        val adapter = FakeAdapter(
            IOSURLSessionResult.Success(
                statusCode = 201,
                body = "response-data",
                headers = mapOf("Retry-After" to listOf("12")),
                finalUrl = "https://trusted.example/api/files/game",
            )
        )
        val transport = IOSMultiplayerV1Transport(adapter)

        val response = transport.execute(
            MultiplayerV1Request(
                method = MultiplayerV1HttpMethod.PUT,
                url = "https://trusted.example/api/files/game",
                headers = mapOf("Authorization" to "Basic hidden"),
                body = "upload-data",
                connectTimeoutMillis = 1_234,
                readTimeoutMillis = 5_678,
            )
        )

        assertEquals(201, response.statusCode)
        assertEquals("response-data", response.body)
        assertEquals("12", response.header("retry-after"))
        assertEquals("https://trusted.example/api/files/game", response.finalUrl)
        assertEquals(MultiplayerV1HttpMethod.PUT, adapter.request?.method)
        assertEquals("upload-data", adapter.request?.body)
        assertEquals(1_234, adapter.request?.connectTimeoutMillis)
        assertEquals(5_678, adapter.request?.readTimeoutMillis)
        assertTrue(adapter.request?.requiresPublicHttps == true)
        assertTrue(adapter.task.resumed)
    }

    @Test
    fun `iOS rejects insecure URL before creating a native task`() = runBlocking {
        val adapter = FakeAdapter(success())
        val exception = expectThrows<MultiplayerNetworkException> {
            IOSMultiplayerV1Transport(adapter).execute(request("http://trusted.example/isalive"))
        }

        assertEquals(MultiplayerNetworkError.INSECURE_SERVER_URL, exception.error)
        assertNull(adapter.request)
    }

    @Test
    fun `native failures retain safe distinct error types`() = runBlocking {
        val dns = expectThrows<MultiplayerNetworkException> {
            IOSMultiplayerV1Transport(
                FakeAdapter(IOSURLSessionResult.Failure(MultiplayerNetworkError.DNS))
            ).execute(request())
        }
        assertEquals(MultiplayerNetworkError.DNS, dns.error)
        assertNull(dns.cause)

        val cancellation = expectThrows<MultiplayerRequestCancelledException> {
            IOSMultiplayerV1Transport(
                FakeAdapter(IOSURLSessionResult.Failure(MultiplayerNetworkError.CANCELLED))
            ).execute(request())
        }
        assertEquals(MultiplayerNetworkError.CANCELLED, cancellation.error)
    }

    @Test
    fun `native timeout classification retains connection phase`() {
        assertEquals(MultiplayerNetworkError.CONNECT_TIMEOUT, classifyIOSTimeout(false))
        assertEquals(MultiplayerNetworkError.READ_TIMEOUT, classifyIOSTimeout(true))
    }

    @Test
    fun `native redirects never turn a write into a successful GET`() {
        val exception = expectThrowsBlocking<MultiplayerNetworkException> {
            validateIOSMultiplayerRedirect(
                originalRequest = MultiplayerV1Request(
                    method = MultiplayerV1HttpMethod.PUT,
                    url = "https://trusted.example/files/game",
                    body = "upload-data",
                ),
                from = "https://trusted.example/files/game",
                to = "https://trusted.example/new/files/game",
                proposedMethod = "GET",
            )
        }

        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, exception.error)

        val crossOrigin = expectThrowsBlocking<MultiplayerNetworkException> {
            validateIOSMultiplayerRedirect(
                originalRequest = request(),
                from = "https://trusted.example/isalive",
                to = "https://other-public.example/isalive",
                proposedMethod = "GET",
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, crossOrigin.error)

        val authenticatedSameOrigin = expectThrowsBlocking<MultiplayerNetworkException> {
            validateIOSMultiplayerRedirect(
                originalRequest = request().copy(
                    headers = mapOf("Authorization" to "Basic hidden"),
                ),
                from = "https://trusted.example/tenant-a/isalive",
                to = "https://trusted.example/tenant-b/isalive",
                proposedMethod = "GET",
            )
        }
        assertEquals(MultiplayerNetworkError.UNSAFE_REDIRECT, authenticatedSameOrigin.error)

        validateIOSMultiplayerRedirect(
            originalRequest = request(),
            from = "https://trusted.example/isalive",
            to = "https://trusted.example/api/isalive",
            proposedMethod = "GET",
        )
    }

    @Test
    fun `structured coroutine cancellation cancels native task without remapping`() = runBlocking {
        val adapter = FakeAdapter(null)
        val deferred = async { IOSMultiplayerV1Transport(adapter).execute(request()) }
        while (adapter.request == null) yield()

        deferred.cancel()
        val cancellation = expectThrows<CancellationException> { deferred.await() }

        assertFalse(cancellation is MultiplayerRequestCancelledException)
        assertTrue(adapter.task.cancelled)
    }

    private fun request(url: String = "https://trusted.example/isalive") = MultiplayerV1Request(
        method = MultiplayerV1HttpMethod.GET,
        url = url,
    )

    private fun success() = IOSURLSessionResult.Success(200, "true", emptyMap(), request().url)

    private class FakeAdapter(
        private val result: IOSURLSessionResult?,
    ) : IOSURLSessionAdapter {
        var request: MultiplayerV1Request? = null
        val task = FakeTask()

        override fun createTask(
            request: MultiplayerV1Request,
            completion: (IOSURLSessionResult) -> Unit,
        ): IOSURLSessionTask {
            this.request = request
            task.onResume = { result?.let(completion) }
            return task
        }
    }

    private class FakeTask : IOSURLSessionTask {
        var resumed = false
        var cancelled = false
        var onResume: () -> Unit = {}

        override fun resume() {
            resumed = true
            onResume()
        }

        override fun cancel() {
            cancelled = true
        }
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
