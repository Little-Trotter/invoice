package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * the port's default arguments are part of its contract: a caller that omits them must
 * reach the adapter with "newest first" and "no filter", never with an accidental narrowing.
 * Pure unit test against a mocked port (no Quarkus, no DB).
 */
class InvoiceAuditJournalRepositoryTest {

    private val repository = mock<InvoiceAuditJournalRepository>()

    @Test
    fun `happy path - omitted direction defaults to newest first for both per-invoice reads`() {
        repository.findByInvoiceId(invoiceId = 7, pageIndex = 0, pageSize = 20)
        repository.findActionGroupsByInvoiceId(invoiceId = 7, pageIndex = 1, pageSize = 20)

        verify(repository).findByInvoiceId(7, 0, 20, SortDirection.DESC)
        verify(repository).findActionGroupsByInvoiceId(7, 1, 20, SortDirection.DESC)
    }

    @Test
    fun `boundary - an unfiltered row page and its count bind no filter at all`() {
        repository.findPage(pageIndex = 0, pageSize = 50)
        repository.countPage()

        verify(repository).findPage(0, 50, InvoiceAuditRowFilter.EMPTY, SortDirection.DESC)
        verify(repository).countPage(InvoiceAuditRowFilter.EMPTY)
    }

    @Test
    fun `boundary - an unfiltered action-group page and its count bind an empty action allow-list`() {
        repository.findActionGroupsPage(pageIndex = 2, pageSize = 10)
        repository.countActionGroupsPage()

        verify(repository).findActionGroupsPage(2, 10, InvoiceAuditActionFilter.EMPTY, SortDirection.DESC)
        verify(repository).countActionGroupsPage(InvoiceAuditActionFilter.EMPTY)
    }

    @Test
    fun `boundary - a partial date window keeps the omitted bound open and the given ones unchanged`() {
        val from = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val actions = setOf(InvoiceAuditAction.CANCELLATION)

        val actionFilter = InvoiceAuditActionFilter(actions = actions, occurredFrom = from)
        val rowFilter = InvoiceAuditRowFilter(actor = "sub-1", occurredFrom = from)
        repository.findActionGroupsPage(pageIndex = 0, pageSize = 10, filter = actionFilter)
        repository.countActionGroupsPage(actionFilter)
        repository.findPage(pageIndex = 0, pageSize = 10, filter = rowFilter, direction = SortDirection.ASC)
        repository.countPage(rowFilter)

        verify(repository).findActionGroupsPage(0, 10, InvoiceAuditActionFilter(actions, null, null, from, null), SortDirection.DESC)
        verify(repository).countActionGroupsPage(InvoiceAuditActionFilter(actions, null, null, from, null))
        verify(repository).findPage(0, 10, InvoiceAuditRowFilter(null, "sub-1", null, from, null), SortDirection.ASC)
        verify(repository).countPage(InvoiceAuditRowFilter(null, "sub-1", null, from, null))
    }

    @Test
    fun `boundary - an unscoped business_tx lookup never narrows to one invoice`() {
        repository.findByBusinessTx(listOf(11L, 12L))

        verify(repository).findByBusinessTx(listOf(11L, 12L), null)
    }

    @Test
    fun `boundary - two action groups differing only in row count are distinct values`() {
        val occurredAt = OffsetDateTime.of(2026, 9, 22, 8, 0, 0, 0, ZoneOffset.UTC)
        val group = InvoiceAuditActionGroup(
            businessTx = 99,
            action = InvoiceAuditAction.CANCELLATION,
            actor = "sub-1",
            actorName = null,
            occurredAt = occurredAt,
            minSeq = 10,
            maxSeq = 12,
            rowCount = 3,
        )

        assertEquals(99L, group.businessTx)
        assertEquals(InvoiceAuditAction.CANCELLATION, group.action)
        assertEquals("sub-1", group.actor)
        assertNull(group.actorName)
        assertEquals(occurredAt, group.occurredAt)
        assertEquals(10L to 12L, group.minSeq to group.maxSeq)
        assertNotEquals(group, group.copy(rowCount = 2))
    }
}
