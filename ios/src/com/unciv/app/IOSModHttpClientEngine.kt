package com.unciv.app

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Keeps the GitHub/Ktor pipeline while avoiding CIO's unsupported RoboVM JDK calls. */
class IOSModHttpClientEngine internal constructor(
    private val transport: IOSModHttpTransport,
) : HttpClientEngineBase("ios-mods") {
    constructor() : this(NativeIOSModHttpTransport())

    override val config = HttpClientEngineConfig()
    override val supportedCapabilities = setOf(HttpTimeoutCapability)

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        require(data.method == HttpMethod.Get) { "The Mod transport only supports GET requests" }
        val context = callContext()
        val requestTime = GMTDate()
        val response = CompletableDeferred<HttpResponseData>(context[Job])
        val body = ByteChannel(autoFlush = true)
        val task = transport.open(data, object : IOSModHttpListener {
            override fun headers(status: Int, headers: Headers) {
                response.complete(HttpResponseData(
                    HttpStatusCode.fromValue(status), requestTime, headers,
                    HttpProtocolVersion.HTTP_1_1, body, context,
                ))
            }

            override fun bytes(bytes: ByteArray) {
                // NSURLSession calls this on its own queue. Backpressure bounds buffered ZIP data.
                runBlocking { body.writeFully(bytes) }
            }

            override fun complete(error: IOException?) {
                if (error == null) body.close() else body.cancel(error)
                if (!response.isCompleted)
                    response.completeExceptionally(error ?: IOException("Missing HTTP response"))
            }
        })
        context[Job]!!.invokeOnCompletion { cause ->
            if (cause != null) body.cancel(cause)
            task.cancel()
        }
        task.resume()
        return response.await()
    }
}

internal interface IOSModHttpTransport {
    fun open(request: HttpRequestData, listener: IOSModHttpListener): IOSModHttpTask
}

internal interface IOSModHttpTask {
    fun resume()
    fun cancel()
}

internal interface IOSModHttpListener {
    fun headers(status: Int, headers: Headers)
    fun bytes(bytes: ByteArray)
    fun complete(error: IOException?)
}
