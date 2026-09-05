package com.unciv.app

import com.unciv.logic.files.UncivFiles
import com.unciv.ui.components.fonts.Fonts
import com.unciv.utils.Display
import com.unciv.utils.Log

object IOSPlatform {
    @JvmStatic
    fun configure() {
        Log.backend = IOSLogBackend()
        Display.platform = IOSDisplay()
        Fonts.fontImplementation = IOSFont()
        UncivFiles.preferExternalStorage = false
        // Capture the cleanup cutoff before a saver can create an active export.
        TemporaryExport.cleanupAbandoned()
        UncivFiles.saverLoader = IOSSaverLoader()
    }
}
