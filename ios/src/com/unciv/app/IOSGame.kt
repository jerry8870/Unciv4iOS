package com.unciv.app

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.cloud.CloudSaveSync
import com.unciv.logic.files.cloud.CloudSyncTrigger
import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import com.unciv.utils.MultiplayerBackgroundTask
import com.unciv.utils.PlatformCapabilities
import com.unciv.utils.Display
import org.robovm.apple.foundation.NSBundle
import java.util.Locale

class IOSGame @JvmOverloads constructor(
    private val runtimeFeatures: IOSRuntimeFeatures = DefaultIOSRuntimeFeatures,
) : UncivGame() {
    private var cloudSaveService: CloudSaveSync? = null

    override val voluntarySupportAvailable get() = runtimeFeatures.voluntarySupportAvailable

    override val sourceCodeUrl = "${com.unciv.Constants.uncivRepoURL}tree/${IOSSourceRevision.COMMIT}/"

    override val displayBuildNumber: String?
        get() = NSBundle.getMainBundle()
            .getInfoDictionaryObject("CFBundleVersion")
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    override val platformCapabilities = PlatformCapabilities(
        onlineMultiplayer = true,
        multiplayerChat = false,
        defaultMusicDownload = false,
        onlineModManagement = true,
        multiplayerServerRequiresHttps = true,
        multiplayerApiV1Only = true,
        secureMultiplayerServerPasswords = runtimeFeatures.secureMultiplayerPasswords,
        multiplayerAdvancedActions = false,
        oggAudio = runtimeFeatures.oggAudioAvailable,
    )

    override fun getMultiplayerServerPassword(serverUrl: String) =
        runtimeFeatures.getMultiplayerPassword(serverUrl)

    override fun setMultiplayerServerPassword(serverUrl: String, password: String) =
        runtimeFeatures.setMultiplayerPassword(serverUrl, password)

    override fun createMultiplayerV1Transport(): MultiplayerV1Transport =
        IOSMultiplayerV1Transport()

    override fun createModHttpClientEngine() = IOSModHttpClientEngine()

    override fun beginMultiplayerUploadBackgroundTask(onExpired: () -> Unit): MultiplayerBackgroundTask =
        IOSMultiplayerBackgroundTask(onExpired)

    override fun showVoluntarySupport() = runtimeFeatures.showVoluntarySupport()

    override fun create() {
        removeObsoletePocMusic()
        super.create()
        cloudSaveService = runtimeFeatures.createCloudSaveSync(files).also {
            files.cloudSaveSync = it
            it.requestSync(CloudSyncTrigger.Startup)
        }
        runtimeFeatures.initialize()
    }

    override fun resume() {
        super.resume()
        (Display.platform as IOSDisplay).restoreOrientation()
        cloudSaveService?.requestSync(CloudSyncTrigger.Foreground)
        runtimeFeatures.onForeground()
    }

    override fun render() {
        (Display.platform as IOSDisplay).updateVisibleArea((screen as? com.unciv.ui.screens.basescreen.BaseScreen)?.stage)
        super.render()
    }

    override fun dispose() {
        files.cloudSaveSync = CloudSaveSync.None
        cloudSaveService?.close()
        cloudSaveService = null
        runtimeFeatures.dispose()
        super.dispose()
    }

    private fun removeObsoletePocMusic() {
        for (name in listOf("Unciv POC Ambient.mp3", "Unciv POC Ambient.ogg")) {
            val obsoleteFile = Gdx.files.local("music/$name")
            if (obsoleteFile.exists()) obsoleteFile.delete()
        }
    }

    override fun getLocaleFromLanguageTag(languageTag: String): Locale {
        val parts = languageTag.substringBefore("-u-").split('-')
        return Locale(parts[0], parts.getOrElse(1) { "" })
    }

    override fun getGcCount(): Int = 0
}
