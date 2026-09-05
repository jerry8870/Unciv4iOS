package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.MissingModsException
import com.unciv.logic.MissingNationException
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.PlatformSaverLoader
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.cloud.CloudSaveState
import com.unciv.logic.files.cloud.CloudSaveStatus
import com.unciv.logic.files.cloud.CloudSyncTrigger
import com.unciv.logic.github.GithubAPI
import com.unciv.logic.github.GithubAPI.downloadAndExtract
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.UncivDateFormat.formatDate
import com.unciv.ui.components.extensions.allChildren
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.ONLINE_MOD_MANAGEMENT_UNAVAILABLE
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CoroutineScope
import java.util.Date

private const val loadGameTitleFontSize = 28
private const val loadGameSecondaryFontSize = 18
private const val loadGameContentFontSize = 22
private const val loadGameCompactActionFontSize = 20

class LoadGameScreen : LoadOrSaveScreen(saveButtonFontSize = loadGameContentFontSize) {
    private val copySavedGameToClipboardButton = getCopyExistingSaveToClipboardButton()
    private val importSaveButton = importSave.toTextButton()
    private val cloudSaveSync = game.files.cloudSaveSync
    private val cloudStatusLabel = "".toLabel()
    private var lastCloudChangeRevision = cloudSaveSync.status.localChangeRevision
    private val cloudStatusListener: (CloudSaveStatus) -> Unit = { status ->
        Concurrency.runOnGLThread {
            cloudStatusLabel.setText(cloudStatusText(status))
            if (status.localChangeRevision != lastCloudChangeRevision) {
                lastCloudChangeRevision = status.localChangeRevision
                resetWindowState()
            }
        }
    }
    
    /** Inheriting here again exposes [getLoadExceptionMessage] and [loadMissingMods] to
     *  other clients (WorldScreen, QuickSave, Multiplayer) without needing to rewrite many imports 
     */
    companion object : Helpers {
        private const val loadGame = "Load game"
        private const val loadFromCustomLocation = "Load from custom location"
        private const val loadFromClipboard = "Load copied data"
        private const val loadFromClipboardMenu = "Load from clipboard"
        private const val importFromFile = "Import from file"
        private const val importSave = "Import save"
        private const val loadThisSave = "Load this save"
        private const val confirmSyncSaves = "Sync local and iCloud saves now?\nA changed save may replace the same-named version\non the other side.\nConflicting versions are kept separately."
        private const val confirmRestoreSaves = "Restore saves from iCloud now?\nSaves deleted only on this device return\nif they still exist in iCloud.\nOther changes still sync both ways;\na same-named version may be replaced.\nConflicts are kept separately."
        private const val copyExistingSaveToClipboard = "Copy saved game to clipboard"
        internal const val downloadMissingMods = "Download missing mods"
    }

    init {
        errorLabel.isVisible = false
        errorLabel.wrap = true

        setDefaultCloseAction()
        initLandscapeLayout()
        rightSideButton.onActivation { onLoadGame(selectedSave) }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.setText(loadThisSave.tr())
        rightSideButton.isVisible = true
        if (cloudSaveSync.isSupported) {
            cloudSaveSync.addStatusListener(cloudStatusListener)
            cloudSaveSync.requestSync(CloudSyncTrigger.LoadScreen)
        }
    }

    override fun resetWindowState() {
        super.resetWindowState()
        copySavedGameToClipboardButton.disable()
        rightSideButton.setText(loadThisSave.tr())
        rightSideButton.disable()
    }

    override fun onExistingSaveSelected(saveGameFile: FileHandle) {
        copySavedGameToClipboardButton.enable()
        rightSideButton.setText(loadThisSave.tr())
        rightSideButton.enable()
    }

    override fun doubleClickAction(saveGameFile: FileHandle) {
        onLoadGame(saveGameFile)
    }

