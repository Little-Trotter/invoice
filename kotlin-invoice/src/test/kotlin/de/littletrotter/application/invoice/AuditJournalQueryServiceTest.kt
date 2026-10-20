package de.littletrotter.application.invoice

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.repository.InvoiceAuditActionGroup
import de.littletrotter.domain.repository.InvoiceAuditActionFilter
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.GermanTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.application.dto.JournalGlobalFilter
import de.littletrotter.application.dto.InvoiceAuditActionLine

/** [AuditJournalQueryService]'s filter-to-query mapping AND its own two-query
 *  assembly ([findActionsByInvoice]/[findGlobalActions] load a page of [InvoiceAuditActionGroup]s,
 *  then that page's technical rows in ONE further [InvoiceAuditJournalRepository.findByBusinessTx]
 *  call, zipped back onto each group by its own `business_tx` — never a per-line query). Verified
 *  against a mocked repository so the exact parameters crossing that boundary are pinned, not just
 *  the returned list. */
class AuditJournalQueryServiceTest {

    private val repository: InvoiceAuditJournalRepository = mock()
    private val receiptRepository: ReceiptRepository = mock()
    private val recipientRepository: RecipientRepository = mock()
    private val invoiceLookup: InvoiceLookupRepository = mock()
    private val service = AuditJournalQueryService(repository, receiptRepository, recipientRepository, invoiceLookup)

    private fun group(businessTx: Long) = InvoiceAuditActionGroup(
        businessTx = businessTx, action = InvoiceAuditAction.HEADER_CHANGED,
        actor = "oidc-sub-123", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        minSeq = businessTx, maxSeq = businessTx, rowCount = 1L,
    )

    private fun rowFor(businessTx: Long, invoiceId: Long = 42L) = InvoiceAuditJournalEntry(
        seq = businessTx, businessTx = businessTx, invoiceId = invoiceId, invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-123", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.HEADER_CHANGED,
        detail = """{"free_text": {"old": "a", "new": "b"}}""",
        documentSha256 = null, prevHash = "genesis", entryHash = "hash-$businessTx",
    )

    // ── findActionsByInvoice / countActionsByInvoice ────────────────────────────

    @Test
    fun `findActionsByInvoice delegates to findActionGroupsByInvoiceId, newest first by default`() {
        whenever(repository.findActionGroupsByInvoiceId(42L, 1, 20, SortDirection.DESC)).thenReturn(listOf(group(1L)))
        whenever(repository.findByBusinessTx(listOf(1L), 42L)).thenReturn(listOf(rowFor(1L)))

        val result = service.findActionsByInvoice(42L, 1, 20)

        assertEquals(listOf(1L), result.map { it.businessTx })
    }

    @Test
    fun `findActionsByInvoice forwards an explicit direction unchanged`() {
        whenever(repository.findActionGroupsByInvoiceId(42L, 0, 20, SortDirection.ASC)).thenReturn(listOf(group(1L)))
        whenever(repository.findByBusinessTx(listOf(1L), 42L)).thenReturn(listOf(rowFor(1L)))

        service.findActionsByInvoice(42L, 0, 20, SortDirection.ASC)

        verify(repository).findActionGroupsByInvoiceId(42L, 0, 20, SortDirection.ASC)
    }

    @Test
    fun `findActionsByInvoice scopes the second query to the invoice id, never loads the other side of a cancellation`() {
        whenever(repository.findActionGroupsByInvoiceId(42L, 0, 20, SortDirection.DESC)).thenReturn(listOf(group(1L)))
        whenever(repository.findByBusinessTx(listOf(1L), 42L)).thenReturn(listOf(rowFor(1L, invoiceId = 42L)))

        service.findActionsByInvoice(42L, 0, 20)

        verify(repository).findByBusinessTx(listOf(1L), 42L)
    }

    @Test
    fun `an empty page of groups never calls the second query at all`() {
        whenever(repository.findActionGroupsByInvoiceId(42L, 0, 20, SortDirection.DESC)).thenReturn(emptyList())

        val result = service.findActionsByInvoice(42L, 0, 20)

        assertTrue(result.isEmpty())
        verify(repository, org.mockito.kotlin.never()).findByBusinessTx(anyOrNull(), anyOrNull())
    }

