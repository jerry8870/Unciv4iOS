package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.logic.multiplayer.MultiplayerGamePreview
import com.unciv.logic.multiplayer.rethrowCancellationAfterCleanup
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.formatShort
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException
import org.threeten.bp.Duration
import org.threeten.bp.Instant

object MultiplayerHelpers {

    fun loadMultiplayerGame(screen: BaseScreen, selectedGame: MultiplayerGamePreview) {
        val loadingGamePopup = Popup(screen)
        loadingGamePopup.addGoodSizedLabel("Loading latest game state...")
        loadingGamePopup.open()

        Concurrency.run("JoinMultiplayerGame") {
            try {
                UncivGame.Current.onlineMultiplayer.downloadGame(selectedGame)
            } catch (ex: CancellationException) {
                rethrowCancellationAfterCleanup(ex) {
                    Concurrency.runOnGLThread { loadingGamePopup.close() }
                }
            } catch (ex: Exception) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                launchOnGLThread {
                    loadingGamePopup.reuseWith(message, true)
                }
            }
        }
    }

    fun buildDescriptionText(multiplayerGamePreview: MultiplayerGamePreview): String {
        val descriptionText = StringBuilder()
        val ex = multiplayerGamePreview.error
        if (ex != null) {
            val (message) = LoadGameScreen.getLoadExceptionMessage(ex, "Error while refreshing:")
            descriptionText.appendLine(message)
        }
        val lastUpdate = multiplayerGamePreview.getLastUpdate()
        descriptionText.appendLine("Last refresh: [${Duration.between(lastUpdate, Instant.now()).formatShort()}] ago".tr())
        val preview = multiplayerGamePreview.preview
        if (preview?.currentPlayer != null) {
            val currentTurnStartTime = Instant.ofEpochMilli(preview.currentTurnStartTime)
            val currentPlayer = preview.getCurrentPlayerCiv()
            val mpSettings = UncivGame.Current.settings.multiplayer
            // "You", name of friend, or null
            val playerDescriptor: String? = 
                if (currentPlayer.playerId == mpSettings.getUserId()) "You" 
                else mpSettings.friendList.firstOrNull { it.playerID == currentPlayer.playerId }?.name
            
            var playerText = "{${preview.currentPlayer}}"
            if (playerDescriptor != null)
                playerText += "{ }({$playerDescriptor})"

            val currentTurnTime = Duration.between(currentTurnStartTime, Instant.now())
            var currentPlayerLine = "Current Turn: [$playerText] since [${currentTurnTime.formatShort()}] ago"
            // Don't show average until we are sure all players have updated to compatible version
            // This check can be removed after a few weeks/months
            if (currentPlayer.turnsPlayedAsHuman > 0) {
                val updatedTotalTurnTime = Duration.ofSeconds(currentPlayer.totalTurnTimeSeconds.toLong()) + currentTurnTime
                val averageTurnTime = updatedTotalTurnTime.dividedBy(currentPlayer.turnsPlayedAsHuman + 1L) // +1 to include current turn and avoid div by 0
                currentPlayerLine += " (average: [${averageTurnTime.formatShort()}])"
            }
            descriptionText.appendLine(currentPlayerLine.tr())
            descriptionText.appendLine("Time to play the turn: [${Duration.ofMinutes(currentPlayer.playerMinutesBeforeForceResign.toLong()).formatShort()}]".tr())

            val playerCivName = preview.civilizations
                .firstOrNull{ it.playerId == UncivGame.Current.settings.multiplayer.getUserId() }?.civName ?: "Unknown"

            descriptionText.appendLine("{$playerCivName}, ${preview.difficulty.tr()}, ${Fonts.turn}${preview.turns}".tr())
            descriptionText.appendLine("{Base ruleset:} ${preview.gameParameters.baseRuleset}".tr())
            if (preview.gameParameters.mods.isNotEmpty())
                descriptionText.appendLine(("{Mods:} " + preview.gameParameters.mods.joinToString()).tr())

        }
        return descriptionText.toString().tr()
    }

    fun showMultiplayerServerWarning(screen: BaseScreen) {
        val game = UncivGame.Current
        if (game.settings.multiplayer.hideDropboxWarning) return

        val message = if (game.platformCapabilities.multiplayerServerRequiresHttps) {
            "Online multiplayer servers are operated by third parties. " +
                "The selected server receives your player ID, IP address, and game save data. " +
                "Its availability, retention, deletion, and remote game saves are not guaranteed by Unciv. " +
                "All players must use the same Unciv version and have the exact same versions of every mod installed."
        } else {
            if (!Multiplayer.usesDropbox()) return
            "You're currently using the default multiplayer server, which is based on a free Dropbox account. " +
                "Because a lot of people use this, it is uncertain if you'll actually be able to access it consistently. " +
                "Consider using a custom server instead."
        }

        val serverWarning = Popup(screen)
        serverWarning.addGoodSizedLabel(message).colspan(2).row()
        serverWarning.addButton("Open Documentation") {
            Gdx.net.openURI("${Constants.wikiURL}Other/Multiplayer/#hosting-a-multiplayer-server")
        }.colspan(2).row()

        val checkBox = "Don't show again".toCheckBox()
        serverWarning.add(checkBox)
        serverWarning.addCloseButton {
            game.settings.multiplayer.hideDropboxWarning = checkBox.isChecked
        }
        serverWarning.open()
    }
}
