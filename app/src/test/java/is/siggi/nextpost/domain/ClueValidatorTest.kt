package `is`.siggi.nextpost.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClueValidatorTest {

    @Test
    fun `three valid clues satisfy the minimum`() {
        val result = ClueValidator.validate(listOf("first clue", "second clue", "third clue"))
        assertTrue(result.isValid)
        assertEquals(3, result.nonBlankCount)
        assertNull(result.firstBlankIndex)
    }

    @Test
    fun `three clues with one blank do not satisfy the minimum`() {
        val result = ClueValidator.validate(listOf("first clue", "", "third clue"))
        assertFalse(result.isValid)
        assertEquals(2, result.nonBlankCount)
        assertEquals(1, result.firstBlankIndex)
    }

    @Test
    fun `three clues with one whitespace-only entry do not satisfy the minimum`() {
        val result = ClueValidator.validate(listOf("first clue", "   ", "third clue"))
        assertFalse(result.isValid)
        assertEquals(2, result.nonBlankCount)
        assertEquals(1, result.firstBlankIndex)
    }

    @Test
    fun `fewer than three clues do not satisfy the minimum`() {
        val result = ClueValidator.validate(listOf("first clue", "second clue"))
        assertFalse(result.isValid)
        assertEquals(2, result.nonBlankCount)
        assertNull(result.firstBlankIndex)
    }

    @Test
    fun `exactly three clues remain after trimming and sanitizing`() {
        val raw = listOf("  first clue  ", " ", "second clue", "third clue  ")
        val sanitized = ClueValidator.sanitize(raw)
        assertEquals(listOf("first clue", "second clue", "third clue"), sanitized)

        val result = ClueValidator.validate(sanitized)
        assertTrue(result.isValid)
        assertEquals(3, result.nonBlankCount)
        assertNull(result.firstBlankIndex)
    }

    @Test
    fun `the cap is ten, per section 4`() {
        // Not a validate() case — MAX_CLUES_PER_POST gates Add clue and publish separately
        // (ClueEditorScreen, CreateGameViewModel). This just pins the constant itself.
        assertEquals(10, ClueValidator.MAX_CLUES_PER_POST)
    }
}
