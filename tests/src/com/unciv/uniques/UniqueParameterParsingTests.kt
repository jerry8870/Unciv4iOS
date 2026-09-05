package com.unciv.uniques

import com.unciv.models.ruleset.unique.Unique
import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueParameterParsingTests {
    @Test
    fun integerParametersDoNotRetainALeadingPlus() {
        assertEquals("1", Unique("[+1] to Fertility for Map Generation").params.single())
    }

    @Test
    fun statParametersRetainTheirLeadingPlus() {
        assertEquals("+1 Food", Unique("[+1 Food]").params.single())
    }
}
