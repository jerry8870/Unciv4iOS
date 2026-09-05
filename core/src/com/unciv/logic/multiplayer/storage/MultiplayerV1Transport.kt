package com.unciv.logic.multiplayer.storage

import com.unciv.logic.UncivShowableException
import kotlinx.coroutines.CancellationException
import java.net.URI

enum class MultiplayerV1HttpMethod(val wireName: String) {
    GET("GET"),
    PUT("PUT"),
    DELETE("DELETE"),
}

data class MultiplayerV1Request(
    val method: MultiplayerV1HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
    val requiresPublicHttps: Boolean = false,
) {
    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.hashCode() ?: 0)
        result = 31 * result + connectTimeoutMillis
        result = 31 * result + readTimeoutMillis
        result = 31 * result + (if (requiresPublicHttps) 1231 else 1237)
        return result
    }
}

data class MultiplayerV1Response(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
    val finalUrl: String,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

/** Narrow transport boundary for the foreground asynchronous API v1 multiplayer flow. */
interface MultiplayerV1Transport {
    suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response
}

/** Shared redirect policy for JVM and native transports. It performs parsing only and never opens a connection. */
fun validateMultiplayerV1Redirect(
    from: String,
    to: String,
    headers: Map<String, String>,
    requiresPublicHttps: Boolean = false,
    allowCrossOrigin: Boolean = false,
) {
    val fromUri: URI
    val toUri: URI
    try {
        fromUri = URI(from)
        toUri = URI(to)
    } catch (_: Exception) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
    }
    if (fromUri.host == null || toUri.host == null
        || fromUri.scheme?.lowercase() !in HTTP_SCHEMES
        || toUri.scheme?.lowercase() !in HTTP_SCHEMES
    ) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.UNSAFE_REDIRECT)
    }
    if (fromUri.scheme.equals("https", ignoreCase = true)
        && !toUri.scheme.equals("https", ignoreCase = true)
    ) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.UNSAFE_REDIRECT)
    }
    val hasAuthorization = headers.keys.any { it.equals("Authorization", ignoreCase = true) }
    // Never replay credentials to a redirected path. Server credentials are isolated by the
    // configured base URL, not merely by origin. File redirects are also rejected so a GET cannot
    // silently move to a location that a later PUT is forbidden to use.
    if (hasAuthorization || !fromUri.path.orEmpty().trimEnd('/').endsWith("/isalive")) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.UNSAFE_REDIRECT)
    }
    if ((!allowCrossOrigin || hasAuthorization) && fromUri.origin() != toUri.origin()) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.UNSAFE_REDIRECT)
    }
    if (requiresPublicHttps) validatePublicHttpsUrl(to)
}

enum class MultiplayerNetworkError {
    INVALID_URL,
    INSECURE_SERVER_URL,
    UNSAFE_REDIRECT,
    TOO_MANY_REDIRECTS,
    DNS,
    TLS,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    CANCELLED,
    CONNECTION,
    INVALID_RESPONSE,
    SERVER_ERROR,
    HTTP_ERROR,
}

/** A user-safe network failure. Response bodies and credentials must never be put in [message]. */
class MultiplayerNetworkException(
    val error: MultiplayerNetworkError,
    val statusCode: Int? = null,
) : UncivShowableException(error.userMessage(statusCode))

/** Explicit transport cancellation while preserving structured coroutine cancellation semantics. */
class MultiplayerRequestCancelledException :
    CancellationException(MultiplayerNetworkError.CANCELLED.userMessage(null)) {
    val error = MultiplayerNetworkError.CANCELLED
}

private fun MultiplayerNetworkError.userMessage(statusCode: Int?): String = when (this) {
    MultiplayerNetworkError.INVALID_URL -> "The multiplayer server address is invalid."
    MultiplayerNetworkError.INSECURE_SERVER_URL -> "This build requires a public HTTPS multiplayer server address."
    MultiplayerNetworkError.UNSAFE_REDIRECT -> "The multiplayer server attempted an unsafe redirect."
    MultiplayerNetworkError.TOO_MANY_REDIRECTS -> "The multiplayer server redirected too many times."
    MultiplayerNetworkError.DNS -> "The multiplayer server address could not be resolved."
    MultiplayerNetworkError.TLS -> "A secure connection to the multiplayer server could not be established."
    MultiplayerNetworkError.CONNECT_TIMEOUT -> "Connecting to the multiplayer server timed out."
    MultiplayerNetworkError.READ_TIMEOUT -> "The multiplayer server took too long to respond."
    MultiplayerNetworkError.CANCELLED -> "The multiplayer request was cancelled."
    MultiplayerNetworkError.CONNECTION -> "The multiplayer server could not be reached."
    MultiplayerNetworkError.INVALID_RESPONSE -> "The multiplayer server returned an invalid response."
    MultiplayerNetworkError.SERVER_ERROR -> "The multiplayer server failed with status [${statusCode ?: 500}]."
    MultiplayerNetworkError.HTTP_ERROR -> "The multiplayer server rejected the request with status [${statusCode ?: 0}]."
}

private val HTTP_SCHEMES = setOf("http", "https")

private fun URI.origin(): Triple<String, String, Int> = Triple(
    scheme.lowercase(),
    host.lowercase(),
    if (port == -1) if (scheme.equals("https", ignoreCase = true)) 443 else 80 else port,
)
