package com.unciv.logic.multiplayer

import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.CompatibilityVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

class PendingTurnUploadTest {
    private fun game(player: String, turns: Int, started: Long) = GameInfo().apply {
        gameId = "test-game"
        currentPlayer = player
        this.turns = turns
        currentTurnStartTime = started
        tileMap.mapParameters.seed = 42
        version = CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION
    }

    private fun sealedGame(player: String, turns: Int, started: Long) =
        game(player, turns, started).apply { checksum = calculateChecksum() }

    private fun preview(player: String, turns: Int, started: Long) = GameInfoPreview().apply {
        currentPlayer = player
        this.turns = turns
        currentTurnStartTime = started
    }

    @Test
    fun turnProcessingGateAllowsOnlyOneConcurrentCaller() {
        val state = PendingTurnUploadState()
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val results = try {
            List(2) {
                executor.submit<Boolean> {
                    barrier.await()
                    state.tryStartTurnProcessing()
                }
            }.map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it })
        assertTrue(state.isTurnProcessing())
        state.finishTurnProcessing()
        assertFalse(state.isTurnProcessing())
        assertTrue(state.tryStartTurnProcessing())
    }

    @Test
    fun retryKeepsTheSameAdvancedGameInstance() {
        val state = PendingTurnUploadState()
        val original = game("Rome", 10, 1_000)
        val pending = game("Egypt", 11, 2_000)
        var cloneCount = 0

        val first = state.getOrCreate(original) {
            cloneCount++
            pending
        }
        state.markUnconfirmed(TurnUploadUnconfirmedReason.UploadFailed)
        val retry = state.getOrCreate(original) {
            cloneCount++
            game("Persia", 12, 3_000)
        }

        assertSame(first, retry)
        assertSame(pending, retry.pendingGame)
        assertEquals(1, cloneCount)
    }

    @Test
    fun fullGameAndPreviewMatchingPendingConfirmUpload() {
        val pending = game("Egypt", 11, 2_000)
        val state = PendingTurnUploadState().getOrCreate(sealedGame("Rome", 10, 1_000)) {
            pending
        }

        assertEquals(
            PendingTurnUploadResolution.Confirmed,
            state.resolve(sealedGame("Egypt", 11, 2_000), pending.asPreview()),
        )
    }

    @Test
    fun matchingTurnFieldsWithDifferentPreviewContentRequiresRepair() {
        val state = PendingTurnUploadState().getOrCreate(sealedGame("Rome", 10, 1_000)) {
            game("Egypt", 11, 2_000)
        }

        assertEquals(
            PendingTurnUploadResolution.RepairPreview,
            state.resolve(sealedGame("Egypt", 11, 2_000), preview("Egypt", 11, 2_000)),
        )
    }

    @Test
    fun fullGameAndPreviewMatchingOriginalAllowRetry() {
        val state = PendingTurnUploadState().getOrCreate(sealedGame("Rome", 10, 1_000)) {
            game("Egypt", 11, 2_000)
        }

        assertEquals(
            PendingTurnUploadResolution.RetryUpload,
            state.resolve(sealedGame("Rome", 10, 1_000), preview("Rome", 10, 1_000)),
        )
    }

    @Test
    fun pendingFullGameWithOldOrMissingPreviewRepairsOnlyPreview() {
        val state = PendingTurnUploadState().getOrCreate(sealedGame("Rome", 10, 1_000)) {
            game("Egypt", 11, 2_000)
        }

        assertEquals(
            PendingTurnUploadResolution.RepairPreview,
            state.resolve(sealedGame("Egypt", 11, 2_000), preview("Rome", 10, 1_000)),
        )
        assertEquals(
            PendingTurnUploadResolution.RepairPreview,
            state.resolve(sealedGame("Egypt", 11, 2_000), null),
        )
    }

    @Test
    fun differentOrCorruptedFullGameRejectsStaleUpload() {
        val state = PendingTurnUploadState().getOrCreate(sealedGame("Rome", 10, 1_000)) {
            game("Egypt", 11, 2_000)
        }
        val corrupted = sealedGame("Egypt", 11, 2_000).apply { turns = 99 }

        assertEquals(
            PendingTurnUploadResolution.ServerChanged,
            state.resolve(sealedGame("Persia", 12, 3_000), preview("Egypt", 11, 2_000)),
        )
        assertEquals(
            PendingTurnUploadResolution.InvalidServerGame,
            state.resolve(corrupted, preview("Egypt", 11, 2_000)),
        )
    }

    @Test
    fun originalFingerprintIsAnImmutableSnapshot() {
        val original = sealedGame("Rome", 10, 1_000)
        val state = PendingTurnUploadState().getOrCreate(original) {
            game("Egypt", 11, 2_000)
        }
        original.currentPlayer = "Persia"
        original.turns = 99
        original.currentTurnStartTime = 9_999

        assertEquals(
            PendingTurnUploadResolution.RetryUpload,
            state.resolve(sealedGame("Rome", 10, 1_000), preview("Rome", 10, 1_000)),
        )
    }

    @Test
    fun authenticationCancellationKeepsPendingTurnUnconfirmed() {
        val state = PendingTurnUploadState()
        val pending = state.getOrCreate(game("Rome", 10, 1_000)) {
            game("Egypt", 11, 2_000)
        }

        state.markUnconfirmed(TurnUploadUnconfirmedReason.AuthenticationCancelled)

        assertSame(pending, state.get())
        assertEquals(TurnUploadUnconfirmedReason.AuthenticationCancelled, state.unconfirmedReason)
    }

    @Test
    fun backgroundTaskExpirationMarksPendingBeforeBlockedUploadReturns() = runBlocking {
        val state = PendingTurnUploadState()
        val pending = state.getOrCreate(game("Rome", 10, 1_000)) {
            game("Egypt", 11, 2_000)
        }
        val allowUploadToReturn = CompletableDeferred<Unit>()
        var uploadReturned = false
        val uploadJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { allowUploadToReturn.await() }
                uploadReturned = true
            }
        }

        state.markBackgroundTaskExpired()

        assertSame(pending, state.get())
        assertEquals(TurnUploadUnconfirmedReason.BackgroundTaskExpired, state.unconfirmedReason)
        assertFalse(uploadReturned)
        assertFalse(uploadJob.isCompleted)
        assertTrue(uploadJob.isActive)

        uploadJob.cancel()
        allowUploadToReturn.complete(Unit)
        uploadJob.join()

        assertTrue(uploadReturned)
    }
}
