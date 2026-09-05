package com.unciv.app

import com.unciv.utils.MultiplayerBackgroundTask
import org.robovm.apple.uikit.UIApplication
import java.util.concurrent.atomic.AtomicBoolean

internal class IOSMultiplayerBackgroundTask(
    private val onExpired: () -> Unit,
) : MultiplayerBackgroundTask {
    private val application = UIApplication.getSharedApplication()
    private val invalidIdentifier = UIApplication.getInvalidBackgroundTask()
    private val expirationDelivered = AtomicBoolean()

    @Volatile
    private var identifier = invalidIdentifier

    @Volatile
    override var isExpired = false
        private set

    init {
        identifier = application.beginBackgroundTask("Unciv multiplayer turn upload") {
            expire()
        }
        if (identifier == invalidIdentifier) {
            // iOS can refuse background time. Treat that exactly like immediate expiration so the
            // caller never starts an upload it cannot safely track after leaving the foreground.
            expire()
        }
    }

    private fun expire() {
        if (!expirationDelivered.compareAndSet(false, true)) return
        isExpired = true
        try {
            onExpired()
        } finally {
            finish()
        }
    }

    override fun finish() = synchronized(this) {
        val taskIdentifier = identifier
        if (taskIdentifier == invalidIdentifier) return@synchronized
        identifier = invalidIdentifier
        application.endBackgroundTask(taskIdentifier)
    }
}
