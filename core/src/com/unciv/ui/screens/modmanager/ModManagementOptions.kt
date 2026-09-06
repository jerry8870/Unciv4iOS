package com.unciv.ui.screens.modmanager

import com.unciv.models.metadata.ModCategories
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import kotlin.math.sign

/** Filtering and sorting shared by the two Mod tabs. */
internal class ModManagementOptions(private val modManagementScreen: ModManagementScreen) {
    companion object {
        val sortByName = Comparator { mod1, mod2: ModUIData -> mod1.name.compareTo(mod2.name, true) }
        val sortByNameDesc = Comparator { mod1, mod2: ModUIData -> mod2.name.compareTo(mod1.name, true) }
        // lastUpdated is compared as string, but that should be OK as it's ISO format
        val sortByDate = Comparator { mod1, mod2: ModUIData -> mod1.lastUpdated().compareTo(mod2.lastUpdated()) }
        val sortByDateDesc = Comparator { mod1, mod2: ModUIData -> mod2.lastUpdated().compareTo(mod1.lastUpdated()) }
        // comparators for stars or status
        val sortByStars = Comparator { mod1, mod2: ModUIData ->
            10 * (mod2.stargazers() - mod1.stargazers()) + mod1.name.compareTo(mod2.name, true).sign
        }
        val sortByStatus = Comparator { mod1, mod2: ModUIData ->
            10 * (mod2.stateSortWeight() - mod1.stateSortWeight()) + mod1.name.compareTo(mod2.name, true).sign
        }

        const val installedHeaderText = "Current mods"
        const val onlineHeaderText = "Downloadable mods"
    }

    enum class SortType(
        val label: String,
        val symbols: String,
        val comparator: Comparator<in ModUIData>
    ) {
        Name("Name ${Fonts.sortUpArrow}", Fonts.sortUpArrow.toString(), sortByName),
        NameDesc("Name ${Fonts.sortDownArrow}", Fonts.sortDownArrow.toString(), sortByNameDesc),
        Date("Date ${Fonts.sortUpArrow}", "${Fonts.clock}${Fonts.sortUpArrow}", sortByDate),
        DateDesc("Date ${Fonts.sortDownArrow}", "${Fonts.clock}${Fonts.sortDownArrow}", sortByDateDesc),
        Stars("Stars ${Fonts.sortDownArrow}", "${Fonts.star}${Fonts.sortDownArrow}", sortByStars),
        Status("Status ${Fonts.sortDownArrow}", "${Fonts.status}${Fonts.sortDownArrow}", sortByStatus)
        ;
        fun next() = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromSelectBox(selectBox: TranslatedSelectBox): SortType {
                val selected = selectBox.selected.value
                return entries.firstOrNull { it.label == selected } ?: Name
            }
        }
    }

    class Filter(
        val text: String,
        val topic: String
    )

    fun getFilter(): Filter {
        return Filter(searchField.text, category.topic)
    }

    val searchField = UncivTextField("Search mods")

    var category = ModCategories.default()

    var sortInstalled = SortType.Name
    var sortOnline = SortType.Stars

    private val categorySelect: TranslatedSelectBox
    private val sortInstalledSelect: TranslatedSelectBox
    private val sortOnlineSelect: TranslatedSelectBox

    init {
        sortInstalledSelect = TranslatedSelectBox(
            SortType.entries.filter { sort -> sort != SortType.Stars }.map { sort -> sort.label },
            sortInstalled.label
        )
        sortInstalledSelect.onChange {
            sortInstalled = SortType.fromSelectBox(sortInstalledSelect)
            modManagementScreen.refreshInstalledModTable()
        }

        sortOnlineSelect = TranslatedSelectBox(
            SortType.entries.map { sort -> sort.label },
            sortOnline.label
        )
        sortOnlineSelect.onChange {
            sortOnline = SortType.fromSelectBox(sortOnlineSelect)
            modManagementScreen.refreshOnlineModTable()
        }

        categorySelect = TranslatedSelectBox(
            ModCategories.asSequence().map { it.label }.toList(),
            category.label
        )
        categorySelect.onChange {
            category = ModCategories.fromSelectBox(categorySelect)
            modManagementScreen.refreshInstalledModTable()
            modManagementScreen.refreshOnlineModTable()
        }

    }

    fun openPopup() {
        val popup = Popup(modManagementScreen)
        val width = (modManagementScreen.stage.width - 80f).coerceAtMost(540f)
        popup.add("Sort and Filter".toLabel(fontSize = 24)).padBottom(16f).row()
        for ((label, select) in listOf(
            "Category:" to categorySelect,
            "Sort Current:" to sortInstalledSelect,
            "Sort Downloadable:" to sortOnlineSelect
        )) {
            popup.add(ModManagementStyle.label(label, 20)).width(width).left().padBottom(6f).row()
            popup.add(select).width(width).minHeight(56f).padBottom(16f).row()
        }
        popup.addCloseButton()
        popup.open()
    }
}
