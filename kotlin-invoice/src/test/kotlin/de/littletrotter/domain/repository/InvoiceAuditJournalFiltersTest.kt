package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The journal port's query parameter objects: defaults mean "no restriction", every
 *  field is part of the value. */
class InvoiceAuditJournalFiltersTest {

    private val from = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    private val to = from.plusDays(1)

    @Test
    fun `row filter EMPTY restricts nothing`() {
        val empty = InvoiceAuditRowFilter.EMPTY

        assertNull(empty.invoiceNumber)
        assertNull(empty.actor)
        assertNull(empty.event)
        assertNull(empty.occurredFrom)
        assertNull(empty.occurredTo)
        assertEquals(InvoiceAuditRowFilter(), empty)
    }

    @Test
    fun `action filter EMPTY restricts nothing`() {
        val empty = InvoiceAuditActionFilter.EMPTY

        assertTrue(empty.actions.isEmpty())
        assertNull(empty.invoiceNumber)
        assertNull(empty.actor)
        assertNull(empty.occurredFrom)
        assertNull(empty.occurredTo)
        assertEquals(InvoiceAuditActionFilter(), empty)
    }

    @Test
    fun `boundary - a fully populated row filter keeps every field`() {
        val filter = InvoiceAuditRowFilter("RE-2026-0001", "sub-1", InvoiceAuditEvent.INVOICE_UPDATED, from, to)

        assertEquals("RE-2026-0001", filter.invoiceNumber)
        assertEquals("sub-1", filter.actor)
        assertEquals(InvoiceAuditEvent.INVOICE_UPDATED, filter.event)
        assertEquals(from, filter.occurredFrom)
        assertEquals(to, filter.occurredTo)
    }

    @Test
    fun `boundary - a half-open date window keeps the omitted bound null`() {
        val onlyFrom = InvoiceAuditActionFilter(occurredFrom = from)
        val onlyTo = InvoiceAuditRowFilter(occurredTo = to)

        assertNull(onlyFrom.occurredTo)
        assertNull(onlyTo.occurredFrom)
        assertNotEquals(InvoiceAuditActionFilter.EMPTY, onlyFrom)
        assertNotEquals(InvoiceAuditRowFilter.EMPTY, onlyTo)
    }

    @Test
    fun `boundary - the action allow-list is compared as a set, not by insertion order`() {
        val a = InvoiceAuditActionFilter(actions = linkedSetOf(InvoiceAuditAction.STORNO, InvoiceAuditAction.ITEM_ADDED))
        val b = InvoiceAuditActionFilter(actions = linkedSetOf(InvoiceAuditAction.ITEM_ADDED, InvoiceAuditAction.STORNO))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(actions = setOf(InvoiceAuditAction.STORNO)))
    }

    @Test
    fun `boundary - an empty-string actor is a real value, distinct from no filter`() {
        assertNotEquals(InvoiceAuditRowFilter.EMPTY, InvoiceAuditRowFilter(actor = ""))
        assertNotEquals(InvoiceAuditActionFilter.EMPTY, InvoiceAuditActionFilter(invoiceNumber = ""))
    }
}
