package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.event.EventBus
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.MultiplayerGameUpdateEnded
import com.unciv.logic.multiplayer.MultiplayerGameUpdateStarted
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.formatShort
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

internal const val multiplayerTitleFontSize = 28
internal const val multiplayerEmphasisFontSize = 24
internal const val multiplayerContentFontSize = 22
internal const val multiplayerSecondaryFontSize = 18
internal const val multiplayerDenseFontSize = 16

class MultiplayerScreen : PickerScreen(disableScroll = true) {
    private var selectedGame: MultiplayerGamePreview? = null
    private val events = EventBus.EventReceiver()
    private val refreshingGames = mutableSetOf<String>()
    private var refreshBatchInProgress = false
    private var lastRefreshHadFailures = false
    private var hasCompletedRefresh = false
    private var isDisposed = false
    private var isDownloadingMods = false
    private var lastRefreshCompleted = game.onlineMultiplayer.games
        .maxOfOrNull { it.getLastUpdate() }

    private val copyGameIdButton = createCopyGameIdButton()
    private val resignButton = createResignButton()
    private val forceResignButton = createForceResignButton()
    private val skipTurnButton = createSkipTurnButton()
    private val deleteButton = createDeleteButton()
    private val renameButton = createRenameButton()

    private val gameSpecificButtons = listOf(copyGameIdButton, resignButton, deleteButton, renameButton)

    private val addGameButton = createAddGameButton()
    private val copyUserIdButton = createCopyUserIdButton()
    private val friendsListButton = createFriendsListButton()
    private val refreshButton = createRefreshButton()
    private val helpButton = createHelpButton()

