package de.littletrotter.domain.message

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** [SystemMessageFilter] is now the repository port's query parameter object too. */
class SystemMessageFilterTest {

    @Test
    fun `EMPTY restricts nothing`() {
        val empty = SystemMessageFilter.EMPTY

        assertTrue(empty.severity.isEmpty())
        assertTrue(empty.source.isEmpty())
        assertNull(empty.from)
        assertNull(empty.to)
        assertFalse(empty.unreadOnly)
        assertEquals(SystemMessageFilter(), empty)
    }

    @Test
    fun `boundary - multi-select sets keep every selected value`() {
        val filter = SystemMessageFilter(
            severity = setOf(SystemMessageSeverity.ERROR, SystemMessageSeverity.WARNING),
            source = setOf(SystemMessageSource.EXPORT, SystemMessageSource.UI),
        )

        assertEquals(2, filter.severity.size)
        assertEquals(setOf(SystemMessageSource.EXPORT, SystemMessageSource.UI), filter.source)
    }

    @Test
    fun `boundary - a single-instant window keeps identical from and to`() {
        val at = OffsetDateTime.parse("2026-09-23T10:00:00Z")

        val filter = SystemMessageFilter(from = at, to = at)

        assertEquals(filter.from, filter.to)
    }

    @Test
    fun `boundary - unreadOnly alone makes the filter differ from EMPTY`() {
        assertNotEquals(SystemMessageFilter.EMPTY, SystemMessageFilter(unreadOnly = true))
        assertEquals(SystemMessageFilter(unreadOnly = true), SystemMessageFilter.EMPTY.copy(unreadOnly = true))
    }
}
