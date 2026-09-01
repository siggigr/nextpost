package `is`.siggi.nextpost.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreCalculatorTest {

    @Test
    fun `three clues, none opened, scores 100`() {
        assertEquals(100.0, ScoreCalculator.scoreForPost(3, 0), 0.001)
    }

    @Test
    fun `three clues, one opened, scores 50`() {
        assertEquals(50.0, ScoreCalculator.scoreForPost(3, 1), 0.001)
    }

    @Test
    fun `three clues, both extras opened, scores 10`() {
        assertEquals(10.0, ScoreCalculator.scoreForPost(3, 2), 0.001)
    }

    @Test
    fun `five clues, one opened, scores 75`() {
        assertEquals(75.0, ScoreCalculator.scoreForPost(5, 1), 0.001)
    }

    @Test
    fun `five clues, all extras opened, hits the floor`() {
        assertEquals(10.0, ScoreCalculator.scoreForPost(5, 4), 0.001)
    }

    @Test
    fun `eight clues, none opened, scores 100`() {
        assertEquals(100.0, ScoreCalculator.scoreForPost(8, 0), 0.001)
    }

    @Test
    fun `eight clues, two opened, scores 71point4`() {
        assertEquals(71.4, ScoreCalculator.scoreForPost(8, 2), 0.05)
    }

    @Test
    fun `eight clues, all extras opened, scores 10`() {
        assertEquals(10.0, ScoreCalculator.scoreForPost(8, 7), 0.001)
    }

    @Test
    fun `the floor is never breached however many clues are opened`() {
        assertEquals(10.0, ScoreCalculator.scoreForPost(3, 99), 0.001)
    }

    /**
     * Section 4: the reason for the 10-clue cap is that, without it, opening a clue in the
     * final ~10% of a long list costs nothing — a flat zone this test would have caught. For
     * every valid clue count, the score must strictly decrease with each clue opened until the
     * floor, and the floor must be reached only on the final clue.
     */
    @Test
    fun `score strictly decreases per clue until the floor, reached only on the last clue`() {
        for (totalClues in ClueValidator.MIN_CLUES_PER_SCORED_POST..ClueValidator.MAX_CLUES_PER_POST) {
            var previous = ScoreCalculator.scoreForPost(totalClues, 0)
            assertEquals(ScoreCalculator.MAX_POINTS, previous, 0.001)

            var floorFirstReachedAt = -1
            for (extraOpened in 1 until totalClues) {
                val current = ScoreCalculator.scoreForPost(totalClues, extraOpened)
                if (floorFirstReachedAt == -1) {
                    assertTrue(
                        "clues=$totalClues opened=$extraOpened should strictly decrease from $previous",
                        current < previous
                    )
                    if (current == ScoreCalculator.MIN_POINTS) floorFirstReachedAt = extraOpened
                } else {
                    assertEquals(ScoreCalculator.MIN_POINTS, current, 0.001)
                }
                previous = current
            }

            assertEquals(
                "clues=$totalClues should hit the floor only on the final extra clue",
                totalClues - 1,
                floorFirstReachedAt
            )
        }
    }

    @Test
    fun `formatScore trims a trailing zero but keeps one decimal otherwise`() {
        assertEquals("75", ScoreCalculator.formatScore(75.0))
        assertEquals("10", ScoreCalculator.formatScore(10.0))
        assertEquals("71.4", ScoreCalculator.formatScore(71.42857))
        assertEquals("88.9", ScoreCalculator.formatScore(ScoreCalculator.scoreForPost(10, 1)))
    }
}