    @Test
    fun `countActionsByInvoice delegates straight to countActionGroupsByInvoiceId`() {
        whenever(repository.countActionGroupsByInvoiceId(42L)).thenReturn(7L)

        assertEquals(7L, service.countActionsByInvoice(42L))
    }

    // ── findGlobalActions / countGlobalActions — filter normalization ──────────

    @Test
    fun `blank invoice number and actor are normalized to null, not passed through as blank strings`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter(invoiceNumber = "   ", actor = ""), 0, 25)

        verify(repository).findActionGroupsPage(0, 25, InvoiceAuditActionFilter.EMPTY, SortDirection.DESC)
    }

    @Test
    fun `untrimmed invoice number and actor are trimmed before reaching the repository`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter(invoiceNumber = "  RE-2026-0001  ", actor = " alice "), 0, 25)

        verify(repository).findActionGroupsPage(0, 25, InvoiceAuditActionFilter(invoiceNumber = "RE-2026-0001", actor = "alice"), SortDirection.DESC)
    }

    @Test
    fun `the action filter passes through unchanged`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter(actions = setOf(InvoiceAuditAction.CANCELLATION)), 0, 25)

        verify(repository).findActionGroupsPage(0, 25, InvoiceAuditActionFilter(actions = setOf(InvoiceAuditAction.CANCELLATION)), SortDirection.DESC)
    }

    @Test
    fun `occurredFrom becomes that day's start (inclusive)`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter(occurredFrom = LocalDate.of(2026, 9, 4)), 0, 25)

        val expectedFrom = LocalDate.of(2026, 9, 4).atStartOfDay(GermanTime.ZONE).toOffsetDateTime()
        verify(repository).findActionGroupsPage(0, 25, InvoiceAuditActionFilter(occurredFrom = expectedFrom), SortDirection.DESC)
    }

    @Test
    fun `occurredTo becomes the START OF THE DAY AFTER (exclusive upper bound), not that day's own start`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter(occurredTo = LocalDate.of(2026, 9, 4)), 0, 25)

        val expectedToExclusive = LocalDate.of(2026, 9, 5).atStartOfDay(GermanTime.ZONE).toOffsetDateTime()
        verify(repository).findActionGroupsPage(0, 25, InvoiceAuditActionFilter(occurredTo = expectedToExclusive), SortDirection.DESC)
    }

    @Test
    fun `countGlobalActions applies the same normalization as findGlobalActions`() {
        whenever(repository.countActionGroupsPage(any())).thenReturn(0L)

        service.countGlobalActions(JournalGlobalFilter(invoiceNumber = "  ", actor = "  bob  "))

        verify(repository).countActionGroupsPage(InvoiceAuditActionFilter(actor = "bob"))
    }

    @Test
    fun `pageIndex and pageSize are forwarded unchanged`() {
        whenever(repository.findActionGroupsPage(eq(3), eq(10), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter.EMPTY, pageIndex = 3, pageSize = 10)

        verify(repository).findActionGroupsPage(3, 10, InvoiceAuditActionFilter.EMPTY, SortDirection.DESC)
    }

    @Test
    fun `the direction passed to findGlobalActions is forwarded to the repository unchanged`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(emptyList())

        service.findGlobalActions(JournalGlobalFilter.EMPTY, pageIndex = 0, pageSize = 25, direction = SortDirection.ASC)

        verify(repository).findActionGroupsPage(0, 25, InvoiceAuditActionFilter.EMPTY, SortDirection.ASC)
    }

    @Test
    fun `findGlobalActions loads every page group's technical rows in one findByBusinessTx call, unscoped by invoice`() {
        whenever(repository.findActionGroupsPage(eq(0), eq(25), any(), any()))
            .thenReturn(listOf(group(1L), group(2L)))
        whenever(repository.findByBusinessTx(listOf(1L, 2L), null)).thenReturn(listOf(rowFor(1L), rowFor(2L)))

        val result = service.findGlobalActions(JournalGlobalFilter.EMPTY, 0, 25)

        assertEquals(setOf(1L, 2L), result.map { it.businessTx }.toSet())
        verify(repository).findByBusinessTx(listOf(1L, 2L), null)
    }

    // ── Batched name resolution the panels call instead of touching a repository ────────────────

    private fun lineWith(action: InvoiceAuditAction, detail: String, event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED) =
        InvoiceAuditActionLine(
            businessTx = 1L, action = action, actor = "oidc-sub-123", actorName = "alice",
            occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
            invoiceIds = listOf(42L), invoiceNumbers = listOf("RE-2026-0001"),
            rows = listOf(rowFor(1L).copy(action = action, event = event, detail = detail)),
        )

    @Test
    fun `recipientNames resolves only HEADER_CHANGED-RECIPIENT_CHANGED rows, in one batched call`() {
        val recipientLine = lineWith(InvoiceAuditAction.HEADER_CHANGED, """{"recipient_id": {"old": "5", "new": "8"}}""")
        val unrelatedLine = lineWith(InvoiceAuditAction.STATUS_CHANGED, """{"status": {"old": "0", "new": "1"}}""")
        whenever(recipientRepository.findNamesByIds(setOf(5L, 8L))).thenReturn(mapOf(5L to "Alt AG", 8L to "Neu GmbH"))

        val names = service.recipientNames(listOf(recipientLine, unrelatedLine))

        assertEquals(mapOf(5L to "Alt AG", 8L to "Neu GmbH"), names)
        verify(recipientRepository).findNamesByIds(setOf(5L, 8L))
    }

    @Test
    fun `receiptFileNames resolves only RECEIPT_ASSIGNED-RECEIPT_DETACHED rows, in one batched call`() {
        val receiptLine = lineWith(
            InvoiceAuditAction.RECEIPT_ASSIGNED, """{"invoice_id": "42", "receipt_id": "9"}""",
            event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED,
        )
        val unrelatedLine = lineWith(InvoiceAuditAction.STATUS_CHANGED, """{"status": {"old": "0", "new": "1"}}""")
        whenever(receiptRepository.findFileNames(setOf(9L))).thenReturn(mapOf(9L to "tankquittung.pdf"))

        val names = service.receiptFileNames(listOf(receiptLine, unrelatedLine))

        assertEquals(mapOf(9L to "tankquittung.pdf"), names)
        verify(receiptRepository).findFileNames(setOf(9L))
    }

    @Test
    fun `no matching lines never calls the repository with a non-empty id set`() {
        service.recipientNames(emptyList())
        service.receiptFileNames(emptyList())
        service.itemPositions(emptyList())

        verify(recipientRepository).findNamesByIds(emptySet())
        verify(receiptRepository).findFileNames(emptySet())
        verify(invoiceLookup).findItemPositions(emptySet())
    }

    @Test
    fun `a group with no matching technical rows still yields a line, with an empty row list`() {
        whenever(repository.findActionGroupsByInvoiceId(42L, 0, 20, SortDirection.DESC))
            .thenReturn(listOf(group(1L), group(2L)))
        whenever(repository.findByBusinessTx(listOf(1L, 2L), 42L)).thenReturn(listOf(rowFor(1L)))

        val result = service.findActionsByInvoice(42L, 0, 20)

        assertEquals(listOf(1L), result.first { it.businessTx == 1L }.rows.map { it.businessTx })
        assertTrue(result.first { it.businessTx == 2L }.rows.isEmpty())
    }

    @Test
    fun `recipientNames also resolves RECIPIENT_CHANGED rows, not only HEADER_CHANGED`() {
        val recipientLine = lineWith(InvoiceAuditAction.RECIPIENT_CHANGED, """{"recipient_id": {"old": "5", "new": "8"}}""")
        whenever(recipientRepository.findNamesByIds(setOf(5L, 8L))).thenReturn(mapOf(5L to "Alt AG", 8L to "Neu GmbH"))

        val names = service.recipientNames(listOf(recipientLine))

        assertEquals(mapOf(5L to "Alt AG", 8L to "Neu GmbH"), names)
    }

    @Test
    fun `recipientNames ignores a matching action whose row is not an _UPDATED event`() {
        val nonUpdateLine = lineWith(InvoiceAuditAction.HEADER_CHANGED, """{"recipient_id": "5"}""", event = InvoiceAuditEvent.INVOICE_CREATED)
        whenever(recipientRepository.findNamesByIds(emptySet())).thenReturn(emptyMap())

        service.recipientNames(listOf(nonUpdateLine))

        verify(recipientRepository).findNamesByIds(emptySet())
    }

    @Test
    fun `recipientNames tolerates malformed JSON, a missing column, a JSON null value and a non-numeric id`() {
        val malformed = lineWith(InvoiceAuditAction.HEADER_CHANGED, "{not json")
        val missingColumn = lineWith(InvoiceAuditAction.HEADER_CHANGED, """{"free_text": {"old": "a", "new": "b"}}""")
        val nullAndNonNumeric = lineWith(InvoiceAuditAction.HEADER_CHANGED, """{"recipient_id": {"old": null, "new": "not-a-number"}}""")
        whenever(recipientRepository.findNamesByIds(emptySet())).thenReturn(emptyMap())

        val names = service.recipientNames(listOf(malformed, missingColumn, nullAndNonNumeric))

        assertEquals(emptyMap<Long, String>(), names)
        verify(recipientRepository).findNamesByIds(emptySet())
    }

    @Test
    fun `receiptFileNames also resolves RECEIPT_DETACHED rows, not only RECEIPT_ASSIGNED`() {
        val detachedLine = lineWith(
            InvoiceAuditAction.RECEIPT_DETACHED, """{"invoice_id": "42", "receipt_id": "9"}""",
            event = InvoiceAuditEvent.INVOICE_RECEIPT_UNASSIGNED,
        )
        whenever(receiptRepository.findFileNames(setOf(9L))).thenReturn(mapOf(9L to "beleg.pdf"))

        val names = service.receiptFileNames(listOf(detachedLine))

        assertEquals(mapOf(9L to "beleg.pdf"), names)
    }

    @Test
    fun `receiptFileNames excludes a matching action whose row is shaped as an _UPDATED event`() {
        val diffShapedLine = lineWith(
            InvoiceAuditAction.RECEIPT_ASSIGNED, """{"receipt_id": {"old": "1", "new": "9"}}""",
            event = InvoiceAuditEvent.INVOICE_UPDATED,
        )
        whenever(receiptRepository.findFileNames(emptySet())).thenReturn(emptyMap())

        service.receiptFileNames(listOf(diffShapedLine))

        verify(receiptRepository).findFileNames(emptySet())
    }

    @Test
    fun `receiptFileNames tolerates malformed JSON, a missing column and a JSON null value`() {
        val malformed = lineWith(
            InvoiceAuditAction.RECEIPT_ASSIGNED, "{not json",
            event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED,
        )
        val missingColumn = lineWith(
            InvoiceAuditAction.RECEIPT_ASSIGNED, """{"invoice_id": "42"}""",
            event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED,
        )
        val nullValue = lineWith(
            InvoiceAuditAction.RECEIPT_ASSIGNED, """{"receipt_id": null}""",
            event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED,
        )
        whenever(receiptRepository.findFileNames(emptySet())).thenReturn(emptyMap())

        val names = service.receiptFileNames(listOf(malformed, missingColumn, nullValue))

        assertEquals(emptyMap<Long, String>(), names)
        verify(receiptRepository).findFileNames(emptySet())
    }

    @Test
    fun `itemPositions resolves only item-scope RECEIPT_ASSIGNED-RECEIPT_DETACHED rows, in one batched call`() {
        val itemLine = lineWith(
            InvoiceAuditAction.RECEIPT_DETACHED, """{"invoice_item_id": "5", "receipt_id": "9"}""",
            event = InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED,
        )
        val invoiceScopeLine = lineWith(
            InvoiceAuditAction.RECEIPT_ASSIGNED, """{"invoice_id": "1", "receipt_id": "9"}""",
            event = InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED,
        )
        val unrelatedLine = lineWith(InvoiceAuditAction.STATUS_CHANGED, """{"status": {"old": "0", "new": "1"}}""")
        whenever(invoiceLookup.findItemPositions(setOf(5L))).thenReturn(mapOf(5L to "3"))

        val positions = service.itemPositions(listOf(itemLine, invoiceScopeLine, unrelatedLine))

        assertEquals(mapOf(5L to "3"), positions)
        verify(invoiceLookup).findItemPositions(setOf(5L))
    }
}
