package com.unciv.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.UncivGame
import com.unciv.logic.event.EventBus
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.utils.PlatformDisplay
import com.unciv.utils.SafeInsets
import com.unciv.utils.ScreenOrientation
import org.robovm.apple.dispatch.DispatchQueue
import org.robovm.apple.foundation.NSOperatingSystemVersion
import org.robovm.apple.foundation.NSProcessInfo
import org.robovm.apple.uikit.UIInterfaceOrientationMask
import org.robovm.apple.uikit.UISceneActivationState
import org.robovm.apple.uikit.UIViewController
import org.robovm.apple.uikit.UIWindowSceneGeometryPreferencesIOS

class IOSDisplay : PlatformDisplay {
    @Volatile private var safeInsets = SafeInsets()
    @Volatile private var edgeToEdge = false
    @Volatile private var orientation = ScreenOrientation.Landscape
    @Volatile private var orientationRevision = 0
    @Volatile private var keyboardTop: Float? = null
    private var controller: UncivIOSViewController? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastVisibleStage: Stage? = null
    private var lastVisibleArea: Rectangle? = null

    fun attach(controller: UncivIOSViewController) { this.controller = controller }

    override fun applySettings(settings: GameSettings) {
        edgeToEdge = settings.iosUseDisplayCutout
        orientation = settings.displayOrientation
        restoreOrientation()
    }

    override fun hasOrientation() = true
    override fun getSafeInsets() = safeInsets
    override fun isEdgeToEdgeEnabled() = edgeToEdge

    override fun setCutout(enabled: Boolean) {
        if (edgeToEdge == enabled) return
        edgeToEdge = enabled
        refreshLayout()
    }

    override fun setOrientation(orientation: ScreenOrientation) {
        this.orientation = orientation
        requestOrientation(notifyOnFailure = true)
    }

    fun restoreOrientation() = requestOrientation(notifyOnFailure = false)

    fun getOrientationMask(): UIInterfaceOrientationMask = when (orientation) {
        ScreenOrientation.Landscape -> UIInterfaceOrientationMask.Landscape
        ScreenOrientation.Portrait -> UIInterfaceOrientationMask.Portrait
        ScreenOrientation.Auto -> UIInterfaceOrientationMask.AllButUpsideDown
    }

    private fun requestOrientation(notifyOnFailure: Boolean) {
        val revision = ++orientationRevision
        DispatchQueue.getMainQueue().async {
            if (revision != orientationRevision) return@async
            val host = controller ?: return@async
            if (!host.isViewLoaded) return@async
            val scene = host.view.window?.windowScene ?: return@async
            if (host.presentedViewController != null || scene.activationState != UISceneActivationState.ForegroundActive)
                return@async // viewDidAppear/resume reapplies the latest preference.
            if (NSProcessInfo.getSharedProcessInfo().isOperatingSystemAtLeastVersion(NSOperatingSystemVersion(16, 0, 0))) {
                host.setNeedsUpdateOfSupportedInterfaceOrientations()
                scene.requestGeometryUpdate(UIWindowSceneGeometryPreferencesIOS(getOrientationMask())) { error ->
                    if (revision != orientationRevision) return@requestGeometryUpdate
                    Gdx.app.error("IOSDisplay", error.localizedDescription)
                    if (notifyOnFailure) Gdx.app.postRunnable {
                        if (revision != orientationRevision || !UncivGame.isCurrentInitialized()) return@postRunnable
                        UncivGame.Current.screen?.let {
                            ToastPopup("The screen orientation could not be changed. Try rotating your device.", it)
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                UIViewController.attemptRotationToDeviceOrientation()
            }
        }
    }

    /** UIKit calls this on its main thread, including changes that do not resize the window. */
    fun updateGeometry() {
        val view = controller?.view ?: return
        val width = view.bounds.width.toInt()
        val height = view.bounds.height.toInt()
        if (width <= 0 || height <= 0) return
        val nativeInsets = view.safeAreaInsets
        val next = SafeInsets(nativeInsets.left.toInt(), nativeInsets.top.toInt(),
            nativeInsets.right.toInt(), nativeInsets.bottom.toInt())
        if (next == safeInsets && width == viewWidth && height == viewHeight) return
        safeInsets = next
        viewWidth = width
        viewHeight = height
        refreshLayout()
    }

    fun updateKeyboard(top: Float?) { keyboardTop = top }

    private fun refreshLayout() {
        Gdx.app?.postRunnable {
            if (!UncivGame.isCurrentInitialized()) return@postRunnable
            UncivGame.Current.screen?.resize(Gdx.graphics.width, Gdx.graphics.height)
        }
    }

    /** Runs on the render thread, so events and stage coordinates always share the current viewport. */
    fun updateVisibleArea(stage: Stage?) {
        if (stage == null) return
        val bottom = keyboardTop?.let {
            stage.screenToStageCoordinates(Vector2(0f, it)).y.coerceIn(0f, stage.height)
        } ?: 0f
        val visible = Rectangle(0f, bottom, stage.width, stage.height - bottom)
        if (stage === lastVisibleStage && visible == lastVisibleArea) return
        lastVisibleStage = stage
        lastVisibleArea = visible
        EventBus.send(UncivStage.VisibleAreaChanged(visible))
    }
}
