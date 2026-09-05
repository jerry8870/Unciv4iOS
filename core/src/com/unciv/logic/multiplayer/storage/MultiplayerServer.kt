package com.unciv.logic.multiplayer.storage

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.ServerFeatureSet
import com.unciv.utils.Dispatcher
import com.unciv.utils.PlatformCapabilities
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import java.net.URI

/** Low-level API v1 access for asynchronous multiplayer game files. */
class MultiplayerServer(
    val fileStorageIdentifier: String? = null,
    private val authenticationHeader: Map<String, String>? = null,
    private val transport: MultiplayerV1Transport = SimpleHttp(),
    private val networkDispatcher: CoroutineDispatcher = Dispatcher.DAEMON,
) {
    @Volatile
    private var featureSet = ServerFeatureSet()
    @Volatile
    private var validatedRedirectServerUrl: String? = null

    fun getFeatureSet() = featureSet

    fun setFeatureSet(value: ServerFeatureSet) {
        if (featureSet == value) return
        featureSet = value
        val chatButton = if (UncivGame.isCurrentInitialized()) {
            UncivGame.Current.worldScreen?.chatButton
        } else {
            null
        }
        if (chatButton != null) CoroutineScope(Dispatcher.DAEMON).launchOnGLThread {
            chatButton.refreshVisibility()
        }
    }

    fun getServerUrl() = validatedRedirectServerUrl
        ?: fileStorageIdentifier
        ?: UncivGame.Current.settings.multiplayer.getServer()

    /** Returns this server's request origin after applying the current platform policy. */
    fun getValidatedServerUrl() = validateServerUrl(getServerUrl(), currentCapabilities())

    fun fileStorage(): FileStorage = fileStorage(getValidatedServerUrl())

    private fun fileStorage(serverUrl: String): FileStorage {
        val capabilities = currentCapabilities()
        if (serverUrl == Constants.dropboxMultiplayerServer) return DropBox
        return UncivServerFileStorage(
            serverUrl = serverUrl,
            authHeaderProvider = {
                authenticationHeader ?: mapOf(
                    "Authorization" to UncivGame.Current.settings.multiplayer.getAuthHeader(serverUrl)
                )
            },
            transport = transport,
            networkDispatcher = networkDispatcher,
            requiresPublicHttps = capabilities.multiplayerServerRequiresHttps,
        )
    }

    /** Checks `/isalive` through the same API v1 transport used by auth and game files. */
    suspend fun checkServerStatus(): Boolean {
        val server = validateServerUrl(getServerUrl(), currentCapabilities())
        if (server == Constants.dropboxMultiplayerServer) return false
        val response = uncivServerFileStorage(server).checkServerStatus()
        validateMultiplayerV1Redirect(
            "$server/isalive",
            response.finalUrl,
            emptyMap(),
            requiresPublicHttps = currentCapabilities().multiplayerServerRequiresHttps,
            allowCrossOrigin = false,
        )
        setFeatureSet(parseServerFeatureSet(response.body))

        redirectedServerBase(response.finalUrl)?.let { redirectedServer ->
            val validatedRedirect = validateServerUrl(redirectedServer, currentCapabilities())
            if (fileStorageIdentifier != null) validatedRedirectServerUrl = validatedRedirect
            val multiplayer = UncivGame.Current.settings.multiplayer
            if (server == multiplayer.getServer() && validatedRedirect != server) {
                multiplayer.setServer(validatedRedirect)
            }
        }
        return true
    }

    /** Returns true when auth succeeded or the server does not advertise auth support. */
    suspend fun authenticate(
        password: String?,
        authRequired: Boolean = false,
        savePasswordOnSuccess: Boolean = true,
    ): Boolean {
        if (!authRequired && featureSet.authVersion == 0) return true
        val settings = UncivGame.Current.settings.multiplayer
        val serverUrl = getValidatedServerUrl()
        val success = fileStorage(serverUrl).authenticate(
            userId = settings.getUserId(),
            password = password ?: settings.getPassword(
                serverUrl
            ) ?: "",
        )
        if (password != null && success && savePasswordOnSuccess)
            settings.setPassword(serverUrl, password)
        return success
    }

    suspend fun setPassword(password: String): Boolean {
        val serverUrl = getValidatedServerUrl()
        if (featureSet.authVersion > 0 && fileStorage(serverUrl).setPassword(password)) {
            UncivGame.Current.settings.multiplayer.setPassword(serverUrl, password)
            return true
        }
        return false
    }

    suspend fun uploadGame(gameInfo: GameInfo, withPreview: Boolean) {
        val zippedGameInfo = UncivFiles.gameInfoToString(
            gameInfo,
            forceZip = true,
            updateChecksum = true,
        )
        val storage = fileStorage()
        try {
            storage.saveFileData(gameInfo.gameId, zippedGameInfo)
        } catch (exception: CancellationException) {
            throw MultiplayerGameUploadCancelledException(
                fullUploadConfirmed = false,
                cause = exception,
            )
        } catch (exception: MultiplayerAuthException) {
            throw exception
        } catch (exception: FileStorageRateLimitReached) {
            throw exception
        } catch (exception: MultiplayerFileNotFoundException) {
            throw exception
        } catch (exception: FileStorageConflictException) {
            throw exception
        } catch (exception: MultiplayerNetworkException) {
            if (exception.error == MultiplayerNetworkError.HTTP_ERROR
                && exception.statusCode in 400..499
            ) throw exception
            throw MultiplayerFullGameUploadOutcomeUnknownException(exception)
        } catch (exception: Exception) {
            throw MultiplayerFullGameUploadOutcomeUnknownException(exception)
        }

        // Publishing the preview first would let another client observe a turn whose game is not uploaded yet.
        if (withPreview) {
            try {
                val zippedPreview = UncivFiles.gameInfoToString(gameInfo.asPreview())
                storage.saveFileData("${gameInfo.gameId}_Preview", zippedPreview)
            } catch (exception: CancellationException) {
                throw MultiplayerGameUploadCancelledException(
                    fullUploadConfirmed = true,
                    cause = exception,
                )
            } catch (_: Exception) {
                throw MultiplayerPreviewUploadException()
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun tryUploadGamePreview(gameInfo: GameInfoPreview) {
        val zippedGameInfo = UncivFiles.gameInfoToString(gameInfo)
        fileStorage().saveFileData("${gameInfo.gameId}_Preview", zippedGameInfo)
    }

    suspend fun tryDownloadGame(gameId: String): GameInfo {
        return tryDownloadGame(
            gameId,
            requireValidChecksum = currentCapabilities().multiplayerApiV1Only,
        )
    }

    /** Verifies the payload before stamping local routing metadata used for future requests. */
    suspend fun tryDownloadVerifiedGame(gameId: String): GameInfo {
        return tryDownloadGame(gameId, requireValidChecksum = true)
    }

    private suspend fun tryDownloadGame(gameId: String, requireValidChecksum: Boolean): GameInfo {
        val serverUrl = getValidatedServerUrl()
        val zippedGameInfo = fileStorage(serverUrl).loadFileData(gameId)
        val gameInfo = try {
            UncivFiles.gameInfoFromString(
                zippedGameInfo,
                validateChecksumBeforeMigrations = requireValidChecksum,
            )
        } catch (ex: Exception) {
            if (requireValidChecksum) {
                throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
            }
            throw ex
        }
        if (gameInfo.gameId != gameId) {
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
        }
        gameInfo.gameParameters.multiplayerServerUrl = serverUrl
        return gameInfo
    }

    suspend fun downloadGame(gameId: String): GameInfo {
        val latestGame = tryDownloadGame(gameId)
        latestGame.isUpToDate = true
        return latestGame
    }

    suspend fun tryDownloadGamePreview(gameId: String): GameInfoPreview {
        val serverUrl = getValidatedServerUrl()
        val zippedGameInfo = fileStorage(serverUrl).loadFileData("${gameId}_Preview")
        val gameInfoPreview = UncivFiles.gameInfoPreviewFromString(zippedGameInfo)
        if (gameInfoPreview.gameId != gameId) {
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
        }
        gameInfoPreview.gameParameters.multiplayerServerUrl = serverUrl
        return gameInfoPreview
    }

    private fun uncivServerFileStorage(serverUrl: String): UncivServerFileStorage {
        val capabilities = currentCapabilities()
        return UncivServerFileStorage(
            serverUrl = serverUrl,
            authHeaderProvider = {
                authenticationHeader ?: mapOf(
                    "Authorization" to UncivGame.Current.settings.multiplayer.getAuthHeader(serverUrl)
                )
            },
            transport = transport,
            networkDispatcher = networkDispatcher,
            requiresPublicHttps = capabilities.multiplayerServerRequiresHttps,
        )
    }

    private fun parseServerFeatureSet(body: String): ServerFeatureSet {
        val trimmedBody = body.trim()
        if (trimmedBody == "true") return ServerFeatureSet()
        if (!trimmedBody.startsWith('{')) {
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
        }
        return try {
            json().fromJson(ServerFeatureSet::class.java, trimmedBody)
                ?: throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
        } catch (_: Exception) {
            // Do not retain parser details because they can include the response body.
            throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_RESPONSE)
        }
    }

    private fun currentCapabilities() = if (UncivGame.isCurrentInitialized()) {
        UncivGame.Current.platformCapabilities
    } else {
        PlatformCapabilities()
    }

    private fun redirectedServerBase(finalUrl: String): String? {
        val suffix = "/isalive"
        val uri = try {
            URI(finalUrl)
        } catch (_: Exception) {
            return null
        }
        if (!uri.path.endsWith(suffix)) return null
        val basePath = uri.path.removeSuffix(suffix)
        return URI(uri.scheme, uri.userInfo, uri.host, uri.port, basePath, null, null).toString()
            .trimEnd('/')
    }

    companion object {
        /** Shared by settings UI and every request boundary so saved historical servers cannot bypass policy. */
        fun validateServerUrl(value: String, capabilities: PlatformCapabilities): String {
            val trimmedValue = value.trimEnd('/')
            if (trimmedValue == Constants.dropboxMultiplayerServer) {
                if (capabilities.multiplayerApiV1Only) {
                    throw MultiplayerNetworkException(MultiplayerNetworkError.INSECURE_SERVER_URL)
                }
                return trimmedValue
            }
            val validatedHttpUrl = validateHttpServerUrl(trimmedValue)
            if (!capabilities.multiplayerServerRequiresHttps) return validatedHttpUrl

            return validatePublicHttpsUrl(validatedHttpUrl)
        }
    }
}

internal fun validatePublicHttpsUrl(value: String): String {
    val validatedValue = validateHttpServerUrl(value)
    val uri = URI(validatedValue)
    val host = uri.host!!.lowercase().trimEnd('.')
    if (uri.scheme?.lowercase() != "https" || host.isLocalOrIpAddress()) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.INSECURE_SERVER_URL)
    }
    return validatedValue
}

private fun validateHttpServerUrl(value: String): String {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
    }
    if (uri.host == null
        || uri.scheme?.lowercase() !in setOf("http", "https")
        || uri.userInfo != null
        || uri.rawQuery != null
        || uri.rawFragment != null
        || uri.port !in -1..65535
        || uri.port == 0
    ) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.INVALID_URL)
    }
    return uri.toString().trimEnd('/')
}

private fun String.isLocalOrIpAddress(): Boolean {
    if (this == "localhost"
        || endsWith(".localhost")
        || endsWith(".local")
        || endsWith(".lan")
        || this == "home.arpa"
        || endsWith(".home.arpa")
    ) return true
    if (contains(':') || startsWith('[') || all { it.isDigit() }) return true
    val parts = split('.')
    return parts.all { part ->
        part.isNotEmpty() && (part.all(Char::isDigit)
            || part.startsWith("0x", ignoreCase = true)
            && part.drop(2).isNotEmpty()
            && part.drop(2).all(Char::isHexDigit))
    }
}

private fun Char.isHexDigit() = isDigit() || lowercaseChar() in 'a'..'f'
