package com.unciv.app

import com.unciv.utils.DefaultLogBackend
import org.robovm.apple.uikit.UIDevice

class IOSLogBackend : DefaultLogBackend() {
    override fun getSystemInfo(): String {
        val device = UIDevice.getCurrentDevice()
        return "${device.systemName} ${device.systemVersion}, ${device.model}"
    }
}
