package com.unciv.ui.screens.basescreen

import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.unciv.utils.SafeInsets

/** Keeps stage layout in safe-area coordinates while optionally drawing beyond those bounds. */
class SafeAreaViewport(virtualSize: Float) : ExtendViewport(virtualSize, virtualSize) {
    private var insets = SafeInsets()
    private var displayWidth = 0
    private var displayHeight = 0
    private var edgeToEdge = false

    val drawingBounds = Rectangle()

    fun updateDisplay(width: Int, height: Int, insets: SafeInsets, edgeToEdge: Boolean) {
        this.insets = insets
        this.edgeToEdge = edgeToEdge
        update(width, height, true)
    }

    override fun update(screenWidth: Int, screenHeight: Int, centerCamera: Boolean) {
        val safe = insets.applyTo(screenWidth, screenHeight)
        if (safe.width <= 0 || safe.height <= 0) return
        displayWidth = screenWidth
        displayHeight = screenHeight
        super.update(safe.width, safe.height, centerCamera)
    }

    override fun apply(centerCamera: Boolean) {
        val safe = insets.applyTo(displayWidth, displayHeight)
        if (safe.width <= 0 || safe.height <= 0) return
        if (!edgeToEdge) {
            drawingBounds.set(0f, 0f, worldWidth, worldHeight)
            setScreenBounds(safe.x, safe.y, safe.width, safe.height)
            super.apply(centerCamera)
            return
        }

        val unitsPerPixelX = worldWidth / safe.width
        val unitsPerPixelY = worldHeight / safe.height
        drawingBounds.set(
            -insets.left * unitsPerPixelX, -insets.bottom * unitsPerPixelY,
            displayWidth * unitsPerPixelX, displayHeight * unitsPerPixelY
        )
        setScreenBounds(0, 0, displayWidth, displayHeight)
        HdpiUtils.glViewport(screenX, screenY, screenWidth, screenHeight)
        // worldWidth/Height remain the safe layout size used by all existing HUD widgets.
        camera.viewportWidth = drawingBounds.width
        camera.viewportHeight = drawingBounds.height
        if (centerCamera) camera.position.set(
            drawingBounds.x + drawingBounds.width / 2,
            drawingBounds.y + drawingBounds.height / 2, 0f
        )
        camera.update()
    }
}
