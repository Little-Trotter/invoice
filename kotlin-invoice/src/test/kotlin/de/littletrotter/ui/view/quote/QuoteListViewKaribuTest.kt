package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._clickItem
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.ColumnTextAlign
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.grid.ItemClickEvent
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.ListDataProvider
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.CreateQuoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteCustomerLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteListQueryPort
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteStatistics
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.security.AppPermission
import de.littletrotter.ui.view.invoice.InvoiceListView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QuoteListViewKaribuTest {

    private val listQuery: QuoteListQueryPort = mock()
    private val workbookExport: QuoteListWorkbookExportQueryPort = mock()
    private val statistics: QuoteStatisticsQueryPort = mock()
    private val lookup: QuoteCustomerLookupQueryPort = mock()
    private val create: CreateQuoteUseCase = mock()

    private val customer = QuoteCustomerOption("c-1", "Muster GmbH", "K-100", "Köln")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
        whenever(listQuery.findPage(any())).thenReturn(
            QuotePage(listOf(quoteSummary("q-1"), quoteSummary("q-2", status = QuoteStatus.DRAFT)), 2),
        )
        whenever(statistics.statistics()).thenReturn(QuoteStatistics(mapOf(QuoteStatus.SENT to 4L)))
        whenever(lookup.customers(any(), any())).thenReturn(listOf(customer))
        whenever(create.create("c-1")).thenReturn("q-9")
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(enabled: Boolean = true, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS) =
        QuoteListView(listQuery, QuoteListExportPorts(workbookExport), statistics, lookup, create, QuoteAvailability(enabled), rolesWith(*granted))

    private fun enter(view: QuoteListView): com.vaadin.flow.router.BeforeEnterEvent {
        val event = quoteEvent()
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return event
    }

    private fun rows(view: QuoteListView): List<QuoteSummary> = (view.grid.dataProvider as ListDataProvider<QuoteSummary>).items.toList()

    private fun lastCriteria(): QuoteListCriteria {
        val captor = argumentCaptor<QuoteListCriteria>()
        verify(listQuery, atLeastOnce()).findPage(captor.capture())
        return captor.lastValue
    }

    private fun chips(view: QuoteListView) = view.quickFilterRow.children.toList().filterIsInstance<NativeButton>()

    @Test
    fun `without the component the view forwards to the invoice list`() {
        val event = enter(newView(enabled = false))

        verify(event).forwardTo(InvoiceListView::class.java)
        verify(listQuery, never()).findPage(any())
    }

    @Test
    fun `with the module switched off in the settings the view forwards to the invoice list`() {
        val view = QuoteListView(
            listQuery, QuoteListExportPorts(workbookExport), statistics, lookup, create,
            QuoteAvailability(true, featureGateWithDisabled(FeatureFlag.QUOTE)),
            rolesWith(*ALL_QUOTE_PERMISSIONS),
        )
        val event = enter(view)

        verify(event).forwardTo(InvoiceListView::class.java)
    }

    @Test
    fun `without the view permission the view forwards to the invoice list`() {
        val event = enter(newView(true, AppPermission.QUOTE_EDIT))

        verify(event).forwardTo(InvoiceListView::class.java)
    }

    @Test
    fun `entering loads the first page and shows rows statistics and markers`() {
        val view = newView()
        enter(view)

        val criteria = lastCriteria()
        assertNull(criteria.status)
        assertNull(criteria.text)
        assertEquals(0, criteria.offset)
        assertEquals(50, criteria.limit)
        assertEquals(listOf("q-1", "q-2"), rows(view).map { it.id })
        assertFalse(view.emptyState.isVisible)
        assertEquals(listOf("Versendet 4"), chips(view).map { it.text })
        assertFalse(view.resetChip.isVisible)
        assertNull(view.grid.getColumnByKey("outcome"))
        assertNotNull(view.grid.getColumnByKey("status"))
        assertEquals("Versendet", view.grid._getCellComponent(0, "status").element.getAttribute("aria-label"))
        assertTrue(view.newButton.isVisible)
    }

    @Test
    fun `number sizes to its content and subject takes the spare room`() {
        val view = newView()

        val number = view.grid.getColumnByKey("number")
        val subject = view.grid.getColumnByKey("subject")
        assertTrue(number.isAutoWidth)
        assertEquals(0, number.flexGrow)
        assertTrue(subject.flexGrow > view.grid.getColumnByKey("customer").flexGrow)
        assertTrue(view.grid.getColumnByKey("status").isAutoWidth)
    }

    @Test
    fun `a missing number reads as draft and an offer without subject has an empty subject cell`() {
        whenever(listQuery.findPage(any())).thenReturn(
            QuotePage(listOf(quoteSummary("q-3").copy(number = null, subject = null)), 1),
        )
        val view = newView()
        enter(view)

        assertEquals("Entwurf", view.grid._getFormattedRow(0)[0])
        assertEquals("", view.grid._getFormattedRow(0)[2])
    }

    @Test
    fun `callers who may only view do not see the new button`() {
        val view = newView(true, AppPermission.QUOTE_VIEW)
        enter(view)

        assertFalse(view.newButton.isVisible)
    }

    @Test
    fun `the filters become query criteria and reset the page`() {
        val view = newView()
        enter(view)

        chips(view).single()._click()
        assertEquals(QuoteStatus.SENT, lastCriteria().status)
        view.searchField.value = "  muster  "
        assertEquals("muster", lastCriteria().text)
        assertEquals(0, lastCriteria().offset)
    }

    @Test
    fun `chips show only non-empty statuses with their counts`() {
        whenever(statistics.statistics()).thenReturn(
            QuoteStatistics(mapOf(QuoteStatus.REVIEW to 2L, QuoteStatus.DRAFT to 0L, QuoteStatus.SENT to 1L)),
        )
        val view = newView()
        enter(view)

        assertEquals(listOf("In Prüfung 2", "Versendet 1"), chips(view).map { it.text })
        assertEquals("false", chips(view).first().element.getAttribute("aria-pressed"))
    }

    @Test
    fun `a chip click filters on page zero marks the chip and a second click clears it`() {
        whenever(statistics.statistics()).thenReturn(
            QuoteStatistics(mapOf(QuoteStatus.REVIEW to 2L, QuoteStatus.SENT to 1L)),
        )
        val view = newView()
        enter(view)

        chips(view).first { it.text.startsWith("In Prüfung") }._click()
        assertEquals(QuoteStatus.REVIEW, lastCriteria().status)
        assertEquals(0, lastCriteria().offset)
        assertEquals("true", chips(view).first().element.getAttribute("aria-pressed"))
        assertTrue(chips(view).first().hasClassName("quick-filter-chip--active"))

        chips(view).first { it.text.startsWith("Versendet") }._click()
        assertEquals(QuoteStatus.SENT, lastCriteria().status)
        assertEquals(1, chips(view).count { it.hasClassName("quick-filter-chip--active") })

        chips(view).first { it.text.startsWith("Versendet") }._click()
        assertNull(lastCriteria().status)
        assertEquals(0, chips(view).count { it.hasClassName("quick-filter-chip--active") })
    }

    @Test
    fun `the reset chip counts active filters and clears status and text`() {
        val view = newView()
        enter(view)
        assertFalse(view.resetChip.isVisible)

        chips(view).single()._click()
        assertTrue(view.resetChip.isVisible)
        assertEquals("1 Filter aktiv · zurücksetzen", view.resetChip.text)

        view.searchField.value = "muster"
        assertEquals("2 Filter aktiv · zurücksetzen", view.resetChip.text)

        view.resetChip._click()
        assertNull(lastCriteria().status)
        assertNull(lastCriteria().text)
        assertEquals("", view.searchField.value)
        assertFalse(view.resetChip.isVisible)
    }

    @Test
    fun `the reset chip also clears a status filter alone`() {
        val view = newView()
        enter(view)
        chips(view).single()._click()

        view.resetChip._click()

        assertNull(lastCriteria().status)
        assertFalse(view.resetChip.isVisible)
    }

    @Test
    fun `the pager moves the window by one page`() {
        whenever(listQuery.findPage(any())).thenReturn(QuotePage(listOf(quoteSummary()), 120))
        val view = newView()
        enter(view)

        view.pager._get<Button> { text = "→" }._click()

        assertEquals(50, lastCriteria().offset)
    }

    @Test
    fun `a failing component shows the reason and an empty list instead of stale rows`() {
        whenever(listQuery.findPage(any())).thenThrow(QuoteServiceException("Angebotskomponente nicht erreichbar"))
        val view = newView()
        enter(view)

        assertTrue(rows(view).isEmpty())
        assertTrue(view.emptyState.isVisible)
        assertTrue(notificationTexts().any { it.contains("Angebotskomponente nicht erreichbar") })
    }

    @Test
    fun `a failing statistics call leaves the list intact`() {
        whenever(statistics.statistics()).thenThrow(QuoteServiceException("Statistik nicht verfügbar"))
        val view = newView()
        enter(view)

        assertEquals(2, rows(view).size)
        assertTrue(chips(view).isEmpty())
        assertTrue(notificationTexts().any { it.contains("Statistik nicht verfügbar") })
    }

    @Test
    fun `clicking a row opens the offer`() {
        val view = newView()
        enter(view)

        view.grid._clickItem(0)

        assertEquals("quotes/q-1", currentPath())
    }

    @Test
    fun `an item click without an item neither navigates nor throws`() {
        val view = newView()
        enter(view)
        val before = currentPath()
        val event: ItemClickEvent<QuoteSummary> = mock()
        whenever(event.source).thenReturn(view.grid)
        whenever(event.item).thenReturn(null)

        ComponentUtil.fireEvent(view.grid, event)

        assertEquals(before, currentPath())
    }

    @Test
    fun `the create dialog creates a draft for the picked customer and opens the editor`() {
        val view = newView()
        enter(view)
        view.newButton._click()

        val dialog = _get<QuoteCreateDialog>()
        assertTrue(dialog.customerBox.isVisible)
        assertFalse(dialog.emptyState.isVisible)
        assertFalse(dialog.confirmButton.isEnabled)
        dialog.customerBox.value = customer
        assertTrue(dialog.confirmButton.isEnabled)
        dialog.confirmButton._click()

        verify(create).create("c-1")
        assertEquals("quotes/q-9/edit", currentPath())
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `the customer picker queries the lookup with the typed text and labels customers`() {
        val view = newView()
        enter(view)
        view.newButton._click()
        val dialog = _get<QuoteCreateDialog>()

        val provider = dialog.customerBox.dataProvider as DataProvider<QuoteCustomerOption, String>
        val fetched = provider.fetch(Query(5, 10, emptyList(), null, "mu")).toList()

        verify(lookup).customers("mu", 15)
        assertTrue(fetched.isEmpty())
        assertEquals("Muster GmbH (K-100, Köln)", dialog.customerBox.itemLabelGenerator.apply(customer))
        assertEquals("Solo", dialog.customerBox.itemLabelGenerator.apply(QuoteCustomerOption("c-2", "Solo")))
    }

    @Test
    fun `without replicated customers the dialog explains why and cannot create`() {
        whenever(lookup.customers(any(), any())).thenReturn(emptyList())
        val view = newView()
        enter(view)
        view.newButton._click()

        val dialog = _get<QuoteCreateDialog>()
        assertTrue(dialog.emptyState.isVisible)
        assertFalse(dialog.customerBox.isVisible)
        assertFalse(dialog.confirmButton.isEnabled)
        assertTrue(dialog.emptyState.text.contains("noch nicht"))
    }

    @Test
    fun `a refused creation keeps the dialog open and shows the reason`() {
        whenever(create.create(any())).thenThrow(QuoteServiceException("Kunde gesperrt"))
        val view = newView()
        enter(view)
        view.newButton._click()
        val dialog = _get<QuoteCreateDialog>()
        dialog.customerBox.value = customer
        dialog.confirmButton._click()

        assertTrue(dialog.isOpened)
        assertTrue(notificationTexts().any { it.contains("Kunde gesperrt") })
    }

    @Test
    fun `an unreachable lookup is shown as an empty state with the reason`() {
        whenever(lookup.customers(any(), any())).thenThrow(QuoteServiceException("Lookup nicht erreichbar"))
        val view = newView()
        enter(view)
        view.newButton._click()

        val dialog = _get<QuoteCreateDialog>()
        assertTrue(dialog.emptyState.isVisible)
        assertTrue(notificationTexts().any { it.contains("Lookup nicht erreichbar") })
    }

    @Test
    fun `an empty page shows the empty state`() {
        whenever(listQuery.findPage(any())).thenReturn(QuotePage(emptyList(), 0))
        val view = newView()
        enter(view)

        assertTrue(view.emptyState.isVisible)
        assertTrue(rows(view).isEmpty())
    }

    @Test
    fun `confirming without a customer does nothing`() {
        val view = newView()
        enter(view)
        view.newButton._click()
        val dialog = _get<QuoteCreateDialog>()

        fireClick(dialog.confirmButton)

        verify(create, never()).create(any())
        assertTrue(dialog.isOpened)
    }

    @Test
    fun `every data column is sortable and the grid sort reaches the port`() {
        val view = newView()
        enter(view)
        listOf("number", "customer", "subject", "status", "date", "validUntil", "net").forEach {
            assertTrue(view.grid.getColumnByKey(it).isSortable, it)
        }
        assertEquals(QuoteSortField.QUOTE_DATE, lastCriteria().sortField)
        assertEquals(QuoteSortDirection.DESC, lastCriteria().sortDirection)

        view.grid.sort(listOf(GridSortOrder(view.grid.getColumnByKey("net"), SortDirection.ASCENDING)))

        assertEquals(QuoteSortField.NET, lastCriteria().sortField)
        assertEquals(QuoteSortDirection.ASC, lastCriteria().sortDirection)
        assertEquals(0, lastCriteria().offset)

        view.grid.sort(emptyList())

        assertEquals(QuoteSortField.QUOTE_DATE, lastCriteria().sortField)
        assertEquals(QuoteSortDirection.DESC, lastCriteria().sortDirection)
    }

    @Test
    fun `entering marks the newest first order on the date column and sorting moves the mark`() {
        val view = newView()
        enter(view)

        val initial = view.grid.sortOrder.single()
        assertEquals("date", initial.sorted.key)
        assertEquals(SortDirection.DESCENDING, initial.direction)

        view.grid.sort(listOf(GridSortOrder(view.grid.getColumnByKey("net"), SortDirection.ASCENDING)))
        enter(view)

        val kept = view.grid.sortOrder.single()
        assertEquals("net", kept.sorted.key)
        assertEquals(SortDirection.ASCENDING, kept.direction)
        assertEquals(QuoteSortField.NET, lastCriteria().sortField)
    }

    @Test
    fun `the page size choice reloads the list with the new limit`() {
        val view = newView()
        enter(view)
        assertEquals(50, view.pageSizeSelect.value)

        view.pageSizeSelect.value = 10

        assertEquals(10, lastCriteria().limit)
        assertEquals(0, lastCriteria().offset)
    }

    @Test
    fun `the net column is a right aligned numeric column`() {
        val view = newView()
        enter(view)

        assertEquals(ColumnTextAlign.END, view.grid.getColumnByKey("net").textAlign)
    }
}
