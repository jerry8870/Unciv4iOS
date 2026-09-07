package com.unciv.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.unciv.ui.images.ImageWithCustomSize
import com.unciv.ui.popups.popups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.popups.LoadingPopup

/** A loading screen that creates a screenshot of the current screen and adds a "Loading..." popup on top of that */
class LoadingScreen(
    previousScreen: BaseScreen? = null
) : BaseScreen() {
    private val screenshot: Texture
    private var loadingPopup: LoadingPopup? = null

    init {
        screenshot = takeScreenshot(previousScreen)
        val image = ImageWithCustomSize(
            TextureRegion(
                screenshot,
                0,
                screenshot.height,
                screenshot.width,
                -screenshot.height
            )
        )
        // The capture includes the viewport's full drawing area, including unsafe screen edges.
        val bounds = (stage.viewport as SafeAreaViewport).drawingBounds
        image.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        stage.addActor(image)
        stage.addAction(Actions.sequence(
            Actions.delay(1000f),
            Actions.run {
                loadingPopup = LoadingPopup(this)
            }
        ))
    }

    private fun takeScreenshot(previousScreen: BaseScreen?): Texture {
        if (previousScreen != null) {
            for (popup in previousScreen.popups) popup.isVisible = false
            previousScreen.render(Gdx.graphics.deltaTime)
        }
        val viewport = previousScreen?.stage?.viewport ?: stage.viewport
        val pixmap = Pixmap.createFromFrameBuffer(
            HdpiUtils.toBackBufferX(viewport.screenX),
            HdpiUtils.toBackBufferY(viewport.screenY),
            HdpiUtils.toBackBufferX(viewport.screenWidth),
            HdpiUtils.toBackBufferY(viewport.screenHeight)
        )
        val screenshot = Texture(pixmap)
        pixmap.dispose()

        if (previousScreen != null) {
            for (popup in previousScreen.popups) popup.isVisible = true
        }
        return screenshot
    }


    override fun dispose() {
        screenshot.dispose()
        stage.root.clearActions() // super.dispose does that too, but prevent race condition
        loadingPopup?.close() // Prevent leak due to EventReceiver reference
        super.dispose()
    }
}
