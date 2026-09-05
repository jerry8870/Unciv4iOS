package com.unciv.logic.multiplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.threeten.bp.Instant
import java.util.concurrent.atomic.AtomicReference

class MultiplayerCancellationTest {
    @Test
    fun foregroundDownloadCancellationRunsCleanupAndRethrows() {
        val cancellation = CancellationException("lifecycle pause")
        var cleanupRan = false

        try {
            rethrowCancellationAfterCleanup(cancellation) { cleanupRan = true }
            fail("CancellationException should be rethrown")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }

        assertTrue(cleanupRan)
    }

    @Test
    fun attemptActionRestoresTimestampAndRethrowsCancellation() = runBlocking {
        val originalTimestamp = Instant.ofEpochMilli(123)
        val lastSuccessfulExecution = AtomicReference<Instant?>(originalTimestamp)
        var failureWasRecorded = false

        try {
            attemptAction(
                lastSuccessfulExecution,
                onNoExecution = { Unit },
                onFailed = {
                    failureWasRecorded = true
                },
            ) {
                throw CancellationException("lifecycle pause")
            }
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
            // Expected structured cancellation.
        }

        assertEquals(originalTimestamp, lastSuccessfulExecution.get())
        assertFalse(failureWasRecorded)
    }
}
