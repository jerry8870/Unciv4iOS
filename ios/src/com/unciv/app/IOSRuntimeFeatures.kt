package com.unciv.app

import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration
import com.badlogic.gdx.backends.iosrobovm.IOSAudio
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.cloud.CloudSaveSync

interface IOSRuntimeFeatures {
    val voluntarySupportAvailable: Boolean
    val secureMultiplayerPasswords: Boolean
    val oggAudioAvailable: Boolean
    fun createCloudSaveSync(files: UncivFiles): CloudSaveSync
    fun getMultiplayerPassword(serverUrl: String): String?
    fun setMultiplayerPassword(serverUrl: String, password: String): Boolean
    fun createAudio(configuration: IOSApplicationConfiguration): IOSAudio?
    fun initialize()
    fun onForeground()
    fun showVoluntarySupport()
    fun dispose()
}

object DefaultIOSRuntimeFeatures : IOSRuntimeFeatures {
    override val voluntarySupportAvailable = false
    override val secureMultiplayerPasswords = false
    override val oggAudioAvailable = false
    override fun createCloudSaveSync(files: UncivFiles) = CloudSaveSync.None
    override fun getMultiplayerPassword(serverUrl: String): String? = null
    override fun setMultiplayerPassword(serverUrl: String, password: String) = false
    override fun createAudio(configuration: IOSApplicationConfiguration): IOSAudio? = null
    override fun initialize() = Unit
    override fun onForeground() = Unit
    override fun showVoluntarySupport() = Unit
    override fun dispose() = Unit
}
