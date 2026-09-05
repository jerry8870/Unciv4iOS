package com.unciv.app

import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import org.robovm.apple.foundation.NSData
import org.robovm.apple.foundation.NSError
import org.robovm.apple.foundation.NSHTTPURLResponse
import org.robovm.apple.foundation.NSMutableURLRequest
import org.robovm.apple.foundation.NSOperationQueue
import org.robovm.apple.foundation.NSURL
import org.robovm.apple.foundation.NSURLRequest
import org.robovm.apple.foundation.NSURLRequestCachePolicy
import org.robovm.apple.foundation.NSURLResponse
import org.robovm.apple.foundation.NSURLSession
import org.robovm.apple.foundation.NSURLSessionConfiguration
import org.robovm.apple.foundation.NSURLSessionDataDelegateAdapter
import org.robovm.apple.foundation.NSURLSessionDataTask
import org.robovm.apple.foundation.NSURLSessionResponseDisposition
import org.robovm.apple.foundation.NSURLSessionTask
import org.robovm.objc.block.VoidBlock1
import java.io.IOException

internal class NativeIOSModHttpTransport : IOSModHttpTransport {
    override fun open(request: HttpRequestData, listener: IOSModHttpListener): IOSModHttpTask {
        val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
        val idleSeconds = (timeout?.socketTimeoutMillis ?: 60_000L) / 1_000.0
        val nativeRequest = NSMutableURLRequest(
            NSURL(request.url.toString()), NSURLRequestCachePolicy.ReloadIgnoringLocalCacheData,
            idleSeconds,
        ).apply {
            setHTTPMethod(request.method.value)
            setShouldHandleHTTPCookies(false)
            // Avoid native decompression changing the Content-Length Ktor uses for ZIP progress.
            setHTTPHeaderField(HttpHeaders.AcceptEncoding, "identity")
            for ((name, values) in request.headers.entries())
                for (value in values) addHTTPHeaderField(name, value)
        }
        val configuration = NSURLSessionConfiguration.getEphemeralSessionConfiguration().apply {
            setTimeoutIntervalForRequest(idleSeconds)
            timeout?.requestTimeoutMillis?.takeIf { it != Long.MAX_VALUE }?.let {
                setTimeoutIntervalForResource(it / 1_000.0)
            }
            setWaitsForConnectivity(false)
            setShouldSetHTTPCookies(false)
        }
        val delegate = Delegate(listener)
        val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
        val session = NSURLSession(configuration, delegate, queue)
        val task = session.newDataTask(nativeRequest)
        return object : IOSModHttpTask {
            // Keep the Java delegate and queue alive until Ktor completes the response body.
            private val retainedDelegate = delegate
            private val retainedQueue = queue
            override fun resume() = task.resume()
            override fun cancel() = session.invalidateAndCancel()
        }
    }

    private class Delegate(private val listener: IOSModHttpListener) : NSURLSessionDataDelegateAdapter() {
        override fun willPerformHTTPRedirection(
            session: NSURLSession, task: NSURLSessionTask, response: NSHTTPURLResponse,
            newRequest: NSURLRequest, completionHandler: VoidBlock1<NSURLRequest>,
        ) {
            // Let the existing Ktor redirect plugin process the original 3xx response.
            completionHandler.invoke(null)
        }

        override fun didReceiveResponse(
            session: NSURLSession, task: NSURLSessionDataTask, response: NSURLResponse,
            completionHandler: VoidBlock1<NSURLSessionResponseDisposition>,
        ) {
            if (response !is NSHTTPURLResponse) {
                listener.complete(IOException("Invalid HTTP response"))
                completionHandler.invoke(NSURLSessionResponseDisposition.Cancel)
                return
            }
            val headers = Headers.build {
                for ((name, value) in response.allHeaderFields) append(name, value)
            }
            listener.headers(response.statusCode.toInt(), headers)
            completionHandler.invoke(NSURLSessionResponseDisposition.Allow)
        }

        override fun didReceiveData(session: NSURLSession, task: NSURLSessionDataTask, data: NSData) {
            try {
                listener.bytes(data.bytes)
            } catch (_: Exception) {
                task.cancel()
            }
        }

        override fun didCompleteWithError(session: NSURLSession, task: NSURLSessionTask, error: NSError?) {
            try {
                listener.complete(error?.let { IOException(it.localizedDescription) })
            } finally {
                session.finishTasksAndInvalidate()
            }
        }
    }
}
