package de.littletrotter.ui.view.system

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.application.dto.JournalGlobalFilter
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/** [JournalPanel], the global "Journal" tab under System: one line per business
 *  action across every invoice (paged, via the lazy [com.vaadin.flow.data.provider.DataProvider]),
 *  the filter fields map into [JournalGlobalFilter] (action multi-select replaces the earlier event
 *  filter), the result count and empty state state the outcome in words, and — same
 *  append-only requirement as [de.littletrotter.ui.view.invoice.InvoiceJournalPanelTest] — no
 *  edit/delete affordance ever appears. */
class JournalPanelTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun row(seq: Long, invoiceNumber: String? = "RE-2026-0001") = InvoiceAuditJournalEntry(
        seq = seq, businessTx = seq, invoiceId = 1L, invoiceNumber = invoiceNumber,
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        event = InvoiceAuditEvent.INVOICE_UPDATED, action = InvoiceAuditAction.HEADER_CHANGED,
        detail = """{"free_text": {"old": "a", "new": "b"}}""",
        documentSha256 = null, prevHash = "prev-$seq", entryHash = "hash-$seq",
    )

    private fun line(seq: Long, invoiceNumber: String? = "RE-2026-0001") = InvoiceAuditActionLine(
        businessTx = seq, action = InvoiceAuditAction.HEADER_CHANGED, actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        invoiceIds = listOf(1L), invoiceNumbers = listOf(invoiceNumber),
        rows = listOf(row(seq, invoiceNumber)),
    )

    private fun buildPanel(lines: List<InvoiceAuditActionLine> = emptyList()): Pair<JournalPanel, AuditJournalQueryService> {
        val service: AuditJournalQueryService = mock()
        whenever(service.findGlobalActions(any(), any(), any(), any())).thenReturn(lines)
        whenever(service.countGlobalActions(any())).thenReturn(lines.size.toLong())
        // Mockito's default answer for a Map-returning method is an empty map, no stubbing needed.
        return JournalPanel(service) to service
    }

    private fun renderedItems(panel: JournalPanel): List<InvoiceAuditActionLine> =
        panel.grid.dataProvider.fetch(Query()).toList()

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    @Test
    fun `renders actions across every invoice, not scoped to one`() {
        val (panel, _) = buildPanel(listOf(line(1, "RE-2026-0001"), line(2, "RE-2026-0002")))

        val items = renderedItems(panel)

        assertEquals(2, items.size)
        assertEquals(setOf("RE-2026-0001", "RE-2026-0002"), items.flatMap { it.invoiceNumbers }.toSet())
    }

    @Test
    fun `an empty journal says so instead of showing a blank grid`() {
        val (panel, _) = buildPanel(emptyList())

        assertTrue(renderedItems(panel).isEmpty())
        assertTrue(panel.emptyState.isVisible, "an empty result must be stated in words (journal.empty)")
        assertEquals("Keine Einträge", panel.resultCount.text)
    }

    /** [JournalPanel.trOrNull] is only ever passed by reference into [JournalPanelParts
     *  .configureGrid]/the composer context — its own body only runs once something downstream
     *  actually resolves a label through it, e.g. a journal detail line no static translation
     *  covers. Same direct-reflection idiom as [de.littletrotter.ui.view.referencedata
     *  .TaxRatesTabKaribuTest]'s own private-method invocations. */
    @Test
    fun `trOrNull delegates to UiTranslations for a key that resolves`() {
        val (panel, _) = buildPanel()
        val m = JournalPanel::class.java.getDeclaredMethod("trOrNull", String::class.java)
        m.isAccessible = true

        val resolved = m.invoke(panel, "journal.view.title") as String?

        assertEquals(panel.tr("journal.view.title"), resolved)
    }

    @Test
    fun `the header states how many actions match the current filter`() {
        val (panel, _) = buildPanel(listOf(line(1), line(2)))

        assertEquals("2 Einträge", panel.resultCount.text)
        assertFalse(panel.emptyState.isVisible)
    }

    /** A `Query` with a non-positive limit (Vaadin allows one, e.g. `Query(0, 0, ...)`) falls back
     *  to the panel's own page size rather than asking the service for zero rows. */
    @Test
    fun `fetching with a non-positive query limit falls back to the page size, not zero`() {
        val (panel, service) = buildPanel(listOf(line(1)))

        panel.grid.dataProvider.fetch(Query(0, 0, emptyList(), null, null)).toList()

        verify(service, atLeastOnce()).findGlobalActions(any(), eq(0), eq(25), any())
    }

    /** The only panel-level control besides the filters is "reset filters" — it changes the
     *  query, never the journal. Nothing here saves, edits or deletes. */
    @Test
    fun `the only panel-level button is the filter reset, never an edit or delete`() {
        val (panel, _) = buildPanel(listOf(line(1), line(2)))

        val buttons = flatten(panel).filterIsInstance<Button>()
        assertEquals(listOf("Filter zurücksetzen"), buttons.map { it.text })
    }

    @Test
    fun `resetting clears every filter at once and queries everything again`() {
        val (panel, service) = buildPanel()
        panel.invoiceNumberFilter.value = "RE-2026-0007"
        panel.actorFilter.value = "alice"
        panel.actionFilter.select(InvoiceAuditAction.CANCELLATION)
        panel.dateFromFilter.value = LocalDate.of(2026, 9, 1)

        flatten(panel).filterIsInstance<Button>().single().click()
        pullData(panel)

        verify(service).findGlobalActions(eq(JournalGlobalFilter()), any(), any(), any())
    }

    /** [JournalPanel.refresh] only swaps in a NEW lazy [com.vaadin.flow.data.provider.DataProvider]
     *  on every filter change — it never calls the service eagerly. Forcing one `fetch(Query())`
     *  pull (same as [renderedItems]) is what actually triggers the fetch callback, exactly what a
     *  real Grid attached to a live UI would do on its own. */
    private fun pullData(panel: JournalPanel) {
        panel.grid.dataProvider.fetch(Query()).toList()
    }

    @Test
    fun `typing an invoice number filter narrows the query sent to the service`() {
        val (panel, service) = buildPanel()

        panel.invoiceNumberFilter.value = "RE-2026-0007"
        pullData(panel)

        verify(service).findGlobalActions(eq(JournalGlobalFilter(invoiceNumber = "RE-2026-0007")), any(), any(), any())
    }

    @Test
    fun `typing an actor filter narrows the query sent to the service`() {
        val (panel, service) = buildPanel()

        panel.actorFilter.value = "alice"
        pullData(panel)

        verify(service).findGlobalActions(eq(JournalGlobalFilter(actor = "alice")), any(), any(), any())
    }

    @Test
    fun `picking one or more actions narrows the query sent to the service`() {
        val (panel, service) = buildPanel()

        panel.actionFilter.select(InvoiceAuditAction.CANCELLATION)
        pullData(panel)

        verify(service).findGlobalActions(eq(JournalGlobalFilter(actions = setOf(InvoiceAuditAction.CANCELLATION))), any(), any(), any())
    }

    @Test
    fun `picking a date range narrows the query sent to the service`() {
        val (panel, service) = buildPanel()
        val from = LocalDate.of(2026, 9, 1)
        val to = LocalDate.of(2026, 9, 4)

        panel.dateFromFilter.value = from
        panel.dateToFilter.value = to
        pullData(panel)

        verify(service).findGlobalActions(eq(JournalGlobalFilter(occurredFrom = from, occurredTo = to)), any(), any(), any())
    }

    @Test
    fun `clearing a filter after setting it goes back to querying everything`() {
        val (panel, service) = buildPanel()

        panel.invoiceNumberFilter.value = "RE-2026-0007"
        panel.invoiceNumberFilter.clear()
        pullData(panel)

        verify(service).findGlobalActions(eq(JournalGlobalFilter()), any(), any(), any())
    }

    // ── "Zeitpunkt" sort ───────────────────────────────────────────────

    @Test
    fun `the arrow shows the default (newest first) direction from the first render, before any click`() {
        val (panel, _) = buildPanel()

        val order = panel.grid.sortOrder.firstOrNull()

        assertEquals("occurredAt", order?.sorted?.key)
        assertEquals(SortDirection.DESCENDING, order?.direction)
    }

    @Test
    fun `sorting Zeitpunkt ascending re-fetches through the service with ASC`() {
        val (panel, service) = buildPanel()
        val column = panel.grid.getColumnByKey("occurredAt")!!

        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))
        pullData(panel)

        verify(service, atLeastOnce()).findGlobalActions(any(), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    /** Distinct from both "no sort order at all" (cleared) and "ascending": a non-null order whose
     *  own direction is DESCENDING, exercising the `when`'s plain `else` arm on a real order rather
     *  than the null-order fallback that happens to compute the same direction. */
    @Test
    fun `sorting Zeitpunkt descending explicitly re-fetches through the service with DESC`() {
        val (panel, service) = buildPanel()
        val column = panel.grid.getColumnByKey("occurredAt")!!
        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        pullData(panel)

        verify(service, atLeastOnce()).findGlobalActions(any(), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    /** B2 — clearing the sort (Vaadin's third click fires an empty `sortOrder`) toggles the
     *  direction (here ASC -> DESC), never "unsorted". */
    @Test
    fun `clearing the sort while ascending toggles back to newest first`() {
        val (panel, service) = buildPanel()
        val column = panel.grid.getColumnByKey("occurredAt")!!
        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        panel.grid.sort(emptyList())
        pullData(panel)

        verify(service, atLeastOnce()).findGlobalActions(any(), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    /** Regression: the first click from the default arrives as "cleared" and must toggle to ASC. */
    @Test
    fun `the first click from the default toggles to ascending, the next back to descending`() {
        val (panel, service) = buildPanel()
        val column = panel.grid.getColumnByKey("occurredAt")!!

        panel.grid.sort(emptyList())
        pullData(panel)

        assertEquals(SortDirection.ASCENDING, panel.grid.sortOrder.firstOrNull()?.direction)
        verify(service, atLeastOnce()).findGlobalActions(any(), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.ASC))

        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        assertEquals(SortDirection.DESCENDING, panel.grid.sortOrder.firstOrNull()?.direction)
    }

    /** B3 — this panel is a `@Dependent` bean [SystemView] keeps alive rather than a panel rebuilt
     *  per navigation (unlike [de.littletrotter.ui.view.invoice.InvoiceJournalPanel]), so its own
     *  `currentSortDirection` field surviving a filter change is the whole story here. */
    @Test
    fun `the sort direction survives a filter change`() {
        val (panel, service) = buildPanel()
        val column = panel.grid.getColumnByKey("occurredAt")!!
        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        panel.actorFilter.value = "alice"
        pullData(panel)

        verify(service, atLeastOnce()).findGlobalActions(eq(JournalGlobalFilter(actor = "alice")), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }
}
