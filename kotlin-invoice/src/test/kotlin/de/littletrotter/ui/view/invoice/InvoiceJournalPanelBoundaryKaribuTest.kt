package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getItemDetailsComponent
import com.vaadin.flow.component.UI
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/** Covers [InvoiceJournalPanel] paging fallbacks, item-details rendering and attach cycles. */
class InvoiceJournalPanelBoundaryKaribuTest {

    private val service: AuditJournalQueryService = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun row(seq: Long) = InvoiceAuditJournalEntry(
        seq = seq, businessTx = seq, invoiceId = 1L, invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-01T10:00:00Z"),
        event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.HEADER_CHANGED,
        detail = """{"free_text": {"old": "vorher", "new": "nachher"}}""",
        documentSha256 = null, prevHash = "prev-$seq", entryHash = "hash-$seq",
    )

    private fun line(seq: Long) = InvoiceAuditActionLine(
        businessTx = seq, action = InvoiceAuditAction.HEADER_CHANGED, actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-01T10:00:00Z"),
        invoiceIds = listOf(1L), invoiceNumbers = listOf("RE-2026-0001"),
        rows = listOf(row(seq), row(seq + 1)),
    )

    private fun panel(lines: List<InvoiceAuditActionLine>): InvoiceJournalPanel {
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(lines)
        whenever(service.countActionsByInvoice(1L)).thenReturn(lines.size.toLong())
        return InvoiceJournalPanel(service).apply { render(1L) }
    }

    @Test
    fun `happy path - the item details of an action line render every technical row with its field summary`() {
        val journal = panel(listOf(line(1)))
        val item = journal.grid.dataProvider.fetch(Query()).toList().single()

        val details = journal.grid._getItemDetailsComponent(item)

        val rows = details.children.toList()
        assertEquals(2, rows.size, "one technical row per journal entry of the action")
        assertTrue(rows.all { it.element.classList.contains("journal-technical-row") })
    }

    @Test
    fun `boundary - a query without a limit falls back to the page size and page zero`() {
        val journal = panel(emptyList())

        journal.grid.dataProvider.fetch(Query(0, 0, emptyList(), null, null)).toList()

        verify(service).findActionsByInvoice(1L, 0, 20, SortDirection.DESC)
    }

    @Test
    fun `boundary - an offset of two full pages asks the service for page index two`() {
        val journal = panel(emptyList())

        journal.grid.dataProvider.fetch(Query(40, 20, emptyList(), null, null)).toList()

        verify(service).findActionsByInvoice(1L, 2, 20, SortDirection.DESC)
    }

    @Test
    fun `boundary - detaching and re-attaching the panel registers the viewport fit script again`() {
        val journal = panel(emptyList())
        val ui = UI.getCurrent()

        ui.add(journal)
        ui.remove(journal)
        ui.add(journal)

        assertEquals(2, journal.viewportFitRegistrations, "every attach after a detach re-registers the script")
    }
}
