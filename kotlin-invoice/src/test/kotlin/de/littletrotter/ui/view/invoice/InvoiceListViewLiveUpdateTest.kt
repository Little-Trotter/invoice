package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._size
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO report 30.08.2026 — pins the poll-fallback fix for [InvoiceListView], the third and last view
 * with the "worker finishes in a separate process, the in-memory [UiEventBus] never fires in this
 * JVM" gap already closed on [InvoiceDetailView] ([InvoiceDetailViewPollRefreshTest]) and
 * [PrintJobListView] ([PrintJobListViewLiveUpdateTest]). Same mechanism: a poll-interval fallback
 * capped at 2000ms, active only while the currently rendered page still carries a
 * [ValidationStatus.PENDING] row (the only non-terminal signal [InvoiceOverviewDto] exposes), plus
 * one final refresh on the tick where the last such row leaves the page — the tick that carries a
 * PENDING → VALID/INVALID/UNAVAILABLE transition into the "Validierung läuft" chip.
 */
class InvoiceListViewLiveUpdateTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()

        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(emptyList())
        stubOverview(emptyList())
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): InvoiceListView = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

    private fun firePoll(ui: UI) {
        DirectPollTicks.firePoll(ui)
    }

    private fun row(validationStatus: ValidationStatus): InvoiceOverviewDto = InvoiceOverviewDto(
        id = 1L,
        invoiceNumber = "RE-2026-00001",
        typeCode = null,
        sellerName = "Seller",
        sellerCity = null,
        recipientName = "Acme",
        grossAmount = null,
        paymentLabel = null,
        freeText = null,
        itemsCount = 1,
        invoiceDate = LocalDate.of(2026, 8, 26),
        status = InvoiceStatus.SENT,
        validationStatus = validationStatus,
    )

    // filterText/status/reverseCharge/validationStatus/recipientId/senderProfileId/dateFrom/dateTo
    // are all null in these tests (no filter set on the toolbar) — mockito-kotlin's typed any()
    // does not match null (see countInvoiceOverviewGroupedByValidationStatus's own KDoc comment in
    // InvoiceListViewKaribuTest for the same caveat), so every nullable parameter below is matched
    // with anyOrNull() instead.
    //
    // countInvoiceOverview is stubbed with a dynamic answer (not a fixed thenReturn) because
    // InvoiceListView.refreshTable calls it once with the toolbar's own validationStatusFilter
    // (null in every test here) and hasPendingValidationInResultSet calls it again with an
    // explicit PENDING override — a fixed stub can't distinguish "total rows" from "PENDING rows"
    // across those two call shapes, which is exactly the bug this dynamic answer avoids.
    private fun stubOverview(rows: List<InvoiceOverviewDto>) {
        whenever(
            invoiceListQuery.count(any()),
        ).thenAnswer { invocation ->
            val requestedStatus = invocation.getArgument<InvoiceExportFilter>(0).validationStatus
            rows.count { requestedStatus == null || it.validationStatus == requestedStatus }.toLong()
        }
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(rows)
    }

    private fun verifyFindInvoiceOverviewCallCount(expected: Int) {
        verify(invoiceListQuery, times(expected)).findPage(any(), any())
    }

    @Test
    fun `onAttach caps the poll interval at 2000ms and onDetach restores whatever the UI had before`() {
        val ui = UI.getCurrent()!!
        ui.pollInterval = 9_000

        val view = newView()
        ui.add(view)
        assertEquals(2_000, ui.pollInterval)

        ui.remove(view)
        assertEquals(9_000, ui.pollInterval)
    }

    @Test
    fun `onAttach leaves an already-fast poll interval untouched`() {
        val ui = UI.getCurrent()!!
        ui.pollInterval = 1_000

        val view = newView()
        ui.add(view)

        assertEquals(1_000, ui.pollInterval)
    }

    @Test
    fun `a poll tick refreshes the table while a PENDING row is visible on the current page`() {
        stubOverview(listOf(row(ValidationStatus.PENDING)))

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view) // triggers onAttach — registers on uiEventBus, arms the poll fallback
        view.restoreFilterState(QueryParameters.empty()) // initial load, one findInvoiceOverview call

        verifyFindInvoiceOverviewCallCount(1)

        firePoll(ui)

        verifyFindInvoiceOverviewCallCount(2)
    }

    @Test
    fun `one final refresh fires on the tick where the last PENDING row leaves the page`() {
        stubOverview(listOf(row(ValidationStatus.PENDING)))

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.restoreFilterState(QueryParameters.empty()) // call #1

        firePoll(ui) // call #2 — PENDING row still visible
        verifyFindInvoiceOverviewCallCount(2)

        // The job resolves: the next fetched page no longer carries a PENDING row.
        stubOverview(listOf(row(ValidationStatus.VALID)))

        firePoll(ui) // call #3 — the completion tick, must still refresh
        verifyFindInvoiceOverviewCallCount(3)

        firePoll(ui) // call #4 would refresh again only if the poll never quiesces — it must not
        verifyFindInvoiceOverviewCallCount(3)
    }

    @Test
    fun `F4 the first poll tick after attach always refreshes once, then further ticks with no PENDING row do not`() {
        stubOverview(listOf(row(ValidationStatus.VALID)))

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.restoreFilterState(QueryParameters.empty()) // call #1, no PENDING rows

        verifyFindInvoiceOverviewCallCount(1)

        // F4 (fix-job-panel-flicker-and-vanishing-entry) — sawPendingValidationInResultSet is
        // seeded true on attach, so the very next poll tick always refreshes once even though no
        // row here is PENDING — this closes the "validation triggered elsewhere finished its
        // whole PENDING lifetime between navigating here and the first tick" hole, the sibling fix
        // to InvoiceDetailView's own onPrintScheduled seed and PrintJobListView's onAttach seed.
        firePoll(ui) // call #2 — the guaranteed tick
        verifyFindInvoiceOverviewCallCount(2)

        // A further, consecutive tick with still nothing PENDING must NOT refresh again.
        firePoll(ui)
        verifyFindInvoiceOverviewCallCount(2)
    }

    @Test
    fun `a result loaded for a state the user has since left is not rendered`() {
        stubOverview(listOf(row(ValidationStatus.VALID)))
        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.restoreFilterState(QueryParameters.empty())
        val grid = view._get<Grid<*>>()
        val before = grid._size()

        // The user toggles a quick filter while the poll's page query is still running off-lock.
        whenever(invoiceListQuery.findPage(any(), any())).thenAnswer {
            view.filterBar.statusFilter = InvoiceStatus.DRAFT
            listOf(row(ValidationStatus.VALID), row(ValidationStatus.VALID))
        }

        firePoll(ui)

        assertEquals(before, grid._size(), "the stale two-row result must be dropped")
    }

    @Test
    fun `a result for an unchanged state is rendered`() {
        stubOverview(listOf(row(ValidationStatus.VALID)))
        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.restoreFilterState(QueryParameters.empty())
        val grid = view._get<Grid<*>>()

        stubOverview(listOf(row(ValidationStatus.VALID), row(ValidationStatus.VALID)))
        firePoll(ui)

        assertEquals(2, grid._size())
    }

    @Test
    fun `a failing list query during a poll tick leaves the rendered page untouched`() {
        stubOverview(listOf(row(ValidationStatus.VALID)))
        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.restoreFilterState(QueryParameters.empty())
        val grid = view._get<Grid<*>>()
        whenever(invoiceListQuery.findPage(any(), any())).thenThrow(IllegalStateException("db down"))

        firePoll(ui)

        assertEquals(1, grid._size())
    }
}
