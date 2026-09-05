package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.GameInfoPreview
import org.threeten.bp.Duration
import org.threeten.bp.Instant

enum class MultiplayerGameUiStatus {
    Refreshing,
    YourTurn,
    WaitingForOpponent,
    RefreshFailed,
    Unavailable,
}

class MultiplayerGameUiModel(
    val name: String,
    val status: MultiplayerGameUiStatus,
    val hasPreview: Boolean,
    val isUsersTurn: Boolean,
    val currentPlayer: String?,
    val ownCivilization: String?,
    val turn: Int?,
    val updatedAgo: Duration,
    val currentTurnDuration: Duration?,
    val turnTimeRemaining: Duration?,
    val difficulty: String?,
    val baseRuleset: String?,
    val mods: List<String>,
    val serverUrl: String?,
)

fun buildMultiplayerGameUiModel(
    name: String,
    preview: GameInfoPreview?,
    hasError: Boolean,
    lastUpdate: Instant,
    userId: String,
    isRefreshing: Boolean = false,
    now: Instant = Instant.now(),
): MultiplayerGameUiModel {
    val currentPlayerCiv = preview?.civilizations
        ?.firstOrNull { it.civID == preview.currentPlayer }
        ?: preview?.civilizations?.firstOrNull { it.civName == preview.currentPlayer }
    val ownCiv = preview?.civilizations?.firstOrNull { it.playerId == userId }
    val isUsersTurn = currentPlayerCiv?.playerId == userId
    val currentTurnDuration = preview?.takeIf { currentPlayerCiv != null }?.let {
        nonNegative(Duration.between(Instant.ofEpochMilli(it.currentTurnStartTime), now))
    }
    val turnTimeRemaining = if (currentTurnDuration == null || currentPlayerCiv == null) null
        else nonNegative(
            Duration.ofMinutes(currentPlayerCiv.playerMinutesBeforeForceResign.toLong())
                .minus(currentTurnDuration)
        )
    val status = when {
        isRefreshing -> MultiplayerGameUiStatus.Refreshing
        preview == null || currentPlayerCiv == null -> MultiplayerGameUiStatus.Unavailable
        hasError -> MultiplayerGameUiStatus.RefreshFailed
        isUsersTurn -> MultiplayerGameUiStatus.YourTurn
        else -> MultiplayerGameUiStatus.WaitingForOpponent
    }

    return MultiplayerGameUiModel(
        name = name,
        status = status,
        hasPreview = preview != null,
        isUsersTurn = isUsersTurn,
        currentPlayer = currentPlayerCiv?.civName ?: preview?.currentPlayer,
        ownCivilization = ownCiv?.civName,
        turn = preview?.turns,
        updatedAgo = nonNegative(Duration.between(lastUpdate, now)),
        currentTurnDuration = currentTurnDuration,
        turnTimeRemaining = turnTimeRemaining,
        difficulty = preview?.difficulty,
        baseRuleset = preview?.gameParameters?.baseRuleset,
        mods = preview?.gameParameters?.mods?.toList().orEmpty(),
        serverUrl = preview?.gameParameters?.multiplayerServerUrl,
    )
}

private fun nonNegative(duration: Duration): Duration =
    if (duration.isNegative) Duration.ZERO else duration
