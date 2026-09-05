package com.unciv.app

import com.badlogic.gdx.Gdx
import com.unciv.utils.PlatformDisplay
import com.unciv.utils.SafeInsets

/** Display orientation is fixed by the iOS application configuration and Info.plist for this POC. */
class IOSDisplay : PlatformDisplay {
    override fun getSafeInsets() = SafeInsets(
        left = Gdx.graphics.safeInsetLeft,
        top = Gdx.graphics.safeInsetTop,
        right = Gdx.graphics.safeInsetRight,
        bottom = Gdx.graphics.safeInsetBottom
    )
}
