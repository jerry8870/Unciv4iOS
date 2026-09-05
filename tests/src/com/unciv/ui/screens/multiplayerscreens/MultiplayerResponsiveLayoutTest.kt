package com.unciv.ui.screens.multiplayerscreens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplayerResponsiveLayoutTest {
    @Test
    fun keepsSectionsInOneRowOnlyWhenTranslatedWidthsFit() {
        assertTrue(fitsInOneRow(500f, listOf(210f, 280f), 10f))
        assertFalse(fitsInOneRow(499f, listOf(210f, 280f), 10f))
    }

    @Test
    fun choosesThreeColumnsWhenAllActionsFit() {
        assertEquals(
            3,
            responsiveActionColumns(420f, listOf(120f, 100f, 180f), 6f),
        )
    }

    @Test
    fun choosesTwoPlusOneWhenOnlyPairsFit() {
        assertEquals(
            2,
            responsiveActionColumns(250f, listOf(140f, 100f, 220f), 6f),
        )
    }

    @Test
    fun choosesSingleColumnWhenTranslatedPairsDoNotFit() {
        assertEquals(
            1,
            responsiveActionColumns(250f, listOf(180f, 140f, 120f), 6f),
        )
    }

    @Test
    fun checksEveryRowWhenChoosingAdvancedActionColumns() {
        assertEquals(
            2,
            responsiveActionColumns(260f, listOf(120f, 120f, 180f, 70f), 6f),
        )
    }
}