    private fun initLandscapeLayout() {
        topTable.clear()
        bottomTable.clear()
        rightSideButton.remove()
        pickerPane.remove()

        closeButton.label.setFontSize(loadGameContentFontSize)
        importSaveButton.label.setFontSize(loadGameContentFontSize)
        cloudStatusLabel.setFontSize(loadGameContentFontSize)
        showAutosavesCheckbox.label.setFontSize(loadGameContentFontSize)
        descriptionLabel.setFontSize(loadGameContentFontSize)
        errorLabel.setFontSize(loadGameContentFontSize)
        rightSideButton.label.setFontSize(loadGameContentFontSize)
        copySavedGameToClipboardButton.label.setFontSize(loadGameCompactActionFontSize)
        deleteSaveButton.label.setFontSize(loadGameCompactActionFontSize)
        listOf(
            closeButton,
            importSaveButton,
            rightSideButton,
            copySavedGameToClipboardButton,
            deleteSaveButton,
        ).forEach { it.labelCell.minWidth(0f) }
        showAutosavesCheckbox.labelCell.minWidth(0f)
        val copyActionPrefWidth = copySavedGameToClipboardButton.prefWidth
        copySavedGameToClipboardButton.label.setWrap(true)
        copySavedGameToClipboardButton.label.setAlignment(Align.center)

        val screenTable = Table().apply {
            setFillParent(true)
            background = skinStrings.getUiBackground(
                "LoadGameScreen/Background",
                tintColor = skinStrings.skinConfig.clearColor,
            )
        }

        importSaveButton.onActivation { ImportSaveMenu(importSaveButton) }
        val ctrlV = KeyCharAndCode.ctrl('v')
        importSaveButton.keyShortcuts.add(ctrlV) { loadFromClipboard() }
        importSaveButton.addTooltip(ctrlV)

        val heading = Table().apply {
            add(loadGame.toLabel(fontSize = loadGameTitleFontSize)).growX().minWidth(0f).left().row()
            add("Select a save to continue".toLabel(Color.LIGHT_GRAY, fontSize = loadGameSecondaryFontSize))
                .growX().minWidth(0f).left()
        }
        val header = Table().apply {
            background = skinStrings.getUiBackground(
                "LoadGameScreen/Header",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.35f),
            )
            add(closeButton).height(46f).padLeft(8f)
            add(heading).growX().minWidth(0f).left().padLeft(10f)
            add().growX()
            add(importSaveButton).height(46f).padRight(8f)
        }
        screenTable.add(header).growX().height(64f).pad(8f, 10f, 4f, 10f).row()

        if (cloudSaveSync.isSupported) {
            val cloudTable = Table().apply { initCloudSaveControls() }
            screenTable.add(cloudTable).growX().minHeight(64f).pad(0f, 10f, 4f, 10f).row()
        }

