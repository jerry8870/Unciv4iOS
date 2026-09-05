package com.unciv.logic.multiplayer.storage

import com.badlogic.gdx.utils.Base64Coder
import com.unciv.utils.Dispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class UncivServerFileStorage(
    serverUrl: String,
    private val authHeaderProvider: () -> Map<String, String>?,
    private val transport: MultiplayerV1Transport,
    private val timeoutMillis: Int = 30_000,
    private val networkDispatcher: CoroutineDispatcher = Dispatcher.DAEMON,
    private val requiresPublicHttps: Boolean = false,
) : FileStorage {
    val serverUrl: String = serverUrl.trimEnd('/')

    override suspend fun saveFileData(fileName: String, data: String) {
        execute(
            MultiplayerV1HttpMethod.PUT,
            fileUrl(fileName),
            body = data,
            headers = authHeaders(),
        ).requireSuccess()
    }

    override suspend fun loadFileData(fileName: String): String = execute(
        MultiplayerV1HttpMethod.GET,
        fileUrl(fileName),
        headers = authHeaders(),
    ).requireSuccess().body

    override suspend fun getFileMetaData(fileName: String): FileMetaData {
        TODO("Not yet implemented")
    }

    override suspend fun deleteFile(fileName: String) {
        execute(
            MultiplayerV1HttpMethod.DELETE,
            fileUrl(fileName),
            headers = authHeaders(),
        ).requireSuccess()
    }

    override suspend fun authenticate(userId: String, password: String): Boolean {
        execute(
            MultiplayerV1HttpMethod.GET,
            "$serverUrl/auth",
            headers = basicAuthHeader(userId, password),
        ).requireSuccess()
        return true
    }

    override suspend fun checkAuthStatus(userId: String, password: String): AuthStatus {
        val response = execute(
            MultiplayerV1HttpMethod.GET,
            "$serverUrl/auth",
            headers = basicAuthHeader(userId, password),
        )
        return when (response.statusCode) {
            200 -> AuthStatus.VERIFIED
            204 -> AuthStatus.UNREGISTERED
            401 -> AuthStatus.UNAUTHORIZED
            else -> {
                response.requireSuccess()
                AuthStatus.UNKNOWN
            }
        }
    }

    override suspend fun setPassword(newPassword: String): Boolean {
        val headers = authHeaderProvider() ?: return false
        execute(
            MultiplayerV1HttpMethod.PUT,
            "$serverUrl/auth",
            body = newPassword,
            headers = headers,
        ).requireSuccess()
        return true
    }

    suspend fun checkServerStatus(): MultiplayerV1Response = execute(
        MultiplayerV1HttpMethod.GET,
        "$serverUrl/isalive",
    ).requireSuccess(fileNotFound = false)

    private suspend fun execute(
        method: MultiplayerV1HttpMethod,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): MultiplayerV1Response = withContext(networkDispatcher) {
        transport.execute(
            MultiplayerV1Request(
                method = method,
                url = url,
                headers = headers,
                body = body,
                connectTimeoutMillis = timeoutMillis,
                readTimeoutMillis = timeoutMillis,
                requiresPublicHttps = requiresPublicHttps,
            )
        )
    }

    private fun MultiplayerV1Response.requireSuccess(
        fileNotFound: Boolean = true,
    ): MultiplayerV1Response {
        if (statusCode in 200..299) return this
        when (statusCode) {
            401 -> throw MultiplayerAuthException(null)
            404 -> if (fileNotFound) throw MultiplayerFileNotFoundException(null)
            429 -> throw FileStorageRateLimitReached(retryAfterSeconds())
        }
        if (statusCode >= 500) {
            throw MultiplayerNetworkException(MultiplayerNetworkError.SERVER_ERROR, statusCode)
        }
        throw MultiplayerNetworkException(MultiplayerNetworkError.HTTP_ERROR, statusCode)
    }

    private fun MultiplayerV1Response.retryAfterSeconds(): Int = header("Retry-After")
        ?.toLongOrNull()
        ?.coerceIn(0L, Int.MAX_VALUE.toLong())
        ?.toInt()
        ?: DEFAULT_RETRY_AFTER_SECONDS

    private fun authHeaders(): Map<String, String> = authHeaderProvider().orEmpty()

    private fun basicAuthHeader(userId: String, password: String): Map<String, String> {
        val encoded = Base64Coder.encodeString("$userId:$password")
        return mapOf("Authorization" to "Basic $encoded")
    }

    private fun fileUrl(fileName: String): String {
        if (!SAFE_FILE_NAME.matches(fileName)) {
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
        }
        return "$serverUrl/files/$fileName"
    }

    private companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 60
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9_-]+")
    }
}
