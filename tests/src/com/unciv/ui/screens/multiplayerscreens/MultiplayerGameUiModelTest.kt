package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.GameInfoPreview
import com.unciv.logic.civilization.CivilizationInfoPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Duration
import org.threeten.bp.Instant

class MultiplayerGameUiModelTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")

    @Test
    fun buildsYourTurnDetailsAndRemainingTime() {
        val preview = preview(
            currentPlayer = "austria-id",
            civilizations = listOf(
                civilization("austria-id", "Austria", "my-player", 180),
                civilization("china-id", "China", "other-player", 240),
            ),
        )

        val model = buildMultiplayerGameUiModel(
            name = "Weekend game",
            preview = preview,
            hasError = false,
            lastUpdate = now.minus(Duration.ofMinutes(5)),
            userId = "my-player",
            now = now,
        )

        assertEquals("Weekend game", model.name)
        assertEquals(MultiplayerGameUiStatus.YourTurn, model.status)
        assertTrue(model.hasPreview)
        assertTrue(model.isUsersTurn)
        assertEquals("Austria", model.currentPlayer)
        assertEquals("Austria", model.ownCivilization)
        assertEquals(42, model.turn)
        assertEquals(Duration.ofMinutes(5), model.updatedAgo)
        assertEquals(Duration.ofHours(1), model.currentTurnDuration)
        assertEquals(Duration.ofHours(2), model.turnTimeRemaining)
        assertEquals("Prince", model.difficulty)
        assertEquals("Civ V - Gods & Kings", model.baseRuleset)
        assertEquals(listOf("Alpha mod", "Beta mod"), model.mods)
        assertEquals("https://example.com", model.serverUrl)
    }

    @Test
    fun reportsWaitingForOpponentWhileKeepingOwnCivilization() {
        val preview = preview(
            currentPlayer = "China",
            civilizations = listOf(
                civilization("austria-id", "Austria", "my-player", 180),
                civilization("china-id", "China", "other-player", 240),
            ),
        )

        val model = buildMultiplayerGameUiModel(
            name = "Weekend game",
            preview = preview,
            hasError = false,
            lastUpdate = now,
            userId = "my-player",
            now = now,
        )

        assertEquals(MultiplayerGameUiStatus.WaitingForOpponent, model.status)
        assertFalse(model.isUsersTurn)
        assertEquals("China", model.currentPlayer)
        assertEquals("Austria", model.ownCivilization)
        assertEquals(Duration.ofHours(3), model.turnTimeRemaining)
    }

    @Test
    fun prioritizesRefreshingThenReportsFailureAndUnavailablePreview() {
        val preview = preview(
            currentPlayer = "austria-id",
            civilizations = listOf(
                civilization("austria-id", "Austria", "my-player", 180),
            ),
        )

        val refreshing = buildMultiplayerGameUiModel(
            name = "Retrying game",
            preview = preview,
            hasError = true,
            lastUpdate = now,
            userId = "my-player",
            isRefreshing = true,
            now = now,
        )
        val failed = buildMultiplayerGameUiModel(
            name = "Failed game",
            preview = preview,
            hasError = true,
            lastUpdate = now,
            userId = "my-player",
            now = now,
        )
        val unavailable = buildMultiplayerGameUiModel(
            name = "Unavailable game",
            preview = null,
            hasError = true,
            lastUpdate = now,
            userId = "my-player",
            now = now,
        )

        assertEquals(MultiplayerGameUiStatus.Refreshing, refreshing.status)
        assertEquals(MultiplayerGameUiStatus.RefreshFailed, failed.status)
        assertEquals(MultiplayerGameUiStatus.Unavailable, unavailable.status)
        assertFalse(unavailable.hasPreview)
        assertFalse(unavailable.isUsersTurn)
        assertNull(unavailable.currentPlayer)
        assertNull(unavailable.ownCivilization)
        assertNull(unavailable.turn)
        assertNull(unavailable.currentTurnDuration)
        assertNull(unavailable.turnTimeRemaining)
        assertNull(unavailable.difficulty)
        assertNull(unavailable.baseRuleset)
        assertTrue(unavailable.mods.isEmpty())
        assertNull(unavailable.serverUrl)
    }

    @Test
    fun clampsFutureTimestampsAndExpiredRemainingTimeToZero() {
        val futurePreview = preview(
            currentPlayer = "austria-id",
            civilizations = listOf(
                civilization("austria-id", "Austria", "my-player", 30),
            ),
        ).apply {
            currentTurnStartTime = now.plus(Duration.ofMinutes(15)).toEpochMilli()
        }
        val futureModel = buildMultiplayerGameUiModel(
            name = "Future timestamps",
            preview = futurePreview,
            hasError = false,
            lastUpdate = now.plus(Duration.ofMinutes(10)),
            userId = "my-player",
            now = now,
        )

        val expiredPreview = preview(
            currentPlayer = "austria-id",
            civilizations = listOf(
                civilization("austria-id", "Austria", "my-player", 30),
            ),
        ).apply {
            currentTurnStartTime = now.minus(Duration.ofHours(2)).toEpochMilli()
        }
        val expiredModel = buildMultiplayerGameUiModel(
            name = "Expired turn",
            preview = expiredPreview,
            hasError = false,
            lastUpdate = now,
            userId = "my-player",
            now = now,
        )

        assertEquals(Duration.ZERO, futureModel.updatedAgo)
        assertEquals(Duration.ZERO, futureModel.currentTurnDuration)
        assertEquals(Duration.ofMinutes(30), futureModel.turnTimeRemaining)
        assertEquals(Duration.ZERO, expiredModel.turnTimeRemaining)
    }

    @Test
    fun reportsMalformedPreviewUnavailableAndPrefersCivilizationId() {
        val malformed = buildMultiplayerGameUiModel(
            name = "Malformed game",
            preview = GameInfoPreview(),
            hasError = false,
            lastUpdate = now,
            userId = "my-player",
            now = now,
        )
        val collidingPreview = preview(
            currentPlayer = "shared-value",
            civilizations = listOf(
                civilization("other-id", "shared-value", "other-player", 60),
                civilization("shared-value", "Austria", "my-player", 180),
            ),
        )
        val collision = buildMultiplayerGameUiModel(
            name = "Collision game",
            preview = collidingPreview,
            hasError = false,
            lastUpdate = now,
            userId = "my-player",
            now = now,
        )

        assertEquals(MultiplayerGameUiStatus.Unavailable, malformed.status)
        assertNull(malformed.currentTurnDuration)
        assertNull(malformed.turnTimeRemaining)
        assertEquals(MultiplayerGameUiStatus.YourTurn, collision.status)
        assertEquals("Austria", collision.currentPlayer)
    }

    private fun preview(
        currentPlayer: String,
        civilizations: List<CivilizationInfoPreview>,
    ) = GameInfoPreview().apply {
        this.currentPlayer = currentPlayer
        this.civilizations = civilizations.toMutableList()
        turns = 42
        difficulty = "Prince"
        currentTurnStartTime = now.minus(Duration.ofHours(1)).toEpochMilli()
        gameParameters.baseRuleset = "Civ V - Gods & Kings"
        gameParameters.mods.addAll(listOf("Alpha mod", "Beta mod"))
        gameParameters.multiplayerServerUrl = "https://example.com"
    }

    private fun civilization(
        id: String,
        name: String,
        playerId: String,
        minutesBeforeForceResign: Int,
    ) = CivilizationInfoPreview().apply {
        civID = id
        civName = name
        this.playerId = playerId
        playerMinutesBeforeForceResign = minutesBeforeForceResign
    }
}
