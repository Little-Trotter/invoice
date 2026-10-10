package de.littletrotter.domain.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** N3 — Testfälle N3-07/08 für [ShortHash]. */
class ShortHashTest {

    @Test
    fun `N3-08 - the alphabet excludes the confusable characters`() {
        val alphabet = (0 until 200).map { ShortHash.random() }.joinToString("")
        ShortHash.excludedCharacters.forEach { excluded ->
            assertFalse(alphabet.contains(excluded), "'$excluded' darf im Kurzname nicht vorkommen")
        }
    }

    @Test
    fun `every generated value has the fixed length`() {
        repeat(50) { assertEquals(ShortHash.LENGTH, ShortHash.random().length) }
    }

    @Test
    fun `randomExcept never returns a value from the given set`() {
        val taken = (1..20).map { ShortHash.random() }.toSet()
        val next = ShortHash.randomExcept(taken)
        assertTrue(next !in taken)
        assertEquals(ShortHash.LENGTH, next.length)
    }
}
