package com.unciv.utils

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.GdxNativesLoader
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.screens.LoadingScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class LoadingScreenGeometryTest {
    @Test fun loadingScreenshotKeepsTheCapturedPixelBounds() {
        val oldApp = Gdx.app
        val oldGraphics = Gdx.graphics
        val oldInput = Gdx.input
        val oldGl = Gdx.gl
        val oldGl20 = Gdx.gl20
        val oldGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else null
        val oldDisplay = runCatching { Display.platform }.getOrNull()
        GdxNativesLoader.load()
        try {
            Gdx.app = mock(Application::class.java)
            Gdx.graphics = mock(Graphics::class.java)
            Gdx.input = mock(Input::class.java)
            Gdx.gl = mock(GL20::class.java)
            Gdx.gl20 = Gdx.gl
            UncivGame.Current = UncivGame().apply { settings = GameSettings() }
            // Keep the real LoadingScreen, viewport, framebuffer capture and image layout.
            // Only GPU rendering is mocked; no game or simulator input is sent.
            mockConstruction(SpriteBatch::class.java).use {
                for ((width, height, insets) in listOf(
                    Triple(852, 393, SafeInsets(59, 0, 0, 21)),
                    Triple(852, 393, SafeInsets(0, 0, 59, 21)),
                    Triple(393, 852, SafeInsets(0, 59, 0, 34)),
                    Triple(800, 600, SafeInsets())
                )) {
                    `when`(Gdx.graphics.width).thenReturn(width)
                    `when`(Gdx.graphics.height).thenReturn(height)
                    `when`(Gdx.graphics.backBufferWidth).thenReturn(width * 3)
                    `when`(Gdx.graphics.backBufferHeight).thenReturn(height * 3)
                    for (edgeToEdge in listOf(false, true)) {
                        Display.platform = object : PlatformDisplay {
                            override fun getSafeInsets() = insets
                            override fun isEdgeToEdgeEnabled() = edgeToEdge
                        }
                        val screen = LoadingScreen()
                        try {
                            val image = screen.stage.actors.first()
                            val viewport = screen.stage.viewport
                            val bottomLeft = viewport.project(Vector2(image.x, image.y))
                            val topRight = viewport.project(Vector2(image.x + image.width, image.y + image.height))
                            val expected = if (edgeToEdge) SafeArea(0, 0, width, height) else insets.applyTo(width, height)
                            val message = "${width}x$height, edgeToEdge=$edgeToEdge, insets=$insets"
                            assertEquals(message, expected.x.toFloat(), bottomLeft.x, 0.001f)
                            assertEquals(message, expected.y.toFloat(), bottomLeft.y, 0.001f)
                            assertEquals(message, (expected.x + expected.width).toFloat(), topRight.x, 0.001f)
                            assertEquals(message, (expected.y + expected.height).toFloat(), topRight.y, 0.001f)
                        } finally {
                            screen.dispose()
                        }
                    }
                }
            }
        } finally {
            Gdx.app = oldApp
            Gdx.graphics = oldGraphics
            Gdx.input = oldInput
            Gdx.gl = oldGl
            Gdx.gl20 = oldGl20
            if (oldGame != null) UncivGame.Current = oldGame
            if (oldDisplay != null) Display.platform = oldDisplay
        }
    }
}
