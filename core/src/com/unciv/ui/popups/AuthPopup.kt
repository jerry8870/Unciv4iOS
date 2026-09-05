package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.logic.multiplayer.rethrowCancellationAfterCleanup
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

class AuthPopup(
    stage: Stage,
    private val multiplayerServer: MultiplayerServer =
        UncivGame.Current.onlineMultiplayer.multiplayerServer,
    private val authSuccessful: ((Boolean) -> Unit)? = null,
)
    : Popup(stage) {

    constructor(
        screen: BaseScreen,
        multiplayerServer: MultiplayerServer = UncivGame.Current.onlineMultiplayer.multiplayerServer,
        authSuccessful: ((Boolean) -> Unit)? = null,
    ) : this(screen.stage, multiplayerServer, authSuccessful)

    private val passwordField: UncivTextField = UncivTextField("Password").apply { isPasswordMode = true }
    private val button: TextButton = "Authenticate".toTextButton()
    private val negativeButtonStyle: TextButton.TextButtonStyle =
        BaseScreen.skin.get("negative", TextButton.TextButtonStyle::class.java)
    private val targetServerUrl = try {
        multiplayerServer.getValidatedServerUrl()
    } catch (_: Exception) {
        null
    }
    private var authenticationJob: Job? = null
    private var resultReported = false

    init {
        button.onClick {
            val password = passwordField.text
            val serverUrl = targetServerUrl
            if (serverUrl == null) {
                clear()
                addComponents("Authentication failed")
                return@onClick
            }
            button.isDisabled = true
            authenticationJob = Concurrency.run("Multiplayer authentication") {
                val authenticated = try {
                    multiplayerServer.authenticate(
                        password,
                        authRequired = true,
                        savePasswordOnSuccess = false,
                    )
                } catch (exception: CancellationException) {
                    rethrowCancellationAfterCleanup(exception) {
                        Concurrency.runOnGLThread { button.isDisabled = false }
                    }
                } catch (_: Exception) {
                    false
                }
                launchOnGLThread {
                    if (resultReported) return@launchOnGLThread
                    button.isDisabled = false
                    if (authenticated) {
                        try {
                            UncivGame.Current.settings.multiplayer.setPassword(serverUrl, password)
                        } catch (_: Exception) {
                            clear()
                            addComponents("Could not store the multiplayer password securely.")
                            return@launchOnGLThread
                        }
                        resultReported = true
                        authSuccessful?.invoke(true)
                        close()
                    } else {
                        clear()
                        addComponents("Authentication failed")
                    }
                }
            }
        }
        addComponents("Please enter your server password")
    }

    override fun close() {
        if (!resultReported) {
            resultReported = true
            authSuccessful?.invoke(false)
        }
        authenticationJob?.cancel()
        super.close()
    }
    
    private fun addComponents(headerLabelText: String) {
        addGoodSizedLabel(headerLabelText).colspan(2).row()
        targetServerUrl?.let { addGoodSizedLabel(it).colspan(2).row() }
        add(passwordField).colspan(2).growX().pad(16f, 0f, 16f, 0f).row()
        addCloseButton(style = negativeButtonStyle).growX().padRight(8f)
        add(button).growX().padLeft(8f)
    }
}
