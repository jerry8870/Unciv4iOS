package com.unciv.logic

import com.unciv.models.ruleset.Ruleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class GameInfoTransientStateTest {
    @Test
    fun rulesetCanBeQueriedWhileTransientsAreBeingRestored() {
        val gameInfo = GameInfo()

        assertNull(gameInfo.getRulesetOrNull())

        val ruleset = Ruleset()
        gameInfo.ruleset = ruleset
        assertSame(ruleset, gameInfo.getRulesetOrNull())
    }

    @Test
    fun pendingGameCloneCanRestoreItsRuleset() {
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        testGame.gameInfo.currentPlayer = player.civID
        val pendingGameClone = testGame.gameInfo.clone()
        assertNull(pendingGameClone.getRulesetOrNull())

        pendingGameClone.setTransients()

        assertNotNull(pendingGameClone.getRulesetOrNull())
    }
}
