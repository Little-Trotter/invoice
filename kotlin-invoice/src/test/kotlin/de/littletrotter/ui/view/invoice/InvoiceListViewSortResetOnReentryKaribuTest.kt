package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Vaadin's router REUSES the current view instance when navigating to the route
 * the user is already on (menu click "Rechnungen" while standing on the list). `beforeEnter` runs
 * again on the SAME instance, so a header-click sort surviving in the `currentSortField`/
 * `currentSortDir` instance fields leaked through [InvoiceListView.restoreFilterState] and
 * re-armed itself via `grid.sort(...)` — the newest-first entry default silently lost, even
 * though the sort was correctly excluded from the session-held [InvoiceListFilterState] and the
 * entry URL carried no sort parameters. Found via a real-browser E2E fail screenshot: an active
 * ascending indicator on "Rechnungsnummer" that no click in that navigation had produced.
 *
 * These tests drive the reuse scenario literally: one view instance, a user sort in between, and
 * a second [InvoiceListView.restoreFilterState] on the same instance — which must reset to the
 * INVOICE_DATE DESC default. An explicit URL sort must still win, unchanged.
 */
class InvoiceListViewSortResetOnReentryKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    private val sameDate: LocalDate = LocalDate.of(2026, 9, 3)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()

        whenever(invoiceListQuery.count(any())).thenReturn(2L)
        whenever(invoiceListQuery.countByStatus(any())).thenReturn(emptyMap())
        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(emptyList())
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(listOf(row(id = 2L), row(id = 1L)))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun grid(view: InvoiceListView): Grid<InvoiceOverviewDto> {
        val f = InvoiceListView::class.java.getDeclaredField("grid")
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    private fun row(id: Long?) = InvoiceOverviewDto(
        id = id,
        invoiceNumber = "",
        typeCode = null,
        sellerName = "Seller",
        sellerCity = null,
        recipientName = "Acme",
        grossAmount = null,
        paymentLabel = null,
        freeText = null,
        itemsCount = 1,
        invoiceDate = sameDate,
        status = InvoiceStatus.DRAFT,
        validationStatus = ValidationStatus.NOT_VALIDATED,
    )

    private fun activeSort(view: InvoiceListView): Pair<String?, SortDirection?> {
        val order = grid(view).sortOrder.firstOrNull()
        return order?.sorted?.key to order?.direction
    }

    @Test
    fun `re-entering the reused instance resets a user sort back to the newest-first default`() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        view.restoreFilterState(QueryParameters.empty())
        assertEquals("invoiceDate" to SortDirection.DESCENDING, activeSort(view), "sanity: entry default")

        // The user's header click — grid.sort fires the same sort listener a real click does,
        // mutating the view's currentSortField/currentSortDir instance fields to NUMBER/ASC.
        val numberColumn = grid(view).getColumnByKey("invoiceNumber")!!
        grid(view).sort(listOf(GridSortOrder(numberColumn, SortDirection.ASCENDING)))
        assertEquals("invoiceNumber" to SortDirection.ASCENDING, activeSort(view), "sanity: user sort armed")

        // Router reuse: same instance, beforeEnter → restoreFilterState with a sort-less URL.
        view.restoreFilterState(QueryParameters.empty())

        assertEquals(
            "invoiceDate" to SortDirection.DESCENDING,
            activeSort(view),
            "a surviving instance-field sort must not outlive re-entry — newest-first is the entry default",
        )
    }

    @Test
    fun `an explicit URL sort still wins over the re-entry reset`() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        view.restoreFilterState(QueryParameters.empty())

        view.restoreFilterState(
            QueryParameters.simple(mapOf("sortField" to "INVOICE_NUMBER", "sortDir" to "ASC")),
        )

        assertEquals(
            "invoiceNumber" to SortDirection.ASCENDING,
            activeSort(view),
            "deep-linked sort parameters must keep working — only the implicit carry-over is reset",
        )
    }
}