        savesScrollPane.setScrollingDisabled(true, false)
        (savesScrollPane.actor as Table).apply {
            top()
            defaults().growX()
        }
        val savesPanel = Table().apply {
            background = skinStrings.getUiBackground(
                "LoadGameScreen/SavesPanel",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.2f),
            )
            add("Current saves".toLabel(fontSize = loadGameContentFontSize))
                .growX().minWidth(0f).left().pad(8f, 12f, 6f, 12f).row()
            add(savesScrollPane).grow().minWidth(0f).minHeight(0f).row()
            add(showAutosavesCheckbox).growX().minWidth(0f).left().pad(6f, 12f, 8f, 12f)
        }

        descriptionScroll.setScrollingDisabled(true, false)
        descriptionScroll.setOverscroll(false, false)
        descriptionLabel.setAlignment(Align.topLeft)
        val secondaryActions = Table().apply {
            add(copySavedGameToClipboardButton).growX().minWidth(0f).height(52f).row()
            add(deleteSaveButton).growX().minWidth(0f).height(44f).padTop(6f)
        }
        val detailsPanel = Table().apply {
            background = skinStrings.getUiBackground(
                "LoadGameScreen/DetailsPanel",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.2f),
            )
            add("Selected save".toLabel(Color.LIGHT_GRAY, fontSize = loadGameSecondaryFontSize))
                .growX().minWidth(0f).left().pad(10f, 12f, 0f, 12f).row()
            add(descriptionScroll).grow().minWidth(0f).minHeight(0f).pad(0f, 4f, 0f, 4f).row()
            add(errorLabel).growX().minWidth(0f).center().pad(0f, 8f, 4f, 8f).row()
            add(rightSideButton).growX().minWidth(0f).height(48f).pad(0f, 10f, 6f, 10f).row()
            add(secondaryActions).growX().minWidth(0f).pad(0f, 10f, 10f, 10f)
        }

        val bodyContentWidth = stage.width - 30f // outer padding plus the gap between panels
        val detailsWidth = maxOf(
            stage.width * 0.40f,
            copyActionPrefWidth + 20f,
            deleteSaveButton.prefWidth + 20f,
            rightSideButton.prefWidth + 20f,
        ).coerceAtMost(bodyContentWidth * 0.48f)
        val savesWidth = bodyContentWidth - detailsWidth
        val body = Table().apply {
            add(savesPanel).grow().width(savesWidth).minWidth(0f)
            add(detailsPanel).grow().width(detailsWidth).minWidth(0f).padLeft(10f)
        }
        screenTable.add(body).grow().minWidth(0f).minHeight(0f).pad(0f, 10f, 10f, 10f)
        stage.addActor(screenTable)
    }

    private fun Table.initCloudSaveControls() {
        background = skinStrings.getUiBackground(
            "LoadGameScreen/CloudPanel",
            skinStrings.roundedEdgeRectangleSmallShape,
            skinStrings.skinConfig.baseColor.darken(0.15f),
        )
        cloudStatusLabel.wrap = true
        cloudStatusLabel.setText(cloudStatusText(cloudSaveSync.status))
        add(cloudStatusLabel).growX().minWidth(0f).left().pad(4f, 12f, 4f, 12f)

        val syncNowButton = "Sync saves now".toTextButton()
        syncNowButton.label.setFontSize(loadGameContentFontSize)
        syncNowButton.labelCell.minWidth(0f)
        syncNowButton.onActivation {
            openCloudSyncConfirmation(confirmSyncSaves, "Sync saves now", CloudSyncTrigger.Manual)
        }
        add(syncNowButton).height(46f).padRight(6f)

        val restoreButton = "Restore saves from iCloud".toTextButton()
        restoreButton.label.setFontSize(loadGameContentFontSize)
        restoreButton.labelCell.minWidth(0f)
        restoreButton.onActivation {
            openCloudSyncConfirmation(confirmRestoreSaves, "Restore saves from iCloud", CloudSyncTrigger.Restore)
        }
        add(restoreButton).height(46f).padRight(8f)
    }

    private fun openCloudSyncConfirmation(question: String, confirmText: String, trigger: CloudSyncTrigger) {
        ConfirmPopup(this, question, confirmText) {
            cloudSaveSync.requestSync(trigger)
        }.apply {
            allChildren().filterIsInstance<Label>().forEach { it.setFontSize(loadGameContentFontSize) }
        }.open()
    }

    private inner class ImportSaveMenu(anchor: TextButton) : AnimatedMenuPopup(stage, anchor, Align.bottomRight) {
        override fun createContentTable() = super.createContentTable()!!.apply {
            add(getButton(loadFromClipboardMenu, KeyboardBinding.None) {
                Concurrency.runOnGLThread { loadFromClipboard() }
            }.apply { label.setFontSize(loadGameCompactActionFontSize) }).row()
            add(getButton(importFromFile, KeyboardBinding.None) {
                Concurrency.runOnGLThread { loadFromCustomLocation() }
            }.apply { label.setFontSize(loadGameCompactActionFontSize) }).row()
        }
    }

    private fun cloudStatusText(status: CloudSaveStatus): String {
        val stateText = when (status.state) {
            CloudSaveState.Unsupported -> ""
            CloudSaveState.Checking -> "Checking iCloud availability..."
            CloudSaveState.Available -> "iCloud is available."
            CloudSaveState.NoAccount -> "Sign in to iCloud to sync saves."
            CloudSaveState.Restricted -> "iCloud access is restricted."
            CloudSaveState.AccountChanged -> "iCloud account changed. Tap Sync saves now to use the current account."
            CloudSaveState.WaitingForNetwork -> "Waiting to retry iCloud save sync."
            CloudSaveState.Syncing -> "Syncing saves with iCloud..."
            CloudSaveState.Failed -> "iCloud save sync failed."
            CloudSaveState.Synced -> "Saves are synced with iCloud."
        }.tr()
        if (status.lastSuccessfulSyncAt <= 0L) return stateText
        return "$stateText\n${"Last successful iCloud sync:".tr()} ${Date(status.lastSuccessfulSyncAt).formatDate()}"
    }

    override fun dispose() {
        if (cloudSaveSync.isSupported) cloudSaveSync.removeStatusListener(cloudStatusListener)
        super.dispose()
    }

    private fun onLoadGame(saveGameFile: FileHandle?) {
        if (saveGameFile == null) return
        val loadingPopup = LoadingPopup(this)
        Concurrency.run(loadGame) {
            try {
                // This is what can lead to ANRs - reading the file and setting the transients, that's why this is in another thread
                val loadedGame = game.files.loadGameFromFile(saveGameFile)
                game.loadGame(loadedGame, callFromLoadScreen = true)
            } catch (notAPlayer: UncivShowableException) {
                launchOnGLThread {
                    val (message) = getLoadExceptionMessage(notAPlayer)
                    loadingPopup.reuseWith(message, true)
                    handleLoadGameException(notAPlayer)
                }
            } catch (ex: Exception) {
                launchOnGLThread {
                    loadingPopup.close()
                    handleLoadGameException(ex)
                }
            }
        }
    }

    private fun loadFromClipboard() {
        if (!Gdx.app.clipboard.hasContents()) return
        importSaveButton.setText(Constants.working.tr())
        importSaveButton.disable()
        Concurrency.run(loadFromClipboard) {
            try {
                val clipboardContentsString = Gdx.app.clipboard.contents.trim()
                val loadedGame = UncivFiles.gameInfoFromString(clipboardContentsString)
                game.loadGame(loadedGame, callFromLoadScreen = true)
            } catch (ex: Exception) {
                launchOnGLThread { handleLoadGameException(ex, "Could not load game from clipboard!") }
            } finally {
                launchOnGLThread {
                    importSaveButton.setText(importSave.tr())
                    importSaveButton.enable()
                }
            }
        }
    }

    private fun loadFromCustomLocation() {
        errorLabel.isVisible = false
        importSaveButton.setText(Constants.loading.tr())
        importSaveButton.disable()
        fun revertButton() {
            importSaveButton.setText(importSave.tr())
            importSaveButton.enable()
        }
        Concurrency.run(loadFromCustomLocation) {
            game.files.loadGameFromCustomLocation(
                onLoaded = { loadedGame ->
                    Concurrency.run {
                        try {
                            game.loadGame(loadedGame, callFromLoadScreen = true)
                        } catch (ex: Exception) {
                            launchOnGLThread { handleLoadGameException(ex, "Could not load game from custom location!") }
                        } finally {
                            launchOnGLThread { revertButton() }
                        }
                    }
                },
                onError = { ex ->
                    if (ex !is PlatformSaverLoader.Cancelled)
                        handleLoadGameException(ex, "Could not load game from custom location!")
                    revertButton()
                }
            )
        }
    }

    private fun getCopyExistingSaveToClipboardButton(): TextButton {
        val copyButton = copyExistingSaveToClipboard.toTextButton()
        copyButton.onActivation {
            val file = selectedSave ?: return@onActivation
            Concurrency.run(copyExistingSaveToClipboard) {
                copySaveToClipboard(file)
            }
        }
        copyButton.disable()
        val ctrlC = KeyCharAndCode.ctrl('c')
        copyButton.keyShortcuts.add(ctrlC)
        copyButton.addTooltip(ctrlC)
        return copyButton
    }

    private fun CoroutineScope.copySaveToClipboard(file: FileHandle) {
        val gameText = try {
            file.readString()
        } catch (ex: Throwable) {
            val (errorText, isUserFixable) = getLoadExceptionMessage(ex, saveToClipboardErrorMessage)
            if (!isUserFixable)
                Log.error(saveToClipboardErrorMessage, ex)
            launchOnGLThread {
                ToastPopup(errorText, this@LoadGameScreen)
            }
            return
        }
        try {
            Gdx.app.clipboard.contents = if (gameText[0] == '{') FileConversions.zip(gameText) else gameText
            launchOnGLThread {
                ToastPopup("'[${file.name()}]' copied to clipboard!", this@LoadGameScreen)
            }
        } catch (ex: Throwable) {
            Log.error(saveToClipboardErrorMessage, ex)
            launchOnGLThread {
                ToastPopup(saveToClipboardErrorMessage, this@LoadGameScreen)
            }
        }
    }

    private fun handleLoadGameException(ex: Exception, primaryText: String = "Could not load game!") {
        val isUserFixable = handleException(ex, primaryText)
        if (!isUserFixable) {
            val cantLoadGamePopup = Popup(this@LoadGameScreen)
            cantLoadGamePopup.addGoodSizedLabel("It looks like your saved game can't be loaded!").row()
            cantLoadGamePopup.addGoodSizedLabel("If you could copy your game data (\"Copy saved game to clipboard\" - ").row()
            cantLoadGamePopup.addGoodSizedLabel("  paste it into a new GitHub issue)").row()
            cantLoadGamePopup.addGoodSizedLabel("I could maybe help you figure out what went wrong, since this isn't supposed to happen!").row()
            cantLoadGamePopup.addCloseButton()
            cantLoadGamePopup.open()
        }

        if ((ex is MissingModsException || ex is MissingNationException)
                && !game.platformCapabilities.onlineModManagement) {
            ToastPopup(ONLINE_MOD_MANAGEMENT_UNAVAILABLE, this)
            return
        }

        if (ex is MissingModsException) {
            loadMissingModsAsync(ex.missingMods)
        }
        if (ex is MissingNationException){
            redownloadUnupdatedMods(ex.modNames)
        }
    }

    /** If any nation is missing from a saved game, chances are that one of the mods needs to be redownloaded
     * Since we don't know which one, we check all mods that
     * A. Have at least one nation
     * B. Are outdated
     * */
    private fun redownloadUnupdatedMods(modNames: LinkedHashSet<String>) {
        descriptionLabel.setText("Downloading unupdated mods...")
        Concurrency.runOnNonDaemonThreadPool("redownloadUnupdatedMods") { 
            val modsToCheck = modNames.mapNotNull { RulesetCache[it] }
                .filter { it.nations.any() && it.modOptions.modUrl.isNotEmpty()  }
            
            val reposToUpdate = modsToCheck
                .mapNotNull { 
                    val repo = GithubAPI.Repo.parseUrl(it.modOptions.modUrl) ?: return@mapNotNull null
                    if (it.modOptions.lastUpdated == repo.pushed_at) return@mapNotNull null
                    repo
                }
            
            Concurrency.runOnGLThread {
                ToastPopup("Updating mods: $reposToUpdate", this@LoadGameScreen)
                descriptionLabel.setText("Downloading unupdated mods - 0/${reposToUpdate.size}")
            }

            for ((index, repo) in reposToUpdate.withIndex()) {
                repo.downloadAndExtract()
                Concurrency.runOnGLThread {
                    ToastPopup("Downloaded ${repo.name}", this@LoadGameScreen)
                    descriptionLabel.setText("Downloading unupdated mods - ${index+1}/${reposToUpdate.size}")
                }
            }
        }
    }

    private fun loadMissingModsAsync(missingMods: Iterable<String>) {
        descriptionLabel.setText(Constants.loading.tr())
        Concurrency.runOnNonDaemonThreadPool(downloadMissingMods) {
            try {
                loadMissingMods(missingMods,
                    onModDownloaded = {
                        val labelText = descriptionLabel.text // A Gdx CharArray that has StringBuilder-like methods
                        labelText.appendLine()
                        labelText.append("[$it] Downloaded!".tr())
                        launchOnGLThread { descriptionLabel.setText(labelText) }
                    },
                    onCompleted = {
                        launchOnGLThread {
                            RulesetCache.loadRulesets()
                            errorLabel.isVisible = false
                            rightSideTable.pack()
                            ToastPopup("Missing mods are downloaded successfully.", this@LoadGameScreen)
                        }
                    }
                )
            } catch (ex: Exception) {
                launchOnGLThread {
                    handleLoadGameException(ex, "Could not load the missing mods!")
                }
            } finally {
                launchOnGLThread {
                    descriptionLabel.setText("")
                }
            }
        }
    }
}
