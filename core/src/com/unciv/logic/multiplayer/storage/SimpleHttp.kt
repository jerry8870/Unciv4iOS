package com.unciv.logic.multiplayer.storage

import com.unciv.UncivGame
import com.unciv.utils.Dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

private enum class RequestPhase { CONNECT, WRITE, READ }

/** HttpURLConnection implementation used by Android and Desktop after API v1 qualification. */
class SimpleHttp(
    private val dispatcher: CoroutineDispatcher = Dispatcher.DAEMON,
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : MultiplayerV1Transport {
    override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response =
        withContext(dispatcher) { executeCancellable(request) }

    private suspend fun executeCancellable(request: MultiplayerV1Request): MultiplayerV1Response =
        suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation { activeConnection.getAndSet(null)?.disconnect() }

            try {
                val response = executeFollowingRedirects(request, activeConnection) {
                    if (!continuation.isActive) throw CancellationException("Multiplayer request cancelled")
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(response))
            } catch (throwable: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(throwable.toNetworkException()))
                }
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

    private fun executeFollowingRedirects(
        request: MultiplayerV1Request,
        activeConnection: AtomicReference<HttpURLConnection?>,
        ensureActive: () -> Unit,
    ): MultiplayerV1Response {
        var currentUrl = request.url
        if (request.requiresPublicHttps) validatePublicHttpsUrl(currentUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            ensureActive()
            val response = executeOnce(request.copy(url = currentUrl), activeConnection, ensureActive)
            if (response.statusCode !in REDIRECT_STATUS_CODES) return response

            if (redirectCount == MAX_REDIRECTS) {
                throw MultiplayerNetworkException(MultiplayerNetworkError.TOO_MANY_REDIRECTS)
            }
            val location = response.header("Location")
                ?: throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
            val redirectUrl = try {
                URL(URL(currentUrl), location).toString()
            } catch (_: MalformedURLException) {
                throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
            }
            validateRedirect(
                currentUrl,
                redirectUrl,
                request.headers,
                request.requiresPublicHttps,
            )
            currentUrl = redirectUrl
        }
        throw MultiplayerNetworkException(MultiplayerNetworkError.TOO_MANY_REDIRECTS)
    }

    private fun executeOnce(
        request: MultiplayerV1Request,
        activeConnection: AtomicReference<HttpURLConnection?>,
        ensureActive: () -> Unit,
    ): MultiplayerV1Response {
        var phase = RequestPhase.CONNECT
        val url = try {
            URL(request.url)
        } catch (_: MalformedURLException) {
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
        }
        if (url.protocol != "http" && url.protocol != "https") {
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
        }

        val connection = try {
            openConnection(url)
        } catch (throwable: Throwable) {
            throw throwable.toNetworkException(phase)
        }
        activeConnection.set(connection)
        try {
            connection.requestMethod = request.method.wireName
            connection.connectTimeout = request.connectTimeoutMillis
            connection.readTimeout = request.readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", UncivGame.getUserAgent("Multiplayer-v1"))
            for ((key, value) in request.headers) connection.setRequestProperty(key, value)

            val bodyBytes = request.body?.toByteArray(Charsets.UTF_8)
            if (bodyBytes != null) {
                connection.doOutput = true
                if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    connection.setRequestProperty("Content-Type", "text/plain")
                }
                connection.setFixedLengthStreamingMode(bodyBytes.size)
            }

            ensureActive()
            connection.connect()
            if (bodyBytes != null) {
                phase = RequestPhase.WRITE
                connection.outputStream.use { output -> output.write(bodyBytes) }
            }

            ensureActive()
            phase = RequestPhase.READ
            val statusCode = connection.responseCode
            val responseBody = (if (statusCode >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val responseHeaders = buildMap {
                for ((name, values) in connection.headerFields.orEmpty()) {
                    if (name != null && values != null) put(name, values.toList())
                }
            }
            return MultiplayerV1Response(statusCode, responseBody, responseHeaders, request.url)
        } catch (throwable: Throwable) {
            throw throwable.toNetworkException(phase)
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        /** Shared redirect policy: never downgrade TLS or forward credentials to another origin. */
        fun validateRedirect(
            from: String,
            to: String,
            headers: Map<String, String>,
            requiresPublicHttps: Boolean = false,
        ) = validateMultiplayerV1Redirect(from, to, headers, requiresPublicHttps)
    }
}

private fun Throwable.toNetworkException(
    phase: RequestPhase? = null,
): Throwable {
    if (this is MultiplayerNetworkException || this is CancellationException) return this
    val error = when (this) {
        is UnknownHostException -> MultiplayerNetworkError.DNS
        is SSLException -> MultiplayerNetworkError.TLS
        is SocketTimeoutException -> if (phase == RequestPhase.CONNECT) {
            MultiplayerNetworkError.CONNECT_TIMEOUT
        } else {
            MultiplayerNetworkError.READ_TIMEOUT
        }
        is MalformedURLException -> MultiplayerNetworkError.INVALID_URL
        is ProtocolException -> MultiplayerNetworkError.INVALID_RESPONSE
        is ConnectException, is NoRouteToHostException, is IOException -> MultiplayerNetworkError.CONNECTION
        else -> return this
    }
    return MultiplayerNetworkException(error)
}
