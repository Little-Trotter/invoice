package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardedRowsTest {

    private var guardCalls = 0
    private val rows = mutableListOf("a", "b")

    private fun guarded(locked: Boolean = false) = GuardedRows(rows) {
        guardCalls++
        check(!locked) { "locked" }
    }

    @Test
    fun `every change runs the guard first, a read does not`() {
        val book = guarded()

        assertEquals(listOf("a", "b"), book.items)
        assertEquals(0, guardCalls)

        book.add("c")
        book.replaceAll(listOf("x"))
        assertTrue(book.removeIf { it == "x" })
        assertEquals(3, guardCalls)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a locked owner rejects the change and keeps its rows`() {
        val book = guarded(locked = true)

        assertThrows(IllegalStateException::class.java) { book.add("c") }
        assertThrows(IllegalStateException::class.java) { book.replaceAll(emptyList()) }
        assertThrows(IllegalStateException::class.java) { book.removeIf { true } }
        assertEquals(listOf("a", "b"), rows)
    }

    @Test
    fun `compose appends without asking the guard`() {
        guarded(locked = true).compose(listOf("c"))

        assertEquals(listOf("a", "b", "c"), rows)
        assertFalse(guardCalls > 0)
    }
}
