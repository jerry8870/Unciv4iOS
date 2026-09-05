package com.unciv.ui.screens.modmanager

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** Shared presentation for the Mod list, details and controls. */
internal object ModManagementStyle {
    val background = Color.valueOf("101b2b")
    val surface = Color.valueOf("172538")
    val raised = Color.valueOf("203249")
    val selected = Color.valueOf("343a3b")
    val text = Color.valueOf("edf1f6")
    val muted = Color.valueOf("adbdce")
    val accent = Color.valueOf("e1bd78")
    val line = Color.valueOf("33465b")
    val success = Color.valueOf("99d6b5")
    val danger = Color.valueOf("f1a8a2")

    fun fill(color: Color) = ImageGetter.getWhiteDotDrawable().tint(color)

    fun rounded(color: Color) = BaseScreen.skinStrings.getUiBackground(
        "ModManagementScreen/Control",
        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
        tintColor = color
    )

    fun label(value: String, size: Int = 22, color: Color = text): Label =
        value.toLabel(color, fontSize = size).apply { wrap = true }

    fun button(value: String, primary: Boolean = false): TextButton =
        value.toTextButton().also { styleButton(it, primary) }

    fun styleButton(button: TextButton, primary: Boolean = false) {
        button.style = TextButton.TextButtonStyle(button.style).apply {
            up = rounded(if (primary) accent else raised)
            down = rounded(selected)
            over = rounded(selected)
            disabled = rounded(raised)
            fontColor = if (primary) background else text
            downFontColor = text
            overFontColor = text
            disabledFontColor = muted
        }
        button.label.setFontSize(22)
        button.label.wrap = true
        button.labelCell.minWidth(0f).growX()
        button.pad(13f, 18f, 13f, 18f)
    }
}
