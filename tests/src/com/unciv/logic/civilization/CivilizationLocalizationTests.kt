package com.unciv.logic.civilization

import com.unciv.logic.GameInfo
import com.unciv.logic.MissingNationException
import com.unciv.models.ruleset.Ruleset
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CivilizationLocalizationTests {
    @Test
    fun missingNationUsesTheTranslatablePlaceholderForm() {
        val civilization = Civilization().apply {
            gameInfo = GameInfo().apply { ruleset = Ruleset() }
            setNameForUnitTests("Missing nation")
        }

        val exception = assertThrows(MissingNationException::class.java) {
            civilization.setNationTransient()
        }

        assertEquals("Nation [Missing nation] is not found!", exception.message)
    }
}
