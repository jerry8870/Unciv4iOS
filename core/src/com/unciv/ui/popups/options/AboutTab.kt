package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.translations.tr
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick

internal class AboutTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    init {
        renderTo(this)
        if (UncivGame.Current.voluntarySupportAvailable) {
            add("Support iOS maintenance".toTextButton().onClick {
                UncivGame.Current.showVoluntarySupport()
            }).padTop(15f).row()
        }
    }

    companion object {
        /** Get "About" content in a separate Table without the [OptionsPopupTab] contract */
        // MainMenuscreen adds this to a Popup - one Table wrapper could be saved by using renderTo(Popup.innerTable), but that would be longer code
        fun asTable() = Table().apply { renderTo(this) }

        private fun renderTo(table: Table) {
            table.pad(20f)
            // The changelog has no patches, and anchors per release tag omit the dots
            val versionAnchor = Regex("""\.|-patch\d+$""").replace(UncivGame.VERSION.text, "")
            val version = UncivGame.Current.displayBuildNumber
                ?.let { "[${UncivGame.VERSION.text}] (Build [$it])".tr() }
                ?: UncivGame.VERSION.toNiceString()
            val lines = sequence {
                yield(FormattedLine(extraImage = "banner", imageSize = 240f, centered = true))
                yield(FormattedLine())
                yield(FormattedLine("{Version}: $version", link = "${UncivGame.Current.sourceCodeUrl}changelog.md#$versionAnchor"))
                yield(FormattedLine("See online Readme", link = "${UncivGame.Current.sourceCodeUrl}README.md#unciv4ios---civ-v-remake-for-ios"))
                yield(FormattedLine("Visit repository", link = UncivGame.Current.sourceCodeUrl))
                yield(FormattedLine("Visit the wiki", link = Constants.wikiURL))
            }
            MarkupRenderer.renderTo(table, lines.asIterable())
        }
    }
}
