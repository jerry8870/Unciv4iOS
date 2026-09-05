package com.unciv.app

import com.unciv.UncivGame
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkError
import com.unciv.logic.multiplayer.storage.MultiplayerNetworkException
import com.unciv.logic.multiplayer.storage.MultiplayerV1Request
import org.robovm.apple.foundation.NSData
import org.robovm.apple.foundation.NSError
import org.robovm.apple.foundation.NSHTTPURLResponse
import org.robovm.apple.foundation.NSMutableURLRequest
import org.robovm.apple.foundation.NSOperationQueue
import org.robovm.apple.foundation.NSURLError
import org.robovm.apple.foundation.NSURLErrorCode
import org.robovm.apple.foundation.NSURLRequest
import org.robovm.apple.foundation.NSURLRequestCachePolicy
import org.robovm.apple.foundation.NSURLSession
import org.robovm.apple.foundation.NSURLSessionConfiguration
import org.robovm.apple.foundation.NSURLSessionDataTask
import org.robovm.apple.foundation.NSURLSessionTask
import org.robovm.apple.foundation.NSURLSessionTaskDelegateAdapter
import org.robovm.apple.foundation.NSURLSessionTaskMetrics
import org.robovm.objc.block.VoidBlock1
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class NativeIOSURLSessionAdapter : IOSURLSessionAdapter {
    override fun createTask(
        request: MultiplayerV1Request,
        completion: (IOSURLSessionResult) -> Unit,
    ): IOSURLSessionTask {
        val nativeRequest = NSMutableURLRequest(
            org.robovm.apple.foundation.NSURL(request.url),
            NSURLRequestCachePolicy.ReloadIgnoringLocalCacheData,
            request.readTimeoutMillis.toSeconds(),
        )
        nativeRequest.setHTTPMethod(request.method.wireName)
        nativeRequest.setShouldHandleHTTPCookies(false)
        for ((name, value) in request.headers) nativeRequest.setHTTPHeaderField(name, value)
        if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            nativeRequest.setHTTPHeaderField(
                "User-Agent",
                UncivGame.getUserAgent("Multiplayer-v1-iOS"),
            )
        }
        request.body?.let { body ->
            if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                nativeRequest.setHTTPHeaderField("Content-Type", "text/plain")
            }
            nativeRequest.setHTTPBody(NSData(body.toByteArray(Charsets.UTF_8)))
        }

        val redirectFailure = AtomicReference<MultiplayerNetworkError?>()
        val connectionEstablished = AtomicBoolean(false)
        val delegate = RedirectDelegate(request, redirectFailure, connectionEstablished)
        val delegateQueue = NSOperationQueue()
        val configuration = NSURLSessionConfiguration.getEphemeralSessionConfiguration().apply {
            setTimeoutIntervalForRequest(request.readTimeoutMillis.toSeconds())
            setTimeoutIntervalForResource(request.totalTimeoutSeconds())
            setWaitsForConnectivity(false)
            setShouldSetHTTPCookies(false)
        }
        val session = NSURLSession(configuration, delegate, delegateQueue)
        val task = session.newDataTask(nativeRequest) { data, response, error ->
            val result = when {
                redirectFailure.get() != null -> IOSURLSessionResult.Failure(redirectFailure.get()!!)
                error != null -> IOSURLSessionResult.Failure(
                    mapError(error, connectionEstablished.get())
                )
                response !is NSHTTPURLResponse -> IOSURLSessionResult.Failure(
                    MultiplayerNetworkError.INVALID_RESPONSE
                )
                else -> IOSURLSessionResult.Success(
                    statusCode = response.statusCode.toInt(),
                    body = data?.bytes?.toString(Charsets.UTF_8).orEmpty(),
                    headers = response.allHeaderFields.mapValues { (_, value) -> listOf(value) },
                    finalUrl = response.url?.absoluteString ?: request.url,
                )
            }
            try {
                completion(result)
            } finally {
                session.finishTasksAndInvalidate()
            }
        }
        return NativeTask(task, session, delegate, delegateQueue)
    }

    private class RedirectDelegate(
        private val originalRequest: MultiplayerV1Request,
        private val failure: AtomicReference<MultiplayerNetworkError?>,
        private val connectionEstablished: AtomicBoolean,
    ) : NSURLSessionTaskDelegateAdapter() {
        override fun didFinishCollectingMetrics(
            session: NSURLSession,
            task: NSURLSessionTask,
            metrics: NSURLSessionTaskMetrics,
        ) {
            var requestStarted = false
            for (transaction in metrics.transactionMetrics) {
                // Use the final transaction so a redirect whose new origin never connected is
                // still classified as a connect timeout.
                requestStarted = transaction.requestStartDate != null
            }
            connectionEstablished.set(requestStarted)
        }

        override fun willPerformHTTPRedirection(
            session: NSURLSession,
            task: NSURLSessionTask,
            response: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: VoidBlock1<NSURLRequest>,
        ) {
            if (failure.get() != null) {
                completionHandler.invoke(null)
                return
            }
            try {
                validateIOSMultiplayerRedirect(
                    originalRequest,
                    response.url.absoluteString,
                    newRequest.url.absoluteString,
                    newRequest.httpMethod,
                )
                completionHandler.invoke(newRequest)
            } catch (exception: MultiplayerNetworkException) {
                failure.compareAndSet(null, exception.error)
                completionHandler.invoke(null)
            } catch (_: Throwable) {
                failure.compareAndSet(null, MultiplayerNetworkError.UNSAFE_REDIRECT)
                completionHandler.invoke(null)
            }
        }
    }

    private class NativeTask(
        private val task: NSURLSessionDataTask,
        @Suppress("unused") private val session: NSURLSession,
        @Suppress("unused") private val delegate: RedirectDelegate,
        @Suppress("unused") private val delegateQueue: NSOperationQueue,
    ) : IOSURLSessionTask {
        override fun resume() = task.resume()
        override fun cancel() = task.cancel()
    }

    private fun mapError(
        error: NSError,
        connectionEstablished: Boolean,
    ): MultiplayerNetworkError {
        if (error.domain != NSURLError.getClassDomain()) return MultiplayerNetworkError.CONNECTION
        val code = try {
            NSURLErrorCode.valueOf(error.code)
        } catch (_: IllegalArgumentException) {
            return MultiplayerNetworkError.CONNECTION
        }
        return when (code) {
            NSURLErrorCode.Cancelled -> MultiplayerNetworkError.CANCELLED
            NSURLErrorCode.BadURL,
            NSURLErrorCode.UnsupportedURL -> MultiplayerNetworkError.INVALID_URL
            NSURLErrorCode.CannotFindHost,
            NSURLErrorCode.DNSLookupFailed -> MultiplayerNetworkError.DNS
            NSURLErrorCode.TimedOut -> classifyIOSTimeout(connectionEstablished)
            NSURLErrorCode.HTTPTooManyRedirects -> MultiplayerNetworkError.TOO_MANY_REDIRECTS
            NSURLErrorCode.RedirectToNonExistentLocation,
            NSURLErrorCode.BadServerResponse,
            NSURLErrorCode.CannotDecodeRawData,
            NSURLErrorCode.CannotDecodeContentData,
            NSURLErrorCode.CannotParseResponse -> MultiplayerNetworkError.INVALID_RESPONSE
            NSURLErrorCode.AppTransportSecurityRequiresSecureConnection,
            NSURLErrorCode.SecureConnectionFailed,
            NSURLErrorCode.ServerCertificateHasBadDate,
            NSURLErrorCode.ServerCertificateUntrusted,
            NSURLErrorCode.ServerCertificateHasUnknownRoot,
            NSURLErrorCode.ServerCertificateNotYetValid,
            NSURLErrorCode.ClientCertificateRejected,
            NSURLErrorCode.ClientCertificateRequired -> MultiplayerNetworkError.TLS
            else -> MultiplayerNetworkError.CONNECTION
        }
    }

    private fun Int.toSeconds() = coerceAtLeast(1) / 1_000.0

    private fun MultiplayerV1Request.totalTimeoutSeconds(): Double =
        (connectTimeoutMillis.toLong().coerceAtLeast(1) + readTimeoutMillis.toLong().coerceAtLeast(1)) /
            1_000.0
}

internal fun classifyIOSTimeout(connectionEstablished: Boolean) =
    if (connectionEstablished) MultiplayerNetworkError.READ_TIMEOUT
    else MultiplayerNetworkError.CONNECT_TIMEOUT
