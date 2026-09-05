package com.unciv.logic.multiplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplayerUpdaterTest {
    @Test
    fun resumeStartsOneUpdaterAndPauseCancelsIt() {
        val jobs = mutableListOf<Job>()
        val inFlightDownloads = mutableListOf<Job>()
        val forcedRefreshes = mutableListOf<Boolean>()
        val updater = MultiplayerUpdater { forceUpdate ->
            forcedRefreshes += forceUpdate
            Job().also {
                jobs += it
                inFlightDownloads += Job(it)
            }
        }

        updater.resume()
        updater.resume()

        assertEquals(1, jobs.size)
        assertEquals(listOf(true), forcedRefreshes)
        assertTrue(updater.isRunning())

        updater.pause()
        updater.pause()

        assertFalse(jobs.single().isActive)
        assertFalse(inFlightDownloads.single().isActive)
        assertFalse(updater.isRunning())

        updater.resume()
        updater.resume()

        assertEquals(2, jobs.size)
        assertEquals(listOf(true, true), forcedRefreshes)
        assertTrue(updater.isRunning())

        updater.pause()
    }

    @Test
    fun rapidResumeWaitsForCancelledUpdaterBeforeStartingOneReplacement() = runBlocking {
        val allowFirstJobToFinish = CompletableDeferred<Unit>()
        val jobs = mutableListOf<Job>()
        var starts = 0
        val updater = MultiplayerUpdater {
            starts++
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (starts == 1) withContext(NonCancellable) {
                        allowFirstJobToFinish.await()
                    }
                }
            }.also { jobs += it }
        }

        updater.resume()
        val firstJob = jobs.single()
        updater.pause()
        updater.resume()
        updater.resume()

        assertFalse(firstJob.isCompleted)
        assertEquals(1, starts)

        allowFirstJobToFinish.complete(Unit)
        firstJob.join()

        assertEquals(2, starts)
        assertEquals(2, jobs.size)
        assertTrue(jobs.last().isActive)

        updater.pause()
        jobs.last().join()
    }
}
