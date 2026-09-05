package com.unciv.logic.multiplayer

import com.unciv.logic.multiplayer.storage.MultiplayerV1HttpMethod
import com.unciv.logic.multiplayer.storage.MultiplayerV1Request
import com.unciv.logic.multiplayer.storage.MultiplayerV1Response
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Cancels ordinary requests on pause while allowing an explicitly scoped turn upload to finish. */
class ForegroundMultiplayerTransport(
    private val transport: MultiplayerV1Transport,
) : MultiplayerV1Transport {
    private val lock = Any()
    private val activeForegroundRequests = mutableSetOf<Job>()
    private var isForeground = true

    override suspend fun execute(request: MultiplayerV1Request): MultiplayerV1Response = coroutineScope {
        // Keep cancellation scoped to the network child. The caller remains active long enough to
        // close modal UI or restore input before it propagates the CancellationException.
        val mayContinueInBackground = request.method == MultiplayerV1HttpMethod.PUT
            && currentCoroutineContext()[TurnUploadContinuation]?.owner === this@ForegroundMultiplayerTransport
        val operation = async(start = CoroutineStart.LAZY) { transport.execute(request) }
        synchronized(lock) {
            if (!isForeground && !mayContinueInBackground) {
                operation.cancel()
                throw CancellationException("Foreground multiplayer requests are paused")
            }
            if (!mayContinueInBackground) activeForegroundRequests += operation
        }
        try {
            operation.await()
        } finally {
            synchronized(lock) { activeForegroundRequests -= operation }
        }
    }

    /** Grants only this coroutine's turn PUTs permission to outlive a foreground-to-background transition. */
    suspend fun <T> withTurnUploadContinuation(block: suspend () -> T): T {
        synchronized(lock) {
            if (!isForeground) throw CancellationException("Turn upload cannot start in the background")
        }
        return withContext(TurnUploadContinuation(this)) { block() }
    }

    fun pause() {
        val requests = synchronized(lock) {
            isForeground = false
            activeForegroundRequests.toList()
        }
        requests.forEach { it.cancel() }
    }

    fun resume() = synchronized(lock) {
        isForeground = true
    }

    private class TurnUploadContinuation(
        val owner: ForegroundMultiplayerTransport,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TurnUploadContinuation>
    }
}

/** Runs cancellation cleanup immediately, then preserves structured cancellation for the caller. */
fun rethrowCancellationAfterCleanup(
    exception: CancellationException,
    cleanup: () -> Unit,
): Nothing {
    cleanup()
    throw exception
}
