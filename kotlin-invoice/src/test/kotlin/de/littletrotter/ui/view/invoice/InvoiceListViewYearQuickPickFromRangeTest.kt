package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * UI-5 slice 5 — [InvoiceListFilterBar.refreshYearQuickPickFromDateRange]'s "does the restored Von/Bis
 * range happen to span exactly one full calendar year" condition (a five-way `&&` chain across
 * [restoredFrom]/[restoredTo]) had never been driven true: every prior test either leaves the date
 * filters empty or sets a partial-year range, so only the early-exit (null or non-full-year) arms
 * of the chain were ever exercised. Restoring from URL query parameters is the real production path
 * that can produce a full-year range (a deep link with `dateFrom=2025-01-01&dateTo=2025-12-31`).
 */
class InvoiceListViewYearQuickPickFromRangeTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2024, 2025, 2026))
        whenever(
            invoiceListQuery.count(any()),
        ).thenReturn(0L)
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): InvoiceListView = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

    private fun yearQuickPick(view: InvoiceListView): ComboBox<Int> {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("yearQuickPick")
        field.isAccessible = true
        return field.get(view.filterBar).requireAs()
    }

    @Test
    fun `restoring an exact full-year Von-Bis range from the URL pre-selects the matching year`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "2025-01-01", "dateTo" to "2025-12-31")))

        assertEquals(2025, yearQuickPick(view).value)
    }

    @Test
    fun `restoring a Von-Bis range that starts mid-year does not pre-select any year`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "2025-02-01", "dateTo" to "2025-12-31")))

        assertNull(yearQuickPick(view).value)
    }

    @Test
    fun `restoring a Von-Bis range spanning two different years does not pre-select any year`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "2025-01-01", "dateTo" to "2026-12-31")))

        assertNull(yearQuickPick(view).value)
    }

    @Test
    fun `restoring with only a Von date and no Bis date does not pre-select any year`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "2025-01-01")))

        assertNull(yearQuickPick(view).value)
    }

    @Test
    fun `restoring a range starting Jan 1 but not ending Dec 31 does not pre-select any year`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "2025-01-01", "dateTo" to "2025-11-30")))

        assertNull(yearQuickPick(view).value)
    }
}
