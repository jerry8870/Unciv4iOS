package com.unciv.utils

import com.unciv.logic.multiplayer.storage.MultiplayerV1Transport
import com.unciv.logic.multiplayer.storage.SimpleHttp
import yairm210.purity.annotations.Readonly
import java.util.Locale

data class PlatformCapabilities(
    val onlineMultiplayer: Boolean = true,
    val multiplayerChat: Boolean = true,
    val defaultMusicDownload: Boolean = true,
    val onlineModManagement: Boolean = true,
    val multiplayerServerRequiresHttps: Boolean = false,
    val multiplayerApiV1Only: Boolean = false,
    val secureMultiplayerServerPasswords: Boolean = false,
    val multiplayerAdvancedActions: Boolean = true,
    val oggAudio: Boolean = true,
) {
    override fun hashCode(): Int {
        var result = if (onlineMultiplayer) 1231 else 1237
        result = 31 * result + (if (multiplayerChat) 1231 else 1237)
        result = 31 * result + (if (defaultMusicDownload) 1231 else 1237)
        result = 31 * result + (if (onlineModManagement) 1231 else 1237)
        result = 31 * result + (if (multiplayerServerRequiresHttps) 1231 else 1237)
        result = 31 * result + (if (multiplayerApiV1Only) 1231 else 1237)
        result = 31 * result + (if (secureMultiplayerServerPasswords) 1231 else 1237)
        result = 31 * result + (if (multiplayerAdvancedActions) 1231 else 1237)
        result = 31 * result + (if (oggAudio) 1231 else 1237)
        return result
    }
}

private val localeForLanguageTagMethod = try {
    Locale::class.java.getMethod("forLanguageTag", String::class.java)
} catch (_: Exception) {
    null
}

@Suppress("DEPRECATION") // RoboVM 2.3.26 exposes only Locale's legacy constructors.
private fun legacyLocaleFromLanguageTag(languageTag: String): Locale {
    val parts = languageTag.substringBefore("-u-").split('-')
    val language = parts.firstOrNull().orEmpty()
    val script = parts.getOrNull(1)?.takeIf { it.length == 4 && it.all(Char::isLetter) }
    val regionIndex = if (script == null) 1 else 2
    val region = parts.getOrNull(regionIndex)
        ?.takeIf { it.length == 2 || it.length == 3 && it.all(Char::isDigit) }.orEmpty()
    val variantIndex = regionIndex + if (region.isEmpty()) 0 else 1
    val variant = (listOfNotNull(script) + parts.drop(variantIndex)).joinToString("_")
    return Locale(language, region, variant)
}

const val ONLINE_MULTIPLAYER_UNAVAILABLE = "Online multiplayer is unavailable in this build."
const val DEFAULT_MUSIC_DOWNLOAD_UNAVAILABLE = "Default music download is unavailable in this build."
const val ONLINE_MOD_MANAGEMENT_UNAVAILABLE = "Online mod management is unavailable in this build."

interface MultiplayerBackgroundTask {
    val isExpired: Boolean
    fun finish()
}

interface PlatformSpecific {

    /** Public source revision corresponding to this build, when supplied by the platform. */
    val sourceCodeUrl: String
        get() = "${com.unciv.Constants.uncivRepoURL}tree/master/"

    /** Platform package build number shown next to the version, when available. */
    val displayBuildNumber: String?
        get() = null

    /** Features implemented by this platform. Existing platforms support both by default. */
    val platformCapabilities: PlatformCapabilities
        get() = PlatformCapabilities()

    /** Whether this platform offers the optional, non-gameplay-affecting support purchase flow. */
    val voluntarySupportAvailable: Boolean
        get() = false

    /** Opens the platform-owned voluntary support purchase flow. */
    fun showVoluntarySupport() {}

    /** Notifies player that his multiplayer turn started */
    fun notifyTurnStarted() {}

    /** Creates the platform network transport used by API v1 multiplayer. */
    fun createMultiplayerV1Transport(): MultiplayerV1Transport = SimpleHttp()

    /** Secure storage hooks used only by platforms that opt in through [PlatformCapabilities.secureMultiplayerServerPasswords]. */
    @Readonly
    fun getMultiplayerServerPassword(serverUrl: String): String? = null
    fun setMultiplayerServerPassword(serverUrl: String, password: String): Boolean = false

    /** Gives an in-flight turn upload a short platform background-execution window. */
    fun beginMultiplayerUploadBackgroundTask(onExpired: () -> Unit): MultiplayerBackgroundTask? = null

    /** Install system audio hooks */
    fun installAudioHooks() {}

    /** If not null, this is the path to the directory in which to store the local files - mods, saves, maps, etc */
    var customDataDirectory: String?

    /** If the OS localizes all error messages, this should provide a lookup */
    fun getSystemErrorMessage(errorCode: Int): String? = null

    /** Returns the number of garbage collections observed, when exposed by the platform. */
    fun getGcCount(): Int = 0

    /** Get system locale, on Android 13+ app-specific locale */
    fun getDefaultLocale(): Locale = Locale.getDefault()

    /** Build a locale from a BCP 47 language tag. */
    @Readonly @Suppress("purity") // Reflection probes an optional pure JDK factory; legacy runtimes use the constructor fallback.
    fun getLocaleFromLanguageTag(languageTag: String): Locale {
        try {
            val locale = localeForLanguageTagMethod?.invoke(null, languageTag) as? Locale
            if (locale != null) return locale
        } catch (_: Exception) {
            // The legacy parser below is also the fallback for runtimes with incomplete reflection.
        }
        return legacyLocaleFromLanguageTag(languageTag)
    }
}
