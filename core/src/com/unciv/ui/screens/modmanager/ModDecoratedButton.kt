package com.unciv.ui.screens.modmanager

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.models.metadata.ModCategories
import com.unciv.models.translations.tr
import com.unciv.ui.images.ImageGetter

/** A mod button on the Mod Manager Screen...
 *
 *  Used both in the "installed" and the "online/downloadable" columns.
 *  The "installed" version shows indicators for "Selected as permanent visual mod" and "update available",
 *  as read from the [modInfo] fields, but requires a [updateIndicators] call when those change.
 */
internal class ModDecoratedButton(private var modInfo: ModUIData) : Table() {
    private val stateImages: ModStateImages?
    private val title = ModManagementStyle.label(modInfo.buttonText(), 24)
    private val categoriesLabel = ModManagementStyle.label("", 18, ModManagementStyle.muted)

    init {
        touchable = Touchable.enabled
        background = ModManagementStyle.rounded(ModManagementStyle.background)
        pad(14f)
        val icon = Table().apply {
            background = ModManagementStyle.rounded(ModManagementStyle.raised)
            add(ImageGetter.getImage("OtherIcons/Mods", ModManagementStyle.accent)).size(28f)
        }
        add(icon).size(54f, 62f).padRight(14f)
        val text = Table().apply {
            add(title).growX().minWidth(0f).left().row()
            add(categoriesLabel).growX().minWidth(0f).left().padTop(7f)
        }
        add(text).growX().minWidth(0f)
        stateImages = if (modInfo.ruleset == null) null else ModStateImages()
        if (stateImages != null) add(stateImages).padLeft(8f)
        updateText()
        updateIndicators()
    }

    private fun updateText() {
        title.setText(modInfo.buttonText().tr())
        val topics = modInfo.topics()
        val categories = ModCategories.asSequence()
            .filter { it != ModCategories.default() && it.topic in topics }
            .joinToString(" · ") { it.label.tr() }
        categoriesLabel.setText(categories.ifEmpty {
            if (modInfo.isInstalled) "Installed".tr() else "Mods".tr()
        })
    }

    fun updateIndicators() = stateImages?.update(modInfo)
    fun setText(text: String) = title.setText(text.tr())
    override fun setColor(color: Color) {
        background = ModManagementStyle.rounded(
            if (color == Color.WHITE) ModManagementStyle.background else ModManagementStyle.selected
        )
    }

    fun updateUIData(newModUIData: ModUIData) {
        modInfo = newModUIData
        updateText()
        updateIndicators()
    }

    /** Helper class keeps references to decoration images of installed mods to enable dynamic visibility
     * (actually we do not use isVisible but refill thiis container selectively which allows the aggregate height to adapt and the set to center vertically)
     */
    private class ModStateImages : Table() {
        /** image indicating _enabled as permanent visual mod_ */
        private val visualImage: Image = ImageGetter.getImage("UnitPromotionIcons/Scouting")
        /** image indicating _online mod has been updated_ */
        private val hasUpdateImage: Image = ImageGetter.getImage("OtherIcons/ModUpdate")

        init {
            defaults().size(20f).align(Align.topLeft)
        }

        fun update(modInfo: ModUIData) {
            clear()
            if (modInfo.isVisual) add(visualImage).row()
            if (modInfo.hasUpdate) add(hasUpdateImage).row()
            pack()
        }

        override fun getMinWidth() = 20f
    }
}
