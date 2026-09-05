package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.PlatformSaverLoader
import com.unciv.logic.files.UncivFiles
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread


private const val saveGameTitleFontSize = 28
private const val saveGameSecondaryFontSize = 18
private const val saveGameContentFontSize = 22
private const val saveGameCompactActionFontSize = 20

class SaveGameScreen(private val gameInfo: GameInfo) :
    LoadOrSaveScreen(saveButtonFontSize = saveGameContentFontSize) {
    private val gameNameTextField = UncivTextField(nameFieldLabelText)
    private val copyJsonButton = "Copy to clipboard".toTextButton()
    private val saveToCustomLocationButton = saveToCustomText.toTextButton()

    companion object : Helpers {
        const val nameFieldLabelText = "Saved game name"
        const val saveButtonText = "Save game"
        const val savingText = "Saving..."
        const val saveToCustomText = "Save to custom location"
    }

    init {
        errorLabel.isVisible = false
        errorLabel.wrap = true

        setDefaultCloseAction()

        initGameNameField()

        copyJsonButton.onActivation(::copyToClipboardHandler)
        val ctrlC = KeyCharAndCode.ctrl('c')
        copyJsonButton.keyShortcuts.add(ctrlC)
        copyJsonButton.addTooltip(ctrlC)

        saveToCustomLocationButton.onClick(::saveToCustomLocation)

        rightSideButton.setText(saveButtonText.tr())
        rightSideButton.onActivation {
            val saveGameFile = game.files.getSave(gameNameTextField.text)
            if (saveGameFile.exists())
                doubleClickAction(saveGameFile)
            else saveGame(saveGameFile)
        }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.enable()

        initLandscapeLayout()
    }

    private fun initGameNameField() {
        gameNameTextField.textFieldFilter = UncivFiles.fileNameTextFieldFilter()
        gameNameTextField.setTextFieldListener { textField, _ -> enableSaveButton(textField.text) }
        val defaultSaveName = "[${gameInfo.currentPlayer}] - [${gameInfo.turns}] turns".tr(hideIcons = true)
        gameNameTextField.text = defaultSaveName
        gameNameTextField.setSelection(0, defaultSaveName.length)
    }

    private fun enableSaveButton(text: String) {
        rightSideButton.isEnabled = UncivFiles.isValidFileName(text)
    }

    private fun copyToClipboardHandler() {
        Concurrency.run("Copy game to clipboard") {
            // the Gzip rarely leads to ANRs
            try {
                Gdx.app.clipboard.contents = UncivFiles.gameInfoToString(gameInfo, forceZip = true)
                launchOnGLThread {
                    ToastPopup("Current game copied to clipboard!", this@SaveGameScreen)
                }
            } catch (ex: Throwable) {
                Log.error(saveToClipboardErrorMessage, ex)
                launchOnGLThread {
                    ToastPopup(saveToClipboardErrorMessage, this@SaveGameScreen)
                }
            }
        }
    }

    private fun saveToCustomLocation() {
        saveToCustomLocationButton.setText(savingText.tr())
        saveToCustomLocationButton.disable()
        errorLabel.isVisible = false
        Concurrency.runOnNonDaemonThreadPool(saveToCustomText) {
            game.files.saveGameToCustomLocation(gameInfo, gameNameTextField.text,
                {
                    game.popScreen()
                },
                {
                    if (it !is PlatformSaverLoader.Cancelled) {
                        handleException(it, "Could not save game to custom location!")
                    }
                    saveToCustomLocationButton.setText(saveToCustomText.tr())
                    saveToCustomLocationButton.enable()
                }
            )
        }
    }

    private fun initLandscapeLayout() {
        topTable.clear()
        bottomTable.clear()
        rightSideButton.remove()
        pickerPane.remove()

        closeButton.label.setFontSize(saveGameContentFontSize)
        showAutosavesCheckbox.label.setFontSize(saveGameContentFontSize)
        descriptionLabel.setFontSize(saveGameContentFontSize)
        errorLabel.setFontSize(saveGameCompactActionFontSize)
        rightSideButton.label.setFontSize(saveGameContentFontSize)
        copyJsonButton.label.setFontSize(saveGameCompactActionFontSize)
        saveToCustomLocationButton.label.setFontSize(saveGameCompactActionFontSize)
        deleteSaveButton.label.setFontSize(saveGameCompactActionFontSize)
        listOf(
            rightSideButton,
            copyJsonButton,
            saveToCustomLocationButton,
            deleteSaveButton,
        ).forEach(::prepareResponsiveButton)
        closeButton.labelCell.minWidth(0f)
        showAutosavesCheckbox.labelCell.minWidth(0f)

        val screenTable = Table().apply {
            setFillParent(true)
            background = skinStrings.getUiBackground(
                "SaveGameScreen/Background",
                tintColor = skinStrings.skinConfig.clearColor,
            )
        }

        val titleLabel = saveButtonText.toLabel(fontSize = saveGameTitleFontSize).apply {
            setEllipsis(true)
            setAlignment(Align.center)
        }
        val closeButtonWidth = maxOf(110f, closeButton.prefWidth + 16f)
            .coerceAtMost(stage.width * 0.25f)
        val header = Table().apply {
            background = skinStrings.getUiBackground(
                "SaveGameScreen/Header",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.35f),
            )
            add(closeButton).width(closeButtonWidth).minHeight(48f).padLeft(8f)
            add(titleLabel).growX().minWidth(0f).center().pad(0f, 10f, 0f, 10f)
            add().width(closeButtonWidth).padRight(8f)
        }
        screenTable.add(header).growX().minHeight(64f).pad(8f, 10f, 4f, 10f).row()

        savesScrollPane.setScrollingDisabled(true, false)
        (savesScrollPane.actor as Table).apply {
            top()
            defaults().growX()
        }
        val savesPanel = Table().apply {
            background = skinStrings.getUiBackground(
                "SaveGameScreen/SavesPanel",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.2f),
            )
            add("Current saves".toLabel(fontSize = saveGameContentFontSize))
                .growX().minWidth(0f).left().pad(8f, 12f, 6f, 12f).row()
            add(savesScrollPane).grow().minWidth(0f).minHeight(0f).row()
            add(showAutosavesCheckbox).growX().minWidth(0f).left().pad(6f, 12f, 8f, 12f)
        }

        descriptionScroll.setScrollingDisabled(true, false)
        descriptionScroll.setOverscroll(false, false)
        descriptionLabel.setAlignment(Align.topLeft)

        val nameAndSave = Table().apply {
            add(gameNameTextField).growX().minWidth(0f).minHeight(50f)
            add(rightSideButton).minWidth(150f).minHeight(50f).padLeft(8f)
        }
        val secondaryActions = Table().apply {
            add(saveToCustomLocationButton).growX().minWidth(0f).minHeight(60f)
            add(copyJsonButton).growX().minWidth(0f).minHeight(60f).padLeft(8f)
        }
        val dangerActions = Table().apply {
            add().growX()
            add(deleteSaveButton).minWidth(150f).minHeight(48f)
        }
        val editorPanel = Table().apply {
            top()
            defaults().growX()
            add(nameFieldLabelText.toLabel(Color.LIGHT_GRAY, fontSize = saveGameSecondaryFontSize))
                .minWidth(0f).left().pad(10f, 12f, 4f, 12f).row()
            add(nameAndSave).minWidth(0f).pad(0f, 12f, 8f, 12f).row()
            add(errorLabel).minWidth(0f).center().pad(0f, 12f, 4f, 12f).row()
            add(descriptionScroll).minWidth(0f).minHeight(56f).pad(0f, 4f, 4f, 4f).row()
            add(secondaryActions).minWidth(0f).pad(0f, 12f, 8f, 12f).row()
            add(dangerActions).minWidth(0f).pad(0f, 12f, 10f, 12f)
        }
        val editorScroll = AutoScrollPane(editorPanel).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val editorSurface = Table().apply {
            background = skinStrings.getUiBackground(
                "SaveGameScreen/EditorPanel",
                skinStrings.roundedEdgeRectangleSmallShape,
                skinStrings.skinConfig.baseColor.darken(0.2f),
            )
            add(editorScroll).grow().minWidth(0f).minHeight(0f)
        }

        val bodyContentWidth = stage.width - 30f
        val editorWidth = (stage.width * 0.45f).coerceAtMost(bodyContentWidth * 0.50f)
        val savesWidth = bodyContentWidth - editorWidth
        val body = Table().apply {
            add(savesPanel).grow().width(savesWidth).minWidth(0f)
            add(editorSurface).grow().width(editorWidth).minWidth(0f).padLeft(10f)
        }
        screenTable.add(body).grow().minWidth(0f).minHeight(0f).pad(0f, 10f, 10f, 10f)
        stage.addActor(screenTable)
    }

    private fun prepareResponsiveButton(button: TextButton) {
        button.label.setWrap(true)
        button.label.setAlignment(Align.center)
        button.labelCell.minWidth(0f)
    }

    private fun saveGame(saveGameFile: FileHandle) {
        rightSideButton.setText(savingText.tr())
        errorLabel.isVisible = false
        Concurrency.runOnNonDaemonThreadPool("SaveGame") {
            game.files.saveGame(gameInfo, saveGameFile) { exception ->
                launchOnGLThread {
                    if (exception != null) {
                        handleException(exception, "Could not save game!", game.files.getSave(gameNameTextField.text))
                        rightSideButton.setText(saveButtonText.tr())
                    }
                    else UncivGame.Current.popScreen()
                }
            }
        }
    }

    override fun onExistingSaveSelected(saveGameFile: FileHandle) {
        gameNameTextField.text = saveGameFile.name()
    }

    override fun doubleClickAction(saveGameFile: FileHandle) {
        ConfirmPopup(
            this,
            "Overwrite existing file?",
            "Overwrite",
        ) { saveGame(saveGameFile) }.open()
    }

}