    private val gameCountLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize)
    private val serverStatusDot = "●".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize)
    private val serverUrlLabel = "".toLabel(fontSize = multiplayerSecondaryFontSize).apply { setEllipsis(true) }
    private val serverStateLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }
    private val lastRefreshLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerDenseFontSize).apply {
        setEllipsis(true)
    }

    private val detailTitleLabel = "".toLabel(fontSize = multiplayerEmphasisFontSize).apply { setEllipsis(true) }
    private val detailRulesetLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }
    private val detailStatusLabel = "".toLabel(fontSize = multiplayerSecondaryFontSize, alignment = Align.center).apply {
        setEllipsis(true)
    }
    private val detailStatusBadge = Table().apply {
        add(detailStatusLabel).minWidth(0f).pad(5f, 9f, 5f, 9f)
    }
    private val currentTurnValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val turnDurationValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val ownCivilizationValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val timeRemainingValue = "".toLabel(fontSize = multiplayerContentFontSize).apply { setEllipsis(true) }
    private val detailNoteLabel = "".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize).apply {
        setEllipsis(true)
    }
    private val advancedActionsTable = Table()
    private var detailsActionWidth = 0f

    val gameList = GameList(::selectGame)

    init {
        setDefaultCloseAction()
        initLandscapeLayout()
        setupRightSideButton()
        setupRefreshEvents()
        unselectGame()

        if (game.settings.multiplayer.securePasswordMigrationFailed) {
            Popup(this).apply {
                addGoodSizedLabel(
                    "Some saved multiplayer passwords could not be moved to secure storage. " +
                        "They were removed from settings; authenticate again when prompted."
                ).row()
                addCloseButton {
                    game.settings.multiplayer.dismissSecurePasswordMigrationWarning()
                    game.settings.save()
                }
                open()
            }
        }

        game.onlineMultiplayer.games
            .sortedWith(compareByDescending<MultiplayerGamePreview> { it.preview?.let { preview ->
                buildMultiplayerGameUiModel(
                    it.name,
                    preview,
                    it.error != null,
                    it.getLastUpdate(),
                    game.settings.multiplayer.getUserId(),
                ).isUsersTurn
            } == true }.thenBy { it.name })
            .firstOrNull()
            ?.let { selectGame(it.name) }

        refreshAllGames()
    }

    private fun onGameDeleted(gameName: String) {
        if (selectedGame?.name == gameName) unselectGame()
        gameList.update()
        updateGameCount()
    }

    private fun setupRightSideButton() {
        rightSideButton.style = skin.get("positive", TextButton.TextButtonStyle::class.java)
        rightSideButton.setText("Download latest save and enter".tr())
        rightSideButton.onClick {
            if (isDownloadingMods) return@onClick
            val targetGame = selectedGame ?: return@onClick
            val targetPreview = targetGame.preview ?: return@onClick
            val missingMods = targetPreview.gameParameters.getModsAndBaseRuleset()
                .filter { !RulesetCache.containsKey(it) }
            if (missingMods.isEmpty()) {
                return@onClick MultiplayerHelpers.loadMultiplayerGame(this, targetGame)
            }
            if (!game.platformCapabilities.onlineModManagement) {
                ToastPopup(
                    "Install the same version of every required ruleset and mod before joining. " +
                        "Automatic mod downloads are unavailable in this build.",
                    this,
                    6000L,
                )
                return@onClick
            }

            isDownloadingMods = true
            selectGame(targetGame.name)

            // Download missing mods
            Concurrency.runOnNonDaemonThreadPool(LoadGameScreen.downloadMissingMods) {
                try {
                    LoadGameScreen.loadMissingMods(missingMods, onModDownloaded = {
                        Concurrency.runOnGLThread {
                            if (!isDisposed && game.screen === this@MultiplayerScreen) {
                                ToastPopup("[$it] Downloaded!", this@MultiplayerScreen)
                            }
                        }
                    },
                    onCompleted = {
                        RulesetCache.loadRulesets()
                        Concurrency.runOnGLThread {
                            if (isDisposed || game.screen !== this@MultiplayerScreen) {
                                return@runOnGLThread
                            }
                            MultiplayerHelpers.loadMultiplayerGame(this@MultiplayerScreen, targetGame)
                        }
                    })
                } catch (ex: Exception) {
                    val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                    launchOnGLThread {
                        if (!isDisposed && game.screen === this@MultiplayerScreen) {
                            ToastPopup(message, this@MultiplayerScreen)
                        }
                    }
                } finally {
                    launchOnGLThread {
                        isDownloadingMods = false
                        if (!isDisposed) refreshSelectedGame()
                    }
                }
            }
        }
    }

    private fun createRefreshButton(): TextButton {
        val btn = "Refresh all games".toTextButton()
        btn.onClick { refreshAllGames() }
        return btn
    }

    private fun createAddGameButton(): TextButton {
        val positiveButtonStyle = skin.get("positive", TextButton.TextButtonStyle::class.java)
        val btn = "Add by Game ID".toTextButton(positiveButtonStyle)
        btn.onClick {
            AddMultiplayerGamePopup(this).open()
        }
        return btn
    }

    private fun createHelpButton(): TextButton {
        val btn = "How multiplayer works".toTextButton()
        btn.onClick { showHelpPopup() }
        return btn
    }

    private fun initLandscapeLayout() {
        topTable.clear()
        bottomTable.clear()
        pickerPane.remove()

        closeButton.setText("Back".tr())
        closeButton.label.setFontSize(multiplayerSecondaryFontSize)
        listOf(helpButton, copyUserIdButton, addGameButton, refreshButton).forEach {
            it.label.setFontSize(multiplayerSecondaryFontSize)
            it.labelCell.minWidth(0f)
        }
        closeButton.labelCell.minWidth(0f)
        updateServerStatus()

        val screenTable = Table().apply {
            setFillParent(true)
            background = skinStrings.getUiBackground(
                "MultiplayerScreen/Background",
                tintColor = skinStrings.skinConfig.clearColor,
            )
        }
        screenTable.add(createHeader()).growX().minWidth(0f).minHeight(64f).pad(8f, 10f, 4f, 10f).row()
        screenTable.add(createServerBar()).growX().minWidth(0f).minHeight(50f).pad(0f, 10f, 6f, 10f).row()

        val bodyContentWidth = stage.width - 30f // outer padding plus the gap between panels
        val gamesWidth = bodyContentWidth * 0.53f
        val detailsWidth = bodyContentWidth - gamesWidth
        detailsActionWidth = detailsWidth - 20f
        val body = Table().apply {
            add(createGamesPanel()).grow().width(gamesWidth).minWidth(0f)
            add(createDetailsPanel()).grow().width(detailsWidth).minWidth(0f).minHeight(0f).padLeft(10f)
        }
        screenTable.add(body).grow().minWidth(0f).minHeight(0f).pad(0f, 10f, 10f, 10f)
        stage.addActor(screenTable)

        updateGameCount()
    }

    private fun createHeader(): Table {
        val subtitle = "Asynchronous turn-based · Download the latest save when it's your turn to continue playing"
            .toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize)
            .apply { setEllipsis(true) }
        val title = "Multiplayer".toLabel(fontSize = multiplayerTitleFontSize)
        val heading = Table().apply {
            add(title).growX().minWidth(0f).left().row()
            add(subtitle).growX().minWidth(0f).left()
        }
        val actions = listOf(helpButton, copyUserIdButton, addGameButton)
        val actionsWidth = stage.width - 52f - closeButton.prefWidth - title.prefWidth
        val actionGrid = Table()
        val actionGridWidth = actionGrid.addResponsiveActions(actions, actionsWidth, 44f)
        return Table().apply {
            background = skinStrings.getUiBackground(
                "MultiplayerScreen/Header",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.35f),
            )
            add(closeButton).height(46f).padLeft(8f)
            add(heading).growX().minWidth(0f).left().padLeft(10f)
            add(actionGrid).width(actionGridWidth).padRight(8f)
        }
    }

    private fun createServerBar(): Table {
        val currentServerLabel = "Current server:"
            .toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize)
            .apply { setEllipsis(true) }
        val identity = Table().apply {
            add(serverStatusDot).padLeft(12f).padRight(7f)
            add(currentServerLabel).minWidth(0f).left()
            add(serverUrlLabel).growX().minWidth(0f).left().padLeft(5f)
                .maxWidth(this@MultiplayerScreen.stage.width * 0.34f)
        }
        val activity = Table().apply {
            add(serverStateLabel).growX().minWidth(0f).left()
                .maxWidth(this@MultiplayerScreen.stage.width * 0.20f)
            add(lastRefreshLabel).growX().minWidth(0f).left().padLeft(10f)
                .maxWidth(this@MultiplayerScreen.stage.width * 0.18f)
            add(refreshButton).height(42f).padLeft(8f)
        }
        val singleRow = fitsInOneRow(
            stage.width - 40f,
            listOf(identity.prefWidth, activity.prefWidth),
            10f,
        )
        return Table().apply {
            background = skinStrings.getUiBackground(
                "MultiplayerScreen/ServerBar",
                skinStrings.roundedEdgeRectangleSmallShape,
                Color.valueOf("0b2b3a"),
            )
            if (singleRow) {
                add(identity).growX().minWidth(0f)
                add(activity).minWidth(0f).pad(3f, 10f, 3f, 10f)
            } else {
                add(identity).growX().minWidth(0f).padRight(10f).row()
                add(activity).growX().minWidth(0f).pad(0f, 10f, 3f, 12f)
            }
        }
    }

    private fun createGamesPanel(): Table {
        val gameScroll = ScrollPane(gameList).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val footer = "Create new games from Home → New game → Online multiplayer"
            .toLabel(Color.LIGHT_GRAY, fontSize = multiplayerDenseFontSize)
            .apply { setEllipsis(true) }
        return Table().apply {
            background = skinStrings.getUiBackground(
                "MultiplayerScreen/GamesPanel",
                skinStrings.roundedEdgeRectangleSmallShape,
                PanelBackground,
            )
            val header = Table().apply {
                add("My multiplayer games".toLabel(fontSize = multiplayerContentFontSize))
                    .growX().minWidth(0f).left()
                add(gameCountLabel).minWidth(0f).right()
            }
            add(header).growX().minWidth(0f).pad(9f, 12f, 7f, 12f).row()
            add(gameScroll).grow().minWidth(0f).minHeight(0f).pad(0f, 8f, 0f, 8f).row()
            add(footer).growX().minWidth(0f).left().pad(6f, 12f, 8f, 12f)
        }
    }

    private fun createDetailsPanel(): Table {
        val titleCopy = Table().apply {
            add("Current selection".toLabel(Color.LIGHT_GRAY, fontSize = multiplayerSecondaryFontSize))
                .growX().minWidth(0f).left().row()
            add(detailTitleLabel).growX().minWidth(0f).left().padTop(2f).row()
            add(detailRulesetLabel).growX().minWidth(0f).left().padTop(2f)
        }
        val detailHeader = Table().apply {
            add(titleCopy).growX().minWidth(0f).left()
            add(detailStatusBadge).minWidth(0f).right().padLeft(8f)
        }
        val infoGrid = Table().apply {
            add(createInfoItem("Current turn", currentTurnValue)).growX().minWidth(0f).padRight(3f).padBottom(3f)
            add(createInfoItem("This turn has lasted", turnDurationValue)).growX().minWidth(0f).padLeft(3f).padBottom(3f).row()
            add(createInfoItem("My civilization", ownCivilizationValue)).growX().minWidth(0f).padRight(3f).padTop(3f)
            add(createInfoItem("Time remaining", timeRemainingValue)).growX().minWidth(0f).padLeft(3f).padTop(3f)
        }

        copyGameIdButton.label.setFontSize(multiplayerSecondaryFontSize)
        renameButton.label.setFontSize(multiplayerSecondaryFontSize)
        deleteButton.label.setFontSize(multiplayerSecondaryFontSize)
        listOf(copyGameIdButton, renameButton, deleteButton).forEach {
            it.label.setEllipsis(false)
            it.labelCell.minWidth(0f)
        }
        rightSideButton.label.setFontSize(multiplayerContentFontSize)
        rightSideButton.label.setWrap(true)
        rightSideButton.labelCell.minWidth(0f)
        val secondaryActions = Table().apply {
            addResponsiveActions(
                listOf(copyGameIdButton, renameButton, deleteButton),
                detailsActionWidth,
                44f,
            )
        }

        return Table().apply {
            background = skinStrings.getUiBackground(
                "MultiplayerScreen/DetailsPanel",
                skinStrings.roundedEdgeRectangleSmallShape,
                PanelBackground,
            )
            add(detailHeader).growX().minWidth(0f).pad(10f, 12f, 6f, 12f).row()
            add(infoGrid).growX().minWidth(0f).pad(0f, 9f, 4f, 9f).row()
            add(detailNoteLabel).growX().minWidth(0f).left().pad(2f, 12f, 5f, 12f).row()
            add(rightSideButton).growX().minWidth(0f).height(56f).pad(0f, 10f, 5f, 10f).row()
            add(secondaryActions).growX().minWidth(0f).pad(0f, 10f, 8f, 10f).row()
            if (game.platformCapabilities.multiplayerAdvancedActions) {
                add(advancedActionsTable).growX().minWidth(0f).pad(0f, 10f, 8f, 10f)
            }
        }
    }

    private fun createInfoItem(title: String, value: Label): Table = Table().apply {
        background = skinStrings.getUiBackground(
            "MultiplayerScreen/InfoItem",
            skinStrings.roundedEdgeRectangleSmallShape,
            Color.valueOf("0b1d38"),
        )
        val titleLabel = title.toLabel(Color.LIGHT_GRAY, fontSize = multiplayerDenseFontSize).apply {
            setEllipsis(true)
        }
        add(titleLabel).growX().minWidth(0f).left().pad(6f, 8f, 1f, 8f).row()
        add(value).growX().minWidth(0f).left().pad(1f, 8f, 6f, 8f)
    }

    private fun setupRefreshEvents() {
        events.receive(MultiplayerGameUpdateStarted::class) {
            refreshingGames += it.name
            updateServerStatus()
            onGameChanged(it.name)
        }
        events.receive(MultiplayerGameUpdateEnded::class) {
            refreshingGames -= it.name
            if (refreshingGames.isEmpty() && !refreshBatchInProgress) {
                lastRefreshCompleted = Instant.now()
                hasCompletedRefresh = true
                lastRefreshHadFailures = game.onlineMultiplayer.games.any { game -> game.error != null }
            }
            updateServerStatus()
            onGameChanged(it.name)
        }
    }

    private fun refreshAllGames() {
        if (refreshBatchInProgress) return
        refreshBatchInProgress = true
        refreshButton.disable()
        updateServerStatus()
        refreshSelectedGame()
        Concurrency.run("Update all multiplayer games") {
            var requestFailed = false
            try {
                game.onlineMultiplayer.requestUpdate()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                requestFailed = true
                Log.error("Could not refresh multiplayer games", ex)
            } finally {
                launchOnGLThread {
                    if (isDisposed) return@launchOnGLThread
                    refreshBatchInProgress = false
                    refreshingGames.clear()
                    lastRefreshCompleted = Instant.now()
                    hasCompletedRefresh = true
                    lastRefreshHadFailures = requestFailed ||
                        game.onlineMultiplayer.games.any { it.error != null }
                    refreshButton.enable()
                    gameList.update()
                    updateGameCount()
                    refreshSelectedGame()
                    updateServerStatus()
                }
            }
        }
    }

    private fun updateServerStatus() {
        serverUrlLabel.setText(game.onlineMultiplayer.multiplayerServer.getServerUrl())
        val isRefreshing = refreshBatchInProgress || refreshingGames.isNotEmpty()
        val state = when {
            isRefreshing -> "Refreshing..."
            lastRefreshHadFailures -> "Some games failed to refresh"
            hasCompletedRefresh -> "Refresh complete"
            else -> "Connection not checked"
        }
        serverStateLabel.setText(state.tr())
        val stateColor = when {
            isRefreshing -> Color.GOLD
            lastRefreshHadFailures -> Negative
            hasCompletedRefresh -> Positive
            else -> Color.LIGHT_GRAY
        }
        serverStateLabel.setFontColor(stateColor)
        serverStatusDot.setFontColor(stateColor)
        lastRefreshLabel.setText(
            lastRefreshCompleted?.let {
                val elapsed = Duration.between(it, Instant.now())
                val nonNegativeElapsed = if (elapsed.isNegative) Duration.ZERO else elapsed
                "Last refreshed [${nonNegativeElapsed.formatShort()}] ago".tr()
            } ?: "Not refreshed yet".tr()
        )
    }

    private fun updateGameCount() {
        gameCountLabel.setText("[${game.onlineMultiplayer.games.size}] games".tr())
    }

    internal fun onMultiplayerGameAdded() {
        gameList.update()
        updateGameCount()
        if (selectedGame == null) {
            game.onlineMultiplayer.games.firstOrNull()?.let { selectGame(it.name) }
        }
    }

    private fun onGameChanged(name: String) {
        if (selectedGame?.name == name) refreshSelectedGame()
    }

    private fun createResignButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val resignButton = "Resign".toTextButton(negativeButtonStyle).apply { disable() }
        resignButton.onClick {
            val civName = selectedGame!!.preview!!.currentPlayer
            val askPopup = ConfirmPopup(
                    this,
                    "Are you sure you ([$civName]) want to resign?",
                    "Resign",
            ) {
                resignPlayer(selectedGame!!, civName, civName)
            }
            askPopup.open()
        }
        return resignButton
    }
    
    private fun getOurCivNameOrPlayerId(): String {
        val ourId = game.settings.multiplayer.getUserId()
        val ourCiv = selectedGame!!.preview!!.getPlayerCiv(ourId)
        // if we are a non-spectator player, use our civ name, otherwise use player id
        return if (ourCiv != null && ourCiv.civName != Constants.spectator) ourCiv.civName else ourId
    }

    private fun createForceResignButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val resignButton = "Force current player to resign".toTextButton(negativeButtonStyle).apply { isVisible = false }
        resignButton.onClick {
            val currentPlayer = selectedGame!!.preview!!.currentPlayer
            val askPopup = ConfirmPopup(
                this,
                "Are you sure you want to force the current player ([$currentPlayer]) to resign?",
                "Yes",
            ) {
                resignPlayer(selectedGame!!, currentPlayer, getOurCivNameOrPlayerId())
            }
            askPopup.open()
        }
        return resignButton
    }

    private fun createSkipTurnButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val skipTurnButton = "Skip turn of current player".toTextButton(negativeButtonStyle).apply { isVisible = false }
        skipTurnButton.onClick {
            val civName = selectedGame!!.preview!!.currentPlayer
            val askPopup = ConfirmPopup(
                this,
                "Are you sure you want to skip the turn of [$civName]?",
                "Yes",
            ) {
                skipCurrentPlayerTurn(selectedGame!!, civName, getOurCivNameOrPlayerId())
            }
            askPopup.open()
        }
        return skipTurnButton
    }

    /**
     * Permanently turns the current playerCiv into an AI civ and uploads the game afterwards.
     * 
     * @param responsibleCivNameOrPlayerId Who caused the player to resign? Can be the name of a civ, or for example a player id
     */
    private fun resignPlayer(multiplayerGamePreview: MultiplayerGamePreview, playerCiv: String, responsibleCivNameOrPlayerId: String) {
        //Create a popup
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open()

        Concurrency.runOnNonDaemonThreadPool("Resign") {
            try {
                val errorMessage = game.onlineMultiplayer.resignPlayer(
                    multiplayerGamePreview,
                    playerCiv,
                    responsibleCivNameOrPlayerId
                )

                launchOnGLThread {
                    if (errorMessage.isEmpty()) {
                        popup.close()
                    } else {
                        popup.reuseWith(errorMessage, true)
                    }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)

                if (ex is MultiplayerAuthException) {
                    launchOnGLThread {
                        val preview = multiplayerGamePreview.preview ?: return@launchOnGLThread
                        val multiplayerServer = game.onlineMultiplayer
                            .serverFor(preview.gameParameters.multiplayerServerUrl)
                        AuthPopup(
                            this@MultiplayerScreen,
                            multiplayerServer,
                            { success ->
                                if (success) resignPlayer(multiplayerGamePreview, playerCiv, responsibleCivNameOrPlayerId)
                            },
                        ).open(true)
                    }
                    return@runOnNonDaemonThreadPool
                }

                launchOnGLThread {
                    popup.reuseWith(message, true)
                }
            }
        }
    }

    /**
     * Temporarily turns the current playerCiv into an AI civ and uploads the game afterwards.
     *
     * @param responsibleCivNameOrPlayerId Who skipped the player's turn? Can be the name of a civ, or for example a player id
     */
    private fun skipCurrentPlayerTurn(multiplayerGamePreview: MultiplayerGamePreview, playerToSkip: String, responsibleCivNameOrPlayerId: String) {
        //Create a popup
        val popup = Popup(this)
        popup.addGoodSizedLabel(Constants.working).row()
        popup.open()

        Concurrency.runOnNonDaemonThreadPool("Skip turn") {
            try {
                val skipTurnErrorMessage = game.onlineMultiplayer.skipCurrentPlayerTurn(
                    multiplayerGamePreview,
                    playerToSkip,
                    responsibleCivNameOrPlayerId
                )

                launchOnGLThread {
                    if (skipTurnErrorMessage == null) {
                        popup.close()
                    } else {
                        popup.reuseWith(skipTurnErrorMessage, true)
                    }
                    gameList.update()
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)

                if (ex is MultiplayerAuthException) {
                    launchOnGLThread {
                        val preview = multiplayerGamePreview.preview ?: return@launchOnGLThread
                        val multiplayerServer = game.onlineMultiplayer
                            .serverFor(preview.gameParameters.multiplayerServerUrl)
                        AuthPopup(
                            this@MultiplayerScreen,
                            multiplayerServer,
                            { success ->
                                if (success) skipCurrentPlayerTurn(multiplayerGamePreview, playerToSkip, responsibleCivNameOrPlayerId)
                            },
                        ).open(true)
                    }
                    return@runOnNonDaemonThreadPool
                }

                launchOnGLThread {
                    popup.reuseWith(message, true)
                }
            }
        }
    }

    private fun createDeleteButton(): TextButton {
        val negativeButtonStyle = skin.get("negative", TextButton.TextButtonStyle::class.java)
        val deleteButton = "Delete local record".toTextButton(negativeButtonStyle).apply { disable() }
        deleteButton.onClick {
            val askPopup = ConfirmPopup(
                    this,
                    "Are you sure you want to delete this local multiplayer record?",
                    "Delete local record",
            ) {
                try {
                    game.onlineMultiplayer.multiplayerFiles.deleteGame(selectedGame!!)
                    onGameDeleted(selectedGame!!.name)
                } catch (ex: Exception) {
                    Log.error("Could not delete game!", ex)
                    ToastPopup("Could not delete game!", this)
                }
            }
            askPopup.open()
        }
        return deleteButton
    }

    private fun createRenameButton(): TextButton {
        val btn = "Rename".toTextButton().apply { disable() }
        btn.onClick {
            Popup(this).apply {
                val textField = UncivTextField("Game name", selectedGame!!.name)
                // slashes in mp names are interpreted as directory separators, so we don't allow them
                textField.textFieldFilter = UncivFiles.fileNameTextFieldFilter()
                add(textField).width(stageToShowOn.width / 2).row()
                val saveButton = "Save".toTextButton()

                val saveNewNameFunction = {
                    val newName = textField.text.trim()
                    val renamed = game.onlineMultiplayer.multiplayerFiles.changeGameName(selectedGame!!, newName) {
                        if (it != null) reuseWith("Could not save game!", true)
                    }
                    if (renamed) {
                        gameList.update()
                        selectGame(newName)
                        close()
                    }
                }

                saveButton.onActivation(saveNewNameFunction)
                saveButton.keyShortcuts.add(KeyCharAndCode.RETURN)
                textField.cursorPosition = textField.text.length
                this@MultiplayerScreen.stage.keyboardFocus = textField
                add(saveButton)
                open()
            }
        }
        return btn
    }

    private fun createCopyGameIdButton(): TextButton {
        val btn = "Copy game ID".toTextButton().apply { disable() }
        btn.onClick {
            val gameInfo = selectedGame?.preview
            if (gameInfo != null) {
                Gdx.app.clipboard.contents = gameInfo.gameId
                ToastPopup("Game ID copied to clipboard!", this)
            }
        }
        return btn
    }

    private fun createFriendsListButton(): TextButton {
        val btn = "Friends list".toTextButton()
        btn.onClick {
            game.pushScreen{ ViewFriendsListScreen() }
        }
        return btn
    }

    private fun createCopyUserIdButton(): TextButton {
        val btn = "Copy my Player ID".toTextButton()
        btn.onClick {
            Gdx.app.clipboard.contents = game.settings.multiplayer.getUserId()
            ToastPopup("UserID copied to clipboard", this)
        }
        return btn
    }

    private fun showHelpPopup() {
        val helpPopup = Popup(this)
        helpPopup.addGoodSizedLabel(
            "Multiplayer in three steps",
            size = multiplayerTitleFontSize,
        ).row()
        helpPopup.addGoodSizedLabel(
            "There is no waiting lobby; the game starts as soon as the creator finishes setup.",
            size = multiplayerSecondaryFontSize,
            color = Color.LIGHT_GRAY,
        ).row()
        helpPopup.addGoodSizedLabel(
            "1. Each player copies their Player ID and sends it to the game creator.",
        ).padTop(8f).row()
        helpPopup.addGoodSizedLabel(
            "2. The creator opens New game, enables online multiplayer, and assigns each human civilization its Player ID.",
        ).row()
        helpPopup.addGoodSizedLabel(
            "3. The creator shares the Game ID; other players add it here and download the latest save.",
        ).row()
        helpPopup.addCloseButton("Got it")
        helpPopup.open()
    }

    private fun updateAdvancedActions() {
        if (!game.platformCapabilities.multiplayerAdvancedActions) return
        advancedActionsTable.clearChildren()
        friendsListButton.label.setFontSize(multiplayerDenseFontSize)
        resignButton.label.setFontSize(multiplayerDenseFontSize)
        skipTurnButton.label.setFontSize(multiplayerDenseFontSize)
        forceResignButton.label.setFontSize(multiplayerDenseFontSize)
        listOf(friendsListButton, resignButton, skipTurnButton, forceResignButton).forEach {
            it.label.setEllipsis(false)
            it.labelCell.minWidth(0f)
        }

        val actions = mutableListOf(friendsListButton)
        if (selectedGame != null) {
            actions += resignButton
        }
        if (skipTurnButton.isVisible) actions += skipTurnButton
        if (forceResignButton.isVisible) actions += forceResignButton
        advancedActionsTable.addResponsiveActions(actions, detailsActionWidth, 40f)
    }

    private fun unselectGame() {
        selectedGame = null
        gameList.select(null)
        rightSideButton.disable()
        for (button in gameSpecificButtons)
            button.disable()
        skipTurnButton.isVisible = false
        forceResignButton.isVisible = false
        updateAdvancedActions()

        detailTitleLabel.setText("No game selected".tr())
        detailRulesetLabel.setText("Select a game from the list to see its details.".tr())
        detailStatusBadge.isVisible = false
        currentTurnValue.setText("—")
        turnDurationValue.setText("—")
        ownCivilizationValue.setText("—")
        timeRemainingValue.setText("—")
        detailNoteLabel.setText("")
        rightSideButton.setText("Download latest save and enter".tr())
    }

    private fun selectGame(name: String) {
        val multiplayerGame = game.onlineMultiplayer.multiplayerFiles.getGameByName(name)
        if (multiplayerGame == null) {
            // Should never happen
            unselectGame()
            return
        }

        selectedGame = multiplayerGame
        gameList.select(name)

        val preview = multiplayerGame.preview
        val actionsBlocked = refreshBatchInProgress ||
            name in refreshingGames || isDownloadingMods
        copyGameIdButton.isEnabled = preview != null
        renameButton.isEnabled = preview != null && !actionsBlocked
        deleteButton.isEnabled = !actionsBlocked
        rightSideButton.isEnabled = preview != null && !actionsBlocked

        if (!game.platformCapabilities.multiplayerAdvancedActions) {
            resignButton.disable()
            skipTurnButton.isVisible = false
            forceResignButton.isVisible = false
        } else {
            val currentPlayerCiv = preview?.civilizations
                ?.firstOrNull { it.civID == preview.currentPlayer }
                ?: preview?.civilizations?.firstOrNull { it.civName == preview.currentPlayer }
            // is it our turn?
            val isOurTurn = currentPlayerCiv?.playerId == game.settings.multiplayer.getUserId()
            resignButton.isEnabled = !actionsBlocked && isOurTurn

            if (actionsBlocked || isOurTurn || preview == null || currentPlayerCiv == null) {
                skipTurnButton.isVisible = false
                forceResignButton.isVisible = false
            } else {
                val durationInactive = Duration.between(Instant.ofEpochMilli(preview.currentTurnStartTime), Instant.now())
                val playerDurationBeforeForceResign = Duration.ofMinutes(currentPlayerCiv.playerMinutesBeforeForceResign.toLong())
                val weAreAPlayer = game.settings.multiplayer.getUserId() in preview.civilizations.map { it.playerId }
                skipTurnButton.isVisible = weAreAPlayer && durationInactive > Duration.ofMinutes(preview.gameParameters.minutesUntilSkipTurn.toLong())
                forceResignButton.isVisible = weAreAPlayer && (durationInactive > playerDurationBeforeForceResign)
            }
        }

        updateAdvancedActions()
        updateSelectedGameDetails()
    }

    private fun refreshSelectedGame() {
        val name = selectedGame?.name ?: return
        val refreshedGame = game.onlineMultiplayer.multiplayerFiles.getGameByName(name)
        if (refreshedGame == null) {
            unselectGame()
            return
        }
        selectedGame = refreshedGame
        selectGame(name)
    }

    private fun updateSelectedGameDetails() {
        val multiplayerGame = selectedGame ?: return
        val model = buildMultiplayerGameUiModel(
            multiplayerGame.name,
            multiplayerGame.preview,
            multiplayerGame.error != null,
            multiplayerGame.getLastUpdate(),
            game.settings.multiplayer.getUserId(),
            multiplayerGame.name in refreshingGames,
        )

        detailTitleLabel.setText(model.name)
        val ruleset = model.baseRuleset?.let { "{$it}".tr() } ?: "Unknown".tr()
        val mods = if (model.mods.isEmpty()) "No mods".tr()
            else "${"Mods:".tr()} ${model.mods.joinToString { "{$it}".tr() }}"
        detailRulesetLabel.setText("$ruleset  ·  $mods")

        detailStatusBadge.isVisible = true
        val (statusText, statusColor) = when (model.status) {
            MultiplayerGameUiStatus.Refreshing -> "Refreshing..." to Color.GOLD
            MultiplayerGameUiStatus.YourTurn -> "Your turn" to Positive
            MultiplayerGameUiStatus.WaitingForOpponent -> "Waiting for opponent" to Color.LIGHT_GRAY
            MultiplayerGameUiStatus.RefreshFailed -> "Refresh failed" to Negative
            MultiplayerGameUiStatus.Unavailable -> "Unavailable" to Color.GRAY
        }
        detailStatusLabel.setText(statusText.tr())
        detailStatusLabel.setFontColor(statusColor)
        detailStatusBadge.background = skinStrings.getUiBackground(
            "MultiplayerScreen/StatusBadge",
            skinStrings.roundedEdgeRectangleSmallShape,
            statusColor.darken(0.68f),
        )

        val currentPlayer = model.currentPlayer?.let { "{$it}".tr() } ?: "Unknown".tr()
        currentTurnValue.setText(
            model.turn?.let { "$currentPlayer  ·  ${"Turn [$it]".tr()}" } ?: currentPlayer
        )
        turnDurationValue.setText(model.currentTurnDuration?.formatShort() ?: "Unknown".tr())
        val ourCivilization = model.ownCivilization?.let { "{$it}".tr() } ?: "Unknown".tr()
        val difficulty = model.difficulty?.tr() ?: "Unknown".tr()
        ownCivilizationValue.setText("$ourCivilization  ·  $difficulty")
        timeRemainingValue.setText(
            model.turnTimeRemaining?.let { "Remaining [${it.formatShort()}]".tr() }
                ?: "Unknown".tr()
        )
        detailNoteLabel.setText(
            when {
                model.status == MultiplayerGameUiStatus.Unavailable ->
                    "Game details are unavailable.".tr()
                multiplayerGame.error != null ->
                    "Refresh failed. Showing the latest saved details.".tr()
                else -> "Last updated [${model.updatedAgo.formatShort()}] ago".tr()
            }
        )
        rightSideButton.setText(
            (if (model.isUsersTurn) "Download latest save and enter" else "View latest game state").tr()
        )
    }

    override fun dispose() {
        isDisposed = true
        gameList.dispose()
        events.stopReceiving()
        super.dispose()
    }

    companion object {
        private val PanelBackground = Color.valueOf("0d2140")
        private val Positive = Color.valueOf("66dfbd")
        private val Negative = Color.valueOf("ff7c8b")
    }
}

