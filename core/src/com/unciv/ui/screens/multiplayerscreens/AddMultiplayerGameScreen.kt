package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.IdChecker
import com.unciv.logic.multiplayer.rethrowCancellationAfterCleanup
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.isUUID
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException

class AddMultiplayerGamePopup(
    private val multiplayerScreen: MultiplayerScreen,
) : Popup(multiplayerScreen, Scrollability.WithoutButtons, 0.8f) {
    private val gameIdTextField = UncivTextField("Paste the Game ID shared by the creator")
    private val gameNameTextField = UncivTextField("For example: Weekend game")

    init {
        defaults().growX().pad(4f)
        addGoodSizedLabel(
            "Add game by Game ID",
            size = multiplayerTitleFontSize,
        ).row()
        addGoodSizedLabel(
            "This downloads an existing game; it does not create a new multiplayer game.",
            size = multiplayerSecondaryFontSize,
            color = Color.LIGHT_GRAY,
        ).row()

        add("Game ID".toLabel(fontSize = multiplayerSecondaryFontSize)).left().padTop(8f).row()
        val pasteButton = "Paste Game ID".toTextButton()
        pasteButton.onClick { gameIdTextField.text = Gdx.app.clipboard.contents }
        val gameIdRow = Table().apply {
            add(gameIdTextField).growX().height(46f).padRight(6f)
            add(pasteButton).height(46f)
        }
        add(gameIdRow).width(stageToShowOn.width * 0.55f).row()

        add("Local display name (optional)".toLabel(fontSize = multiplayerSecondaryFontSize)).left().padTop(6f).row()
        add(gameNameTextField).width(stageToShowOn.width * 0.55f).height(46f).row()

        addCloseButton("Cancel")
        val positiveButtonStyle = skin.get("positive", TextButton.TextButtonStyle::class.java)
        addButton("Add to list", KeyCharAndCode.RETURN, positiveButtonStyle) { addGame() }
        showListeners += { stageToShowOn.keyboardFocus = gameIdTextField }
    }

    private fun addGame() {
        val gameId = IdChecker.checkAndReturnUuiId(gameIdTextField.text)
        if (gameId?.isUUID() != true) {
            ToastPopup("Invalid game ID!", multiplayerScreen)
            return
        }

        val workingPopup = Popup(multiplayerScreen)
        workingPopup.addGoodSizedLabel(Constants.working)
        workingPopup.open(force = true)

        Concurrency.run("AddMultiplayerGame") {
            try {
                multiplayerScreen.game.onlineMultiplayer.addGame(
                    gameId,
                    gameNameTextField.text.trim(),
                )
                launchOnGLThread {
                    workingPopup.close()
                    close()
                    multiplayerScreen.onMultiplayerGameAdded()
                }
            } catch (ex: CancellationException) {
                rethrowCancellationAfterCleanup(ex) {
                    Concurrency.runOnGLThread { workingPopup.close() }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread { workingPopup.reuseWith(message, true) }
            }
        }
    }
}
