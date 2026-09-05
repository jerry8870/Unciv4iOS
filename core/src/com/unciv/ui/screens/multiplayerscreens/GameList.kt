package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.event.EventBus
import com.unciv.logic.multiplayer.HasMultiplayerGameName
import com.unciv.logic.multiplayer.MultiplayerGameNameChanged
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.logic.multiplayer.MultiplayerGameUpdateEnded
import com.unciv.logic.multiplayer.MultiplayerGameUpdateFailed
import com.unciv.logic.multiplayer.MultiplayerGameUpdateStarted
import com.unciv.logic.multiplayer.MultiplayerGameUpdateSucceeded
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.formatShort
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

class GameList(
    private val onSelected: (String) -> Unit,
) : Table() {
    private val gameDisplays = mutableMapOf<String, GameDisplay>()
    private val events = EventBus.EventReceiver()
    private var selectedName: String? = null

    init {
        top()
        events.receive(MultiplayerGameNameChanged::class) { update() }
        update()
    }

    fun update() {
        gameDisplays.values.forEach(GameDisplay::dispose)
        gameDisplays.clear()
        clearChildren()

        val userId = UncivGame.Current.settings.multiplayer.getUserId()
        val games = UncivGame.Current.onlineMultiplayer.games.sortedWith(
            compareByDescending<MultiplayerGamePreview> {
                buildMultiplayerGameUiModel(
                    it.name,
                    it.preview,
                    it.error != null,
                    it.getLastUpdate(),
                    userId,
                ).isUsersTurn
            }.thenBy { it.name }
        )
        for (game in games) {
            val gameDisplay = GameDisplay(game, userId, onSelected)
            gameDisplays[game.name] = gameDisplay
            add(gameDisplay).growX().minWidth(0f).padBottom(6f).row()
        }
        select(selectedName)
    }

    fun select(name: String?) {
        selectedName = name
        gameDisplays.forEach { (gameName, display) ->
            display.setSelected(gameName == name)
        }
    }

    fun dispose() {
        gameDisplays.values.forEach(GameDisplay::dispose)
        events.stopReceiving()
    }
}

private class GameDisplay(
    private val multiplayerGame: MultiplayerGamePreview,
    private val userId: String,
    private val onSelected: (String) -> Unit,
) : Table() {
    private var preview: GameInfoPreview? = multiplayerGame.preview
    private var hasError = multiplayerGame.error != null
    private var isRefreshing = false

    private val nameLabel = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val civilizationLabel = "".toLabel(fontSize = multiplayerContentFontSize, alignment = Align.center)
    private val civilizationBadge = Table().apply {
        add(civilizationLabel).minWidth(0f).center()
    }
    private val metadataLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }
    private val statusLabel = "".toLabel(fontSize = multiplayerSecondaryFontSize, alignment = Align.center).apply {
        setEllipsis(true)
    }
    private val statusBadge = Table()
    private val events = EventBus.EventReceiver()

    init {
        pad(9f, 10f, 9f, 10f)

        val copy = Table().apply {
            add(nameLabel).growX().minWidth(0f).left().row()
            add(metadataLabel).growX().minWidth(0f).left().padTop(3f)
        }
        statusBadge.add(statusLabel).minWidth(0f).pad(6f, 10f, 6f, 10f)
        add(civilizationBadge).size(46f).padRight(10f)
        add(copy).growX().minWidth(0f).left()
        add(statusBadge).minWidth(0f).right().padLeft(8f)

        onClick { onSelected(multiplayerGame.name) }

        val isOurGame: (HasMultiplayerGameName) -> Boolean = { it.name == multiplayerGame.name }
        events.receive(MultiplayerGameUpdateStarted::class, isOurGame) {
            isRefreshing = true
            updateContent()
        }
        events.receive(MultiplayerGameUpdateEnded::class, isOurGame) {
            isRefreshing = false
            updateContent()
        }
        events.receive(MultiplayerGameUpdateSucceeded::class, isOurGame) {
            preview = it.preview
            hasError = false
            updateContent()
        }
        events.receive(MultiplayerGameUpdateFailed::class, isOurGame) {
            hasError = true
            updateContent()
        }

        updateContent()
        setSelected(false)
    }

    fun setSelected(selected: Boolean) {
        val color = if (selected) SelectedBackground else NormalBackground
        background = BaseScreen.skinStrings.getUiBackground(
            if (selected) "MultiplayerScreen/GameRowSelected" else "MultiplayerScreen/GameRow",
            BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            color,
        )
    }

    private fun updateContent() {
        val model = buildMultiplayerGameUiModel(
            multiplayerGame.name,
            preview,
            hasError,
            multiplayerGame.getLastUpdate(),
            userId,
            isRefreshing,
        )
        nameLabel.setText(model.name)
        civilizationLabel.setText(
            model.currentPlayer?.let { "{$it}".tr().take(1) } ?: "?"
        )

        val turn = model.turn?.let { "Turn [$it]".tr() } ?: "Turn unavailable".tr()
        val currentPlayer = model.currentPlayer?.let { "{$it}".tr() } ?: "Unknown".tr()
        val updated = "Updated [${model.updatedAgo.formatShort()}] ago".tr()
        metadataLabel.setText("$turn  ·  $currentPlayer  ·  $updated")

        val (statusText, statusColor) = when (model.status) {
            MultiplayerGameUiStatus.Refreshing -> "Refreshing..." to Color.GOLD
            MultiplayerGameUiStatus.YourTurn -> "Your turn" to Positive
            MultiplayerGameUiStatus.WaitingForOpponent -> "Waiting for opponent" to Color.LIGHT_GRAY
            MultiplayerGameUiStatus.RefreshFailed -> "Refresh failed" to Negative
            MultiplayerGameUiStatus.Unavailable -> "Unavailable" to Color.GRAY
        }
        statusLabel.setText(statusText.tr())
        statusLabel.setFontColor(statusColor)
        statusBadge.background = BaseScreen.skinStrings.getUiBackground(
            "MultiplayerScreen/StatusBadge",
            BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            statusColor.darken(0.68f),
        )
        civilizationBadge.background = BaseScreen.skinStrings.getUiBackground(
            "MultiplayerScreen/CivilizationBadge",
            BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            statusColor.darken(0.62f),
        )
    }

    fun dispose() {
        events.stopReceiving()
    }

    companion object {
        private val NormalBackground = Color.valueOf("102544")
        private val SelectedBackground = Color.valueOf("183e70")
        private val Positive = Color.valueOf("66dfbd")
        private val Negative = Color.valueOf("ff7c8b")
    }
}
