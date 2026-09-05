package com.unciv.app

import com.unciv.logic.multiplayer.storage.MultiplayerNetworkError
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkException
import com.unciv.logic.multiplayer.storage.MultiplayerRequestCancelledException
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.logic.multiplayer.storage.MultiplayerV1HttpMethod
import com.unciv.logic.multiplayer.storage.MultiplayerV1Request
import com.unciv.logic.multiplayer.storage.MultiplayerV1Response
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import com.unciv.logic.multiplayer.storage.validateMultiplayerV1Redirect
import com.unciv.utils.PlatformCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** NSURLSession-backed API v1 transport. It never touches the JVM URL handler. */
class IOSMultiplayerV1Transport internal constructor(
    private val adapter: IOSURLSessionAdapter,
) : MultiplayerV1Transport {
    constructor() : this(NativeIOSURLSessionAdapter())

    override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response {
        val validatedRequest = request.copy(
            url = MultiplayerServer.validateServerUrl(request.url, IOS_NETWORK_CAPABILITIES),
            requiresPublicHttps = true,
        )
        return executeTask(validatedRequest)
    }

    private suspend fun executeTask(request: MultiplayerV1Request): MultiplayerV1Response =
        suspendCancellableCoroutine { continuation ->
            val task = try {
                adapter.createTask(request) { result ->
                    if (!continuation.isActive) return@createTask
                    when (result) {
                        is IOSURLSessionResult.Success -> continuation.resumeWith(
                            Result.success(
                                MultiplayerV1Response(
                                    statusCode = result.statusCode,
                                    body = result.body,
                                    headers = result.headers,
                                    finalUrl = result.finalUrl,
                                )
                            )
                        )
                        is IOSURLSessionResult.Failure -> {
                            val failure = if (result.error == MultiplayerNetworkError.CANCELLED) {
                                MultiplayerRequestCancelledException()
                            } else {
                                MultiplayerNetworkException(result.error)
                            }
                            continuation.resumeWith(Result.failure(failure))
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: MultiplayerNetworkException) {
                continuation.resumeWith(Result.failure(exception))
                return@suspendCancellableCoroutine
            } catch (_: Throwable) {
                continuation.resumeWith(
                    Result.failure(MultiplayerNetworkException(MultiplayerNetworkError.CONNECTION))
                )
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation { task.cancel() }
            if (continuation.isActive) task.resume() else task.cancel()
        }

    private companion object {
        val IOS_NETWORK_CAPABILITIES = PlatformCapabilities(
            multiplayerServerRequiresHttps = true,
            multiplayerApiV1Only = true,
        )
    }
}

internal interface IOSURLSessionTask {
    fun resume()
    fun cancel()
}

internal interface IOSURLSessionAdapter {
    fun createTask(
        request: MultiplayerV1Request,
        completion: (IOSURLSessionResult) -> Unit,
    ): IOSURLSessionTask
}

internal sealed class IOSURLSessionResult {
    data class Success(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, List<String>>,
        val finalUrl: String,
    ) : IOSURLSessionResult()

    data class Failure(val error: MultiplayerNetworkError) : IOSURLSessionResult()
}

/** Prevents NSURLSession from changing a write/delete redirect into a successful GET. */
internal fun validateIOSMultiplayerRedirect(
    originalRequest: MultiplayerV1Request,
    from: String,
    to: String,
    proposedMethod: String,
) {
    if (originalRequest.method != MultiplayerV1HttpMethod.GET
        || proposedMethod != MultiplayerV1HttpMethod.GET.wireName
    ) {
        throw MultiplayerNetworkException(MultiplayerNetworkError.UNSAFE_REDIRECT)
    }
    validateMultiplayerV1Redirect(
        from,
        to,
        originalRequest.headers,
        requiresPublicHttps = true,
        allowCrossOrigin = false,
    )
}
