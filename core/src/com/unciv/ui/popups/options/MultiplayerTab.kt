package com.unciv.ui.popups.options

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.files.IMediaFinder.LabeledSounds
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.logic.multiplayer.storage.AuthStatus
import com.unciv.logic.multiplayer.storage.FileStorageRateLimitReached
import com.unciv.logic.multiplayer.storage.MultiplayerAuthException
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.models.translations.tr
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.options.MultiplayerSelectBoxHelpers.RefreshSelectOptions
import com.unciv.ui.popups.AuthPopup
import com.unciv.ui.popups.Popup
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import org.threeten.bp.temporal.ChronoUnit
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

internal class MultiplayerTab(
    optionsPopup: OptionsPopup
) : OptionsPopupTab(optionsPopup), MultiplayerSelectBoxHelpers {
    private val mpSettings by settings::multiplayer
    private val mpServer by game.onlineMultiplayer::multiplayerServer

    override fun lateInitialize() {
        addCheckbox(
            "Enable multiplayer status button in singleplayer games",
            mpSettings::statusButtonInSinglePlayer, updateWorld = true
        )

        addSeparator()

        val curRefreshSelect = RefreshSelectOptions(
            mpSettings::currentGameRefreshDelay,
            createRefreshOptions(ChronoUnit.SECONDS, 3, 5),
            createRefreshOptions(ChronoUnit.SECONDS, 10, 20, 30, 60)
        )
        curRefreshSelect.selectBox = addSelectBox("Update status of currently played game every:", curRefreshSelect::value, curRefreshSelect.getItems())

        val allRefreshSelect = RefreshSelectOptions(
            mpSettings::allGameRefreshDelay,
            createRefreshOptions(ChronoUnit.SECONDS, 15, 30),
            createRefreshOptions(ChronoUnit.MINUTES, 1, 2, 5, 15)
        )
        allRefreshSelect.selectBox = addSelectBox("In-game, update status of all games every:", allRefreshSelect::value, allRefreshSelect.getItems())

        addSeparator()

        val turnCheckerSelect = addTurnCheckerOptions()

        val labeledSounds = LabeledSounds() // This is a cache but only used until the SelectBoxes are filled
        fun soundsProvider() = synchronized(labeledSounds) {
            labeledSounds.getLabeledSounds().asFlow().map { MultiplayerSelectBoxHelpers.UncivSoundLabeled(it) }
        }
        val currentSoundProxy = MultiplayerSelectBoxHelpers.UncivSoundProxy(mpSettings::currentGameTurnNotificationSound)
        val otherSoundProxy = MultiplayerSelectBoxHelpers.UncivSoundProxy(mpSettings::otherGameTurnNotificationSound)
        addAsyncSelectBox("Sound notification for when it's your turn in your currently open game:", currentSoundProxy::value, ::soundsProvider) {
            SoundPlayer.play(it.value)
        }
        addAsyncSelectBox("Sound notification for when it's your turn in any other game:", otherSoundProxy::value, ::soundsProvider) {
            SoundPlayer.play(it.value)
        }

        addSeparator()

        addMultiplayerServerOptions(listOfNotNull(curRefreshSelect, allRefreshSelect, turnCheckerSelect))

        super.lateInitialize()
    }

    private fun addMultiplayerServerOptions(toUpdate: Iterable<RefreshSelectOptions>) {
        val connectionToServerButton = "Check connection".toTextButton()

        val textToShowForOnlineMultiplayerAddress = if (Multiplayer.usesCustomServer()) {
            mpSettings.getServer()
        } else {
            "https://"
        }
        val multiplayerServerTextField = UncivTextField("Server address", textToShowForOnlineMultiplayerAddress)
        multiplayerServerTextField.setTextFieldFilter { _, c -> c !in " \r\n\t\\" }
        multiplayerServerTextField.programmaticChangeEvents = true
        var passwordTextField: UncivTextField? = null
        var setPasswordButton: TextButton? = null
        var connectionCheckInFlight = false
        var validatedServerUrl: String? = try {
            MultiplayerServer.validateServerUrl(
                multiplayerServerTextField.text,
                game.platformCapabilities,
            )
        } catch (_: Exception) {
            null
        }
        val serverIpTable = Table()

        serverIpTable.add("Server address".toLabel().onClick {
            if (!connectionCheckInFlight)
                multiplayerServerTextField.text = Gdx.app.clipboard.contents
        }).colspan(2).padBottom(Constants.defaultFontSize / 2.0f).row()

        val errorTextField = "".toLabel(Color.RED)
        errorTextField.isVisible = false

        serverIpTable.add(errorTextField).colspan(2).row()

        multiplayerServerTextField.onChange {
            fixTextFieldUrlOnType(multiplayerServerTextField)
            passwordTextField?.text = ""

            try {
                val validatedServer = MultiplayerServer.validateServerUrl(
                    multiplayerServerTextField.text,
                    game.platformCapabilities,
                )
                settings.multiplayer.setServer(validatedServer)
                validatedServerUrl = validatedServer
                errorTextField.isVisible = false
                multiplayerServerTextField.color = Color.GREEN
            } catch (ex: Throwable) {
                validatedServerUrl = null
                errorTextField.setText(ex.localizedMessage)
                errorTextField.isVisible = true
                multiplayerServerTextField.color = Color.RED
            }

            val isCustomServer = validatedServerUrl != null && Multiplayer.usesCustomServer()
            connectionToServerButton.isEnabled = validatedServerUrl != null && isCustomServer
            setPasswordButton?.isEnabled = validatedServerUrl != null

            for (refreshSelect in toUpdate) refreshSelect.update(isCustomServer)
        }
        connectionToServerButton.isEnabled =
            validatedServerUrl != null && Multiplayer.usesCustomServer()

        serverIpTable.add(multiplayerServerTextField)
            .minWidth(optionsPopup.stageToShowOn.width / 3).padRight(Constants.defaultFontSize.toFloat()).growX()

        serverIpTable.add(connectionToServerButton.onClick {
            val targetUrl = validatedServerUrl ?: return@onClick
            val targetServer = game.onlineMultiplayer.serverFor(targetUrl)
            connectionCheckInFlight = true
            connectionToServerButton.isEnabled = false
            multiplayerServerTextField.isDisabled = true
            val popup = Popup(optionsPopup.stageToShowOn).apply {
                addGoodSizedLabel("Awaiting response...").row()
                open(true)
            }

            successfullyConnectedToServer(targetServer) { connectionSuccess, authSuccess ->
                connectionCheckInFlight = false
                multiplayerServerTextField.isDisabled = false
                connectionToServerButton.isEnabled =
                    validatedServerUrl != null && Multiplayer.usesCustomServer()
                if (validatedServerUrl != targetUrl) {
                    popup.close()
                    return@successfullyConnectedToServer
                }
                if (authSuccess == false) {
                    popup.close()
                    AuthPopup(optionsPopup.stageToShowOn, targetServer) { success ->
                        popup.apply {
                            reuseWith(if (success) "Success!" else "Failed!", true)
                            open(true)
                        }
                    }.open(true)
                } else if (connectionSuccess) {
                    if (authSuccess == true) popup.reuseWith("Success!", true)
                    else popup.reuseWith("Auth rejected for unknown reasons, please try again.", true)
                } else {
                    popup.reuseWith("Failed!", true)
                }
    
                if (connectionSuccess) {
                    mpServer.setFeatureSet(targetServer.getFeatureSet())
                    // because multiplayer server url can get autopatched during isAilve test
                    multiplayerServerTextField.text = settings.multiplayer.getServer()
                }
            }
        }).row()

        if (mpServer.getFeatureSet().authVersion > 0) {
            val passwordField = UncivTextField("Password").apply { isPasswordMode = true }
            passwordTextField = passwordField
            val passwordButton = "Set password".toTextButton()
            setPasswordButton = passwordButton
            passwordButton.isEnabled = validatedServerUrl != null

            serverIpTable.add("Set password".toLabel()).padTop(16f).colspan(2).row()
            serverIpTable.add(passwordField).colspan(2).growX().padBottom(8f).row()

            // initially assume no password
            val authStatusLabel = "Set a password to secure your userId".toLabel()

            val validationServerUrl = mpSettings.getServer()
            val password = mpSettings.getPassword(validationServerUrl)
            if (password != null) {
                val validationServer = game.onlineMultiplayer.serverFor(validationServerUrl)
                authStatusLabel.setText("Validating your authentication status...".tr())
                Concurrency.run {
                    try {
                        val userId = mpSettings.getUserId()
                        val authStatus = validationServer.fileStorage()
                            .checkAuthStatus(userId, password)

                        val newAuthStatusText = when (authStatus) {
                            AuthStatus.UNAUTHORIZED -> "Your current password was rejected from the server"
                            AuthStatus.UNREGISTERED -> "You userId is unregistered! Set password to secure your userId"
                            AuthStatus.VERIFIED -> "Your current password has been succesfully verified"
                            AuthStatus.UNKNOWN -> "Your authentication status could not be determined"
                        }

                        Concurrency.runOnGLThread {
                            authStatusLabel.setText(newAuthStatusText.tr())
                        }
                    } catch (ex: CancellationException) {
                        Concurrency.runOnGLThread {
                            authStatusLabel.setText("Authentication validation was cancelled".tr())
                        }
                        throw ex
                    } catch (_: Exception) {
                        Concurrency.runOnGLThread {
                            authStatusLabel.setText("Your authentication status could not be determined".tr())
                        }
                    }
                }
            }

            val passwordStatusTable = Table().apply {
                add(authStatusLabel)
                add(passwordButton.onClick {
                    val serverUrl = validatedServerUrl ?: return@onClick
                    val targetServer = game.onlineMultiplayer.serverFor(serverUrl).apply {
                        setFeatureSet(mpServer.getFeatureSet())
                    }
                    setPassword(passwordField.text, serverUrl, targetServer, optionsPopup)
                }).padLeft(16f)
            }

            serverIpTable.add(passwordStatusTable).colspan(2).row()
        }

        add(serverIpTable).colspan(2).fillX().row()
    }

    private fun addTurnCheckerOptions(): RefreshSelectOptions? {
        // at the moment the notification service only exists on Android
        if (Gdx.app.type != Application.ApplicationType.Android) return null

        addCheckbox("Enable out-of-game turn notifications", mpSettings::turnCheckerEnabled) {
            reopenOptions(force = true)
        }

        if (!mpSettings.turnCheckerEnabled) return null

        val turnCheckerSelect = RefreshSelectOptions(
            mpSettings::turnCheckerDelay,
            createRefreshOptions(ChronoUnit.SECONDS, 30),
            createRefreshOptions(ChronoUnit.MINUTES, 1, 2, 5, 15)
        )
        turnCheckerSelect.selectBox = addSelectBox("Out-of-game, update status of all games every:", turnCheckerSelect::value, turnCheckerSelect.getItems())

        addCheckbox("Show persistent notification for turn notifier service", mpSettings::turnCheckerPersistentNotificationEnabled)

        addSeparator()
        return turnCheckerSelect
    }

    private fun successfullyConnectedToServer(
        targetServer: MultiplayerServer,
        action: (Boolean, Boolean?) -> Unit,
    ) {
        Concurrency.run("TestIsAlive") {
            try {
                val connectionSuccess = targetServer.checkServerStatus()
                var authSuccess: Boolean? = null
                if (connectionSuccess) {
                    try {
                        authSuccess = targetServer.authenticate(null)
                    } catch (_: MultiplayerAuthException) {
                        authSuccess = false
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (_: Throwable) {
                        // We ignore the exception here, because we handle the failed auth onGLThread
                    }
                }
                launchOnGLThread {
                    action(connectionSuccess, authSuccess)
                }
            } catch (ex: CancellationException) {
                Concurrency.runOnGLThread {
                    action(false, null)
                }
                throw ex
            } catch (_: Exception) {
                launchOnGLThread {
                    action(false, false)
                }
            }
        }
    }

    private fun setPassword(
        password: String,
        serverUrl: String,
        targetServer: MultiplayerServer,
        optionsPopup: OptionsPopup,
    ) {
        if (password.isBlank())
            return

        val popup = Popup(optionsPopup.stageToShowOn).apply {
            addGoodSizedLabel("Awaiting response...").row()
            open(true)
        }

        if (password.length < 6) {
            popup.reuseWith("Password must be at least 6 characters long", true)
            return
        }

        if (targetServer.getFeatureSet().authVersion == 0) {
            popup.reuseWith("This server does not support authentication", true)
            return
        }

        successfullySetPassword(password, targetServer) { success, ex ->
            if (success) {
                popup.reuseWith(
                    "Password set successfully for server [$serverUrl]",
                    true
                )
            } else {
                if (ex is MultiplayerAuthException) {
                    AuthPopup(optionsPopup.stageToShowOn, targetServer) { authSuccess ->
                        // If auth was successful, try to set password again
                        if (authSuccess) {
                            popup.close()
                            setPassword(password, serverUrl, targetServer, optionsPopup)
                        } else {
                            popup.reuseWith("Failed to set password!", true)
                        }
                    }.open(true)
                    return@successfullySetPassword
                }

                val message = when (ex) {
                    is FileStorageRateLimitReached -> "Server limit reached! Please wait for [${ex.limitRemainingSeconds}] seconds"
                    else -> "Failed to set password!"
                }

                popup.reuseWith(message, true)
            }
        }
    }

    private fun successfullySetPassword(
        password: String,
        targetServer: MultiplayerServer,
        action: (Boolean, Exception?) -> Unit,
    ) {
        Concurrency.run("SetPassword") {
            try {
                val setSuccess = targetServer.setPassword(password)
                launchOnGLThread {
                    action(setSuccess, null)
                }
            } catch (ex: CancellationException) {
                Concurrency.runOnGLThread {
                    action(false, ex)
                }
                throw ex
            } catch (ex: Exception) {
                launchOnGLThread {
                    action(false, ex)
                }
            }
        }
    }

    private fun fixTextFieldUrlOnType(textField: TextField) {
        var text: String = textField.text
        var cursor: Int = minOf(textField.cursorPosition, text.length)

        val textBeforeCursor: String = text.substring(0, cursor)

        // replace multiple slash with a single one, except when it's a ://
        val multipleSlashes = Regex("(?<!:)/{2,}")
        text = multipleSlashes.replace(text, "/")

        // calculate updated cursor
        cursor = multipleSlashes.replace(textBeforeCursor, "/").length

        // update TextField
        if (text != textField.text) {
            textField.text = text
            textField.cursorPosition = cursor
        }
    }
}
