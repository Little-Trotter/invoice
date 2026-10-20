package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.ui.i18n.UiTranslations
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
import java.time.OffsetDateTime
import java.util.Locale

/** [InvoiceJournalPanel], the invoice detail view's "Journal" tab: one line per
 *  business action, the result count and empty state state the outcome in words, and — the
 *  append-only audit trail demands — the only affordance anywhere in the panel
 *  toggles item-details or opens the read-only detail dialog from a technical row. */
class InvoiceJournalPanelTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun row(seq: Long, event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED) = InvoiceAuditJournalEntry(
        seq = seq, businessTx = seq, invoiceId = 1L, invoiceNumber = "RE-2026-0001",
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-0${(seq % 9) + 1}T10:00:00Z"),
        event = event, action = InvoiceAuditAction.HEADER_CHANGED,
        detail = """{"free_text": {"old": "vorher", "new": "nachher"}}""",
        documentSha256 = null, prevHash = "prev-$seq", entryHash = "hash-$seq",
    )

    private fun line(seq: Long, action: InvoiceAuditAction = InvoiceAuditAction.HEADER_CHANGED) = InvoiceAuditActionLine(
        businessTx = seq, action = action, actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-0${(seq % 9) + 1}T10:00:00Z"),
        invoiceIds = listOf(1L), invoiceNumbers = listOf("RE-2026-0001"),
        rows = listOf(row(seq)),
    )

    private fun buildPanel(lines: List<InvoiceAuditActionLine>): InvoiceJournalPanel {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(lines)
        whenever(service.countActionsByInvoice(1L)).thenReturn(lines.size.toLong())
        return InvoiceJournalPanel(service).apply { render(1L) }
    }

    private fun renderedItems(panel: InvoiceJournalPanel): List<InvoiceAuditActionLine> =
        panel.grid.dataProvider.fetch(Query()).toList()

    private fun countText(panel: InvoiceJournalPanel): String = panel.resultCount.element.textRecursively

    private fun buildPanelWithCount(total: Int): InvoiceJournalPanel {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(total.toLong())
        return InvoiceJournalPanel(service).apply { render(1L) }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    @Test
    fun `renders every business action for the invoice`() {
        val panel = buildPanel(listOf(line(1), line(2), line(3)))

        val items = renderedItems(panel)

        assertEquals(3, items.size)
        assertEquals(setOf(1L, 2L, 3L), items.map { it.businessTx }.toSet())
    }

    @Test
    fun `grid has one translated column header per field, including the Vorgang column`() {
        val panel = buildPanel(listOf(line(1, InvoiceAuditAction.INVOICE_CREATED)))

        val headers = panel.grid.columns.map { it.headerText }
        assertTrue(headers.contains("Zeitpunkt"), "expected the occurredAt column header, got: $headers")
        assertTrue(headers.contains("Vorgang"), "expected the action column header, got: $headers")
        assertTrue(headers.contains("Was genau"), "expected the what column header, got: $headers")
        assertFalse(headers.any { it.toIntOrNull() != null }, "no header may ever be a raw number")
    }

    /** The invoice column belongs to the global view only — every row here is already scoped to
     *  the invoice on screen, so repeating its number in each row would be noise. */
    @Test
    fun `the per-invoice tab drops the invoice column the global view carries`() {
        val panel = buildPanel(listOf(line(1)))

        assertFalse(panel.grid.columns.map { it.headerText }.contains("Rechnungsnummer"))
    }

    @Test
    fun `no edit or delete affordance ever appears in the journal tab`() {
        val panel = buildPanel(listOf(line(1), line(2)))

        // Cell components are built lazily by the grid, so the panel's own tree is the surface
        // this asserts on: an append-only view must not carry a save/delete control of its own.
        val buttons = flatten(panel).filterIsInstance<Button>()
        assertTrue(buttons.isEmpty(), "the append-only journal must offer no panel-level action, found: ${buttons.map { it.text }}")
    }

    @Test
    fun `an invoice with no journal entries says so instead of showing a blank grid`() {
        val panel = buildPanel(emptyList())

        assertTrue(renderedItems(panel).isEmpty())
        assertTrue(panel.emptyState.isVisible, "an empty result must be stated in words (journal.empty)")
        assertEquals("Keine Einträge", countText(panel))
    }

    @Test
    fun `the header states how many actions the invoice has`() {
        val panel = buildPanel(listOf(line(1), line(2), line(3)))

        assertEquals("3 Einträge", countText(panel))
        assertFalse(panel.emptyState.isVisible)
    }

    @Test
    fun `re-rendering a different invoice replaces the grid content instead of appending to it`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(listOf(line(1)))
        whenever(service.countActionsByInvoice(1L)).thenReturn(1L)
        whenever(service.findActionsByInvoice(eq(2L), any(), any(), any())).thenReturn(listOf(line(10), line(11)))
        whenever(service.countActionsByInvoice(2L)).thenReturn(2L)

        val panel = InvoiceJournalPanel(service)
        panel.render(1L)
        panel.render(2L)

        assertEquals(setOf(10L, 11L), renderedItems(panel).map { it.businessTx }.toSet())
    }

    // ── expand toggle ────────────────────────────────────────────────────────

    @Test
    fun `expanding an action line shows its technical rows`() {
        val twoRowLine = line(1).copy(rows = listOf(row(1), row(2)))
        val panel = buildPanel(listOf(twoRowLine))
        val item = renderedItems(panel).single()

        assertFalse(panel.grid.isDetailsVisible(item), "collapsed by default")
        panel.grid.setDetailsVisible(item, true)
        assertTrue(panel.grid.isDetailsVisible(item))
    }

    // ── "Zeitpunkt" sort ─────────────────────────────────────────────────────

    @Test
    fun `the arrow shows the default (newest first) direction from the first render, before any click`() {
        val panel = buildPanel(listOf(line(1)))

        val order = panel.grid.sortOrder.firstOrNull()

        assertEquals("occurredAt", order?.sorted?.key)
        assertEquals(SortDirection.DESCENDING, order?.direction)
    }

    @Test
    fun `sorting Zeitpunkt ascending re-fetches through the service with ASC`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(0L)
        val panel = InvoiceJournalPanel(service).apply { render(1L) }
        val column = panel.grid.getColumnByKey("occurredAt")!!

        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))
        renderedItems(panel)

        verify(service, atLeastOnce()).findActionsByInvoice(eq(1L), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    /** Clearing the sort (Vaadin's third click fires an empty `sortOrder`) toggles the direction
     *  (here ASC -> DESC), never "unsorted". */
    @Test
    fun `clearing the sort while ascending toggles back to newest first`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(0L)
        val panel = InvoiceJournalPanel(service).apply { render(1L) }
        val column = panel.grid.getColumnByKey("occurredAt")!!
        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        panel.grid.sort(emptyList())
        renderedItems(panel)

        verify(service, atLeastOnce()).findActionsByInvoice(eq(1L), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    /** A rebuilt panel (as [InvoiceDetailTabs.buildJournalPanel] does on every data refresh)
     *  starts with whatever direction it is constructed with, not always the hardcoded default. */
    @Test
    fun `an initial sort direction other than the default is honoured from construction`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(0L)

        val panel = InvoiceJournalPanel(service, initialSortDirection = de.littletrotter.domain.shared.SortDirection.ASC)
            .apply { render(1L) }

        val order = panel.grid.sortOrder.firstOrNull()
        assertEquals(SortDirection.ASCENDING, order?.direction)
        renderedItems(panel)
        verify(service, atLeastOnce()).findActionsByInvoice(eq(1L), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    /** The callback a rebuilding caller (like [InvoiceDetailTabs]) relies on to remember the
     *  user's choice across the panel's own rebuild. */
    @Test
    fun `toggling the sort reports the new direction back through onSortDirectionChanged`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(0L)
        var reported: de.littletrotter.domain.shared.SortDirection? = null
        val panel = InvoiceJournalPanel(service, onSortDirectionChanged = { reported = it }).apply { render(1L) }
        val column = panel.grid.getColumnByKey("occurredAt")!!

        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(de.littletrotter.domain.shared.SortDirection.ASC, reported)
    }

    /** Regression: Vaadin's sorter cycles asc -> desc -> cleared. Starting from DESC the first
     *  click arrives as "cleared", which used to be mapped back to DESC (arrow vanished, order
     *  unchanged, two clicks needed for ascending). It must toggle on every click. */
    @Test
    fun `the first click on Zeitpunkt from the default toggles to ascending, the next back to descending`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(0L)
        val reported = mutableListOf<de.littletrotter.domain.shared.SortDirection>()
        val panel = InvoiceJournalPanel(service, onSortDirectionChanged = { reported += it }).apply { render(1L) }
        val column = panel.grid.getColumnByKey("occurredAt")!!
        assertEquals(SortDirection.DESCENDING, panel.grid.sortOrder.firstOrNull()?.direction)
        reported.clear() // the initial sort in configureGrid reports DESC once; only clicks count

        panel.grid.sort(emptyList())
        renderedItems(panel)

        assertEquals(listOf(de.littletrotter.domain.shared.SortDirection.ASC), reported)
        assertEquals(SortDirection.ASCENDING, panel.grid.sortOrder.firstOrNull()?.direction)
        verify(service, atLeastOnce()).findActionsByInvoice(eq(1L), any(), any(), eq(de.littletrotter.domain.shared.SortDirection.ASC))

        panel.grid.sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        assertEquals(
            listOf(de.littletrotter.domain.shared.SortDirection.ASC, de.littletrotter.domain.shared.SortDirection.DESC),
            reported,
        )
        assertEquals(SortDirection.DESCENDING, panel.grid.sortOrder.firstOrNull()?.direction)
    }

    // ── title and count inside the grid's own header row ───────────────

    @Test
    fun `the grid has a prepended header row with one joined cell holding the title and the count`() {
        val panel = buildPanel(listOf(line(1), line(2)))

        val rows = panel.grid.headerRows
        assertEquals(2, rows.size, "the band row plus the column-label row")
        val band = rows.first().cells.single()
        assertEquals("journal-band-cell", band.partName)
        val content = flatten(band.component)
        assertTrue(content.any { it.element.text == "Protokoll" }, "the title sits in the band")
        assertTrue(content.contains(panel.resultCount), "the count sits in the band")
        assertEquals("2 Einträge", panel.resultCount.element.getAttribute("aria-label"))
    }

    @Test
    fun `the column labels stay the row beneath the band and rendering again does not stack another band`() {
        val panel = buildPanel(listOf(line(1)))
        panel.render(1L)
        panel.render(1L)

        assertEquals(2, panel.grid.headerRows.size)
        assertTrue(panel.grid.columns.map { it.headerText }.contains("Zeitpunkt"))
    }

    @Test
    fun `the old header band is gone from the card, only grid and empty state remain`() {
        val panel = buildPanel(emptyList())

        val card = flatten(panel).single { it.element.classList.contains("journal-invoice-card") }
        assertEquals(listOf<Component>(panel.grid, panel.emptyState), card.children.toList())
        assertTrue(flatten(card).none { it.element.classList.contains("journal-header") || it.element.classList.contains("journal-count") })
    }

    @Test
    fun `the count reads none, one and many in German`() {
        assertEquals("Keine Einträge", countText(buildPanelWithCount(0)))
        assertEquals("1 Eintrag", countText(buildPanelWithCount(1)))
        assertEquals("25 Einträge", countText(buildPanelWithCount(25)))
    }

    /** Karibu registers no I18NProvider, so a panel always renders through the German fallback;
     *  the English strings are therefore read from the bundle by locale. The panel splits whatever
     *  this key yields at its number, it holds no English text of its own. */
    @Test
    fun `the English count key reads none, one and many`() {
        fun en(total: Long) = UiTranslations.trOrNullForLocale(Locale.ENGLISH, "journal.count", total)

        assertEquals("No entries", en(0L))
        assertEquals("1 entry", en(1L))
        assertEquals("25 entries", en(25L))
    }

    @Test
    fun `the number is its own span and the word another, no entries stays one word span`() {
        val many = buildPanelWithCount(25).resultCount.children.toList()
        assertEquals(listOf("25", " Einträge"), many.map { it.element.text })
        assertTrue(many[0].element.classList.contains("journal-band__count-num"))
        assertTrue(many[1].element.classList.contains("journal-band__count-word"))

        val none = buildPanelWithCount(0).resultCount.children.toList()
        assertEquals(listOf("Keine Einträge"), none.map { it.element.text })
        assertTrue(none.single().element.classList.contains("journal-band__count-word"))
    }

    @Test
    fun `the count updates when the panel renders another invoice`() {
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(any(), any(), any(), any())).thenReturn(emptyList())
        whenever(service.countActionsByInvoice(1L)).thenReturn(1L)
        whenever(service.countActionsByInvoice(2L)).thenReturn(7L)
        val panel = InvoiceJournalPanel(service)

        panel.render(1L)
        assertEquals("1 Eintrag", countText(panel))
        panel.render(2L)
        assertEquals("7 Einträge", countText(panel))
        assertEquals("7 Einträge", panel.resultCount.element.getAttribute("aria-label"))
    }

    @Test
    fun `the empty state still shows below the band for zero entries`() {
        val panel = buildPanelWithCount(0)

        assertTrue(panel.emptyState.isVisible)
        assertEquals("Keine Einträge", countText(panel))
    }

    // ── viewport-filling card ───────────────────────────────────────────

    @Test
    fun `the grid scrolls inside the card instead of sizing itself to all rows`() {
        val panel = buildPanel(listOf(line(1)))

        assertFalse(panel.grid.isAllRowsVisible)
        assertEquals(20, panel.grid.pageSize)
    }

    @Test
    fun `the card carries the flex column sizing class and the grid stays lazy`() {
        val lines = (1L..25L).map { line(it) }
        val service: AuditJournalQueryService = mock()
        whenever(service.findActionsByInvoice(eq(1L), eq(0), eq(20), any())).thenReturn(lines.take(20))
        whenever(service.countActionsByInvoice(1L)).thenReturn(lines.size.toLong())
        val panel = InvoiceJournalPanel(service).apply { render(1L) }

        val card = flatten(panel).single { it.element.classList.contains("journal-invoice-card") }
        assertTrue(card.element.classList.contains("journal-invoice-card--fill"))
        assertTrue(flatten(card).contains(panel.grid), "the grid sits inside the card")
        assertEquals(20, panel.grid.dataProvider.fetch(Query(0, 20, emptyList(), null, null)).toList().size, "the first page only, never the full history")
        assertEquals("25 Einträge", countText(panel))
    }

    @Test
    fun `the empty state lives inside the same card`() {
        val panel = buildPanel(emptyList())

        val card = flatten(panel).single { it.element.classList.contains("journal-invoice-card") }
        assertTrue(flatten(card).contains(panel.emptyState))
        assertTrue(card.element.classList.contains("journal-invoice-card--fill"), "the card keeps its full height when empty")
    }

    @Test
    fun `the viewport fit script is registered once on attach however often render rebuilds the card`() {
        val panel = buildPanel(listOf(line(1)))
        assertEquals(0, panel.viewportFitRegistrations, "nothing is registered before the panel is attached")

        UI.getCurrent().add(panel)
        panel.render(1L)
        panel.render(1L)

        assertEquals(1, panel.viewportFitRegistrations)
    }

    @Test
    fun `detaching and re-attaching registers a fresh script for the same panel`() {
        val panel = buildPanel(listOf(line(1)))
        UI.getCurrent().add(panel)

        UI.getCurrent().remove(panel)
        UI.getCurrent().add(panel)

        assertEquals(2, panel.viewportFitRegistrations)
    }
}