fun fitsInOneRow(
    availableWidth: Float,
    itemPrefWidths: List<Float>,
    horizontalGap: Float = 0f,
): Boolean = itemPrefWidths.sum() + horizontalGap * (itemPrefWidths.size - 1).coerceAtLeast(0) <= availableWidth

fun responsiveActionColumns(
    availableWidth: Float,
    itemPrefWidths: List<Float>,
    horizontalGap: Float = 6f,
): Int {
    if (itemPrefWidths.isEmpty()) return 0
    for (columns in itemPrefWidths.size downTo 1) {
        val everyRowFits = itemPrefWidths.chunked(columns).all { rowWidths ->
            fitsInOneRow(availableWidth, rowWidths, horizontalGap)
        }
        if (everyRowFits) return columns
    }
    return 1
}

private fun Table.addResponsiveActions(
    buttons: List<TextButton>,
    availableWidth: Float,
    buttonHeight: Float,
    horizontalGap: Float = 6f,
): Float {
    if (buttons.isEmpty()) return 0f
    val requiredWidths = buttons.map { maxOf(it.prefWidth, it.label.prefWidth + 30f) }
    val columns = responsiveActionColumns(availableWidth, requiredWidths, horizontalGap)
    for ((rowIndex, rowButtons) in buttons.chunked(columns).withIndex()) {
        if (rowButtons.size == 1 && columns > 1) {
            add(rowButtons.single()).growX().minWidth(0f).height(buttonHeight).colspan(columns)
                .padTop(if (rowIndex == 0) 0f else 4f).row()
            continue
        }
        rowButtons.forEachIndexed { index, button ->
            val cell = add(button).growX().minWidth(0f).height(buttonHeight)
                .padTop(if (rowIndex == 0) 0f else 4f)
            if (index < rowButtons.lastIndex) cell.padRight(horizontalGap)
        }
        row()
    }
    return requiredWidths.chunked(columns).maxOf { rowWidths ->
        rowWidths.sum() + horizontalGap * (rowWidths.size - 1)
    }
}
