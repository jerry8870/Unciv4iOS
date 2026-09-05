package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(BaseTestRunner::class)
class PendingTurnUploadPersistenceTest {
    @Test
    fun pendingTurnSurvivesStateRecreationAndCanBeCleared() {
        withTemporaryFiles { files ->
            val original = onlineGame(SERVER_A)
            val pending = original.clone().apply {
                currentPlayer = civilizations.first { it.civID != original.currentPlayer }.civID
                turns = 11
                currentTurnStartTime = 2_000
            }

            PendingTurnUploadState.forGame(original, files).apply {
                getOrCreate(original) { pending }
                markBackgroundTaskExpired()
                assertEquals(
                    TurnUploadUnconfirmedReason.BackgroundTaskExpired,
                    unconfirmedReason,
                )
            }

            val restored = PendingTurnUploadState.forGame(original, files)
            assertTrue(restored.requiresRecovery())
            // Expiration callbacks only update memory so iOS can end its background task promptly.
            // The already-durable record still restores conservatively as an unconfirmed upload.
            assertEquals(
                TurnUploadUnconfirmedReason.UploadFailed,
                restored.unconfirmedReason,
            )
            assertNotNull(restored.get())
            assertEquals(11, restored.get()!!.pendingGame.turns)

            restored.clear()
            assertFalse(PendingTurnUploadState.forGame(original, files).requiresRecovery())
        }
    }

    @Test
    fun sameGameIdOnAnotherServerDoesNotRestorePendingTurn() {
        withTemporaryFiles { files ->
            val serverAGame = onlineGame(SERVER_A)
            PendingTurnUploadState.forGame(serverAGame, files).getOrCreate(serverAGame) {
                serverAGame.clone().apply { turns++ }
            }

            val serverBGame = onlineGame(SERVER_B)
            assertFalse(PendingTurnUploadState.forGame(serverBGame, files).requiresRecovery())
            assertTrue(PendingTurnUploadState.forGame(serverAGame, files).requiresRecovery())
        }
    }

    @Test
    fun malformedLoneTemporaryFileCannotRepresentAnUncertainUpload() {
        withTemporaryFiles { files ->
            val original = onlineGame(SERVER_A)
            val state = PendingTurnUploadState.forGame(original, files)
            state.getOrCreate(original) { original.clone().apply { turns++ } }

            val pendingDirectory = files.getLocalFile("MultiplayerPendingTurns")
            val persistedFile = pendingDirectory.list().single { it.extension() == "json" }
            state.clear()
            val temporaryFile = persistedFile.sibling("${persistedFile.name()}.tmp")
            temporaryFile.writeString("not valid json", false)

            val restored = PendingTurnUploadState.forGame(original, files)

            assertFalse(restored.requiresRecovery())
            assertFalse(temporaryFile.exists())
        }
    }

    private fun onlineGame(serverUrl: String) = TestGame().let { testGame ->
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        testGame.addCiv(testGame.ruleset.nations.getValue("Egypt"), isPlayer = true)
        testGame.gameInfo.apply {
            gameId = "same-game-id"
            currentPlayer = player.civID
            turns = 10
            currentTurnStartTime = 1_000
            gameParameters.isOnlineMultiplayer = true
            gameParameters.multiplayerServerUrl = serverUrl
            version = CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION
            checksum = calculateChecksum()
        }
    }

    private fun withTemporaryFiles(action: (UncivFiles) -> Unit) {
        val dataDirectory = Files.createTempDirectory("unciv-pending-turn-")
        try {
            val files = UncivFiles(Gdx.files, dataDirectory.toString())
            action(files)
        } finally {
            dataDirectory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val SERVER_A = "https://a.example"
        const val SERVER_B = "https://b.example"
    }
}
