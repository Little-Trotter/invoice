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
import kotlin.streams.toList
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The "newest invoice shown first" investigation proved the server emits the
 * correct `order by invoiceDate desc, id desc` (SQL log) and karibu renders the service order
 * correctly, yet the real browser showed the OLDER of two same-date invoices first. Root cause
 * (see [InvoiceListGridColumns.configure]'s `invoiceDate` column comment): the column never had an
 * explicit comparator, so Vaadin's `Grid.Column` fell back to its constructor default — a no-op
 * `(a, b) -> 0` — which `grid.sort(...)` (called on every navigation via
 * [InvoiceListView.restoreFilterState], since INVOICE_DATE DESC is the default sort) turns into
 * Grid's in-memory sorting comparator. An "always equal" comparator lets whatever order the
 * DataCommunicator/client machinery already holds win, instead of the server's page order.
 *
 * These tests exercise the fix — an explicit comparator mirroring the backend's
 * `cb.desc(invoiceDate), cb.desc(id)` tiebreaker — directly against the same fetch path Grid uses
 * to push data to the client ([Grid.getGenericDataView]'s `items`, backed by
 * `dataCommunicator.buildQuery(...)`), in both sort directions, plus the null-`invoiceDate`
 * pinning test.
 */
class InvoiceListViewDateColumnTiebreakerKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    private val sameDate: LocalDate = LocalDate.of(2026, 8, 30)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()

        whenever(invoiceListQuery.count(any())).thenReturn(2L)
        whenever(invoiceListQuery.countByStatus(any())).thenReturn(emptyMap())
        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(emptyList())
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

    private fun row(id: Long?, invoiceDate: LocalDate?) = InvoiceOverviewDto(
        id = id,
        invoiceNumber = "RE-2026-${id ?: 0}",
        typeCode = null,
        sellerName = "Seller",
        sellerCity = null,
        recipientName = "Acme",
        grossAmount = null,
        paymentLabel = null,
        freeText = null,
        itemsCount = 1,
        invoiceDate = invoiceDate,
        status = InvoiceStatus.ISSUED,
        validationStatus = ValidationStatus.NOT_VALIDATED,
    )

    private fun stubFindInvoiceOverview(items: List<InvoiceOverviewDto>) {
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(items)
    }

    private fun displayedIds(view: InvoiceListView): List<Long?> =
        grid(view).genericDataView.items.toList().map { it.id }

    // ── (a) default DESC entry preserves the service order on a same-date tie ──────────────────

    @Test
    fun `default entry keeps the service order newer-then-older for two same-date rows`() {
        val newer = row(id = 2L, invoiceDate = sameDate)
        val older = row(id = 1L, invoiceDate = sameDate)
        stubFindInvoiceOverview(listOf(newer, older))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        view.restoreFilterState(QueryParameters.empty())

        assertEquals(listOf(2L, 1L), displayedIds(view), "default INVOICE_DATE DESC must show the higher id (newer) first on a same-date tie")
    }

    // ── (b) flipping the grid sort direction flips the tiebreaker too ──────────────────────────

    @Test
    fun `flipping the invoiceDate column to ascending flips the tiebreaker to older-then-newer`() {
        val newer = row(id = 2L, invoiceDate = sameDate)
        val older = row(id = 1L, invoiceDate = sameDate)
        stubFindInvoiceOverview(listOf(newer, older))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        view.restoreFilterState(QueryParameters.empty())
        assertEquals(listOf(2L, 1L), displayedIds(view), "sanity: starts in DESC order")

        val column = grid(view).getColumnByKey("invoiceDate")!!
        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(listOf(1L, 2L), displayedIds(view), "ASC must show the lower id (older) first on a same-date tie")
    }

    // ── (c) pin today's null-invoiceDate behavior — Postgres DESC default is NULLS FIRST ───────

    @Test
    fun `a null-invoiceDate row sorts first under the default DESC order, same as the Postgres default`() {
        val undated = row(id = 3L, invoiceDate = null)
        val dated = row(id = 4L, invoiceDate = sameDate)
        stubFindInvoiceOverview(listOf(undated, dated))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        view.restoreFilterState(QueryParameters.empty())

        assertEquals(listOf(3L, 4L), displayedIds(view), "DESC: NULLS FIRST is Postgres's own default for `order by invoiceDate desc`, mirrored here")
    }

    @Test
    fun `a null-invoiceDate row sorts last under ascending order, same as the Postgres default`() {
        val undated = row(id = 3L, invoiceDate = null)
        val dated = row(id = 4L, invoiceDate = sameDate)
        stubFindInvoiceOverview(listOf(dated, undated))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        view.restoreFilterState(QueryParameters.empty())

        val column = grid(view).getColumnByKey("invoiceDate")!!
        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(listOf(4L, 3L), displayedIds(view), "ASC: NULLS LAST is Postgres's own default for `order by invoiceDate asc`, mirrored here")
    }
}
