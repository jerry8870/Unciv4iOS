package com.unciv.logic.automation.civilization

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm.AStarPathfinding
import com.unciv.testing.TestGame
import com.unciv.testing.TestRunnerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.runners.Parameterized.UseParametersRunnerFactory

@RunWith(Parameterized::class)
@UseParametersRunnerFactory(TestRunnerFactory::class)
class NextTurnAutomationTest(private val algorithm: PathfindingAlgorithm) {
    companion object {
        @Suppress("unused")
        @Parameters
        @JvmStatic
        fun parameters() = TestRunnerFactory.Parameters.pathfinding
    }

    private lateinit var civInfo: Civilization

    val testGame = TestGame()

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = (algorithm == AStarPathfinding)
        testGame.makeHexagonalMap(7)
        civInfo = testGame.addCiv()
        val capital = testGame.addCity(civInfo, testGame.tileMap[0,0])
        assertTrue(capital.isCapital())
    }

    @Test
    fun `automateUnits skips workers captured while founding a city`() {
        val game = TestGame()
        UncivGame.Current.settings.useAStarPathfinding = (algorithm == AStarPathfinding)
        game.makeHexagonalMap(4)
        game.setDifficulty("Chieftain") // Barbarians must leave newly claimed tiles before turn 60.
        val cityState = game.addCiv(cityStateType = "Cultured") // Settles its initial tile.
        val barbarians = game.addBarbarianCiv()
        val settler = game.addUnit("Settler", cityState, game.tileMap[0, 0])
        val worker = game.addUnit("Worker", cityState, game.tileMap[-2, -2])
        val brute = game.addUnit("Brute", barbarians, game.tileMap[-1, -1])
        // Founding the city expels the brute onto the worker's tile and captures it mid-turn.
        for (tile in brute.currentTile.neighbors) {
            if (tile != settler.currentTile && tile != worker.currentTile)
                game.setTileTerrain(tile.position, "Mountain")
        }

        NextTurnAutomation.automateCivMoves(cityState, tradeAndChangeState = false)

        assertEquals(1, cityState.cities.size)
        assertTrue(settler.isDestroyed)
        assertSame(barbarians, worker.civ)
        assertSame(worker.currentTile, brute.currentTile)
        assertEquals(0f, worker.currentMovement)
        assertTrue(cityState.units.getCivUnits().none { it == worker })
    }

    @Test
    fun `automateSettlerEscorting replaces low hp escort`() {
        val settler = testGame.addUnit("Settler", civInfo, testGame.tileMap[0,2])
        val highHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,1])
        val lowHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,2])
        lowHpWarrior.takeDamage(90)
        lowHpWarrior.startEscorting()
        assertTrue(settler.isEscorting())

        // Act
        NextTurnAutomation.automateSettlerEscorting(civInfo)
        assertEquals("settler should not have moved, else test is invalid", testGame.tileMap[0,2], settler.currentTile)

        // Assert
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,2], highHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,1], lowHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", highHpWarrior, settler.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", settler, highHpWarrior.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", null, lowHpWarrior.getOtherEscortUnit())
    }

    @Test
    fun `automateSettlerEscorting replaces low hp escort even with no movement`() {
        // AStar fails this because UnitMovement#canUnitSwapToReachableTile
        // calls escortedUnit.movement.canMoveTo(includeOtherEscortUnit = false) 
        // and escortedUnit.movement.canUnitSwapToReachableTile(checkEscorted = false)
        // but AStar assumes includeOtherEscortUnit always true.
        val settler1 = testGame.addUnit("Settler", civInfo, testGame.tileMap[0,2])
        val settler2 = testGame.addUnit("Settler", civInfo, testGame.tileMap[0,1])
        val highHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,1])
        val lowHpWarrior = testGame.addUnit("Warrior", civInfo, testGame.tileMap[0,2])
        lowHpWarrior.takeDamage(90)
        lowHpWarrior.startEscorting()
        settler2.currentMovement = 0f
        assertEquals(lowHpWarrior, settler1.getOtherEscortUnit())
        assertEquals(highHpWarrior, settler2.getOtherEscortUnit())

        // Act
        NextTurnAutomation.automateSettlerEscorting(civInfo)
        assertEquals("settlers should not have moved, else test is invalid", testGame.tileMap[0,2], settler1.currentTile)
        assertEquals("settlers should not have moved, else test is invalid", testGame.tileMap[0,1], settler2.currentTile)

        // Assert
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,2], highHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", testGame.tileMap[0,1], lowHpWarrior.currentTile)
        assertEquals("high hp warrior have taken the place of low hp escort of settler", highHpWarrior, settler1.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", settler1, highHpWarrior.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", lowHpWarrior, settler2.getOtherEscortUnit())
        assertEquals("high hp warrior have taken the place of low hp escort of settler", settler2, lowHpWarrior.getOtherEscortUnit())
    }
}
