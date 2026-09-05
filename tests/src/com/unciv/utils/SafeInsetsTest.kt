package com.unciv.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeInsetsTest {
    @Test
    fun zeroInsetsKeepTheEntireScreen() {
        assertEquals(SafeArea(0, 0, 852, 393), SafeInsets().applyTo(852, 393))
    }

    @Test
    fun landscapeInsetsProduceBottomLeftViewportBounds() {
        val insets = SafeInsets(left = 59, top = 0, right = 59, bottom = 21)

        assertEquals(SafeArea(59, 21, 734, 372), insets.applyTo(852, 393))
    }
}
