package `is`.siggi.nextpost.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameCodeGeneratorTest {

    @Test
    fun `generated code is six characters`() {
        val code = GameCodeGenerator.generate()
        assertEquals(6, code.length)
    }

    @Test
    fun `generated code uses only the ambiguity-free alphabet`() {
        repeat(200) {
            val code = GameCodeGenerator.generate()
            assertTrue(code.all { it in GameCodeGenerator.ALPHABET })
        }
    }

    @Test
    fun `alphabet excludes the ambiguous characters`() {
        // Section 7: I, O, 0, 1 are excluded — a code read aloud or handwritten must never
        // get mistyped into a different one.
        assertFalse('I' in GameCodeGenerator.ALPHABET)
        assertFalse('O' in GameCodeGenerator.ALPHABET)
        assertFalse('0' in GameCodeGenerator.ALPHABET)
        assertFalse('1' in GameCodeGenerator.ALPHABET)
    }

    @Test
    fun `generation is deterministic for a seeded random source`() {
        val first = GameCodeGenerator.generate(Random(42))
        val second = GameCodeGenerator.generate(Random(42))
        assertEquals(first, second)
    }

    @Test
    fun `normalize uppercases and strips whitespace`() {
        assertEquals("R7K2QM", GameCodeGenerator.normalize(" r7k 2qm "))
    }

    @Test
    fun `normalize is idempotent on an already-normalized code`() {
        val code = GameCodeGenerator.generate()
        assertEquals(code, GameCodeGenerator.normalize(code))
    }
}
