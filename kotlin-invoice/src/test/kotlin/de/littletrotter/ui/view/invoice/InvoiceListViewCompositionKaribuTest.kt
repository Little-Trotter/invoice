package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListPage
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.Pager
import java.time.LocalDate
import java.util.function.Consumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceListView] as the composition root after the decomposition: the refresh cycle
 * wires filter bar, quick-filter row, grid and pager together. Facades mocked, Karibu only.
 */
class InvoiceListViewCompositionKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        whenever(uiEventBus.registerOffLock(any(), any())).thenReturn(UiEventBus.Registration { })
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // doReturn form: re-stubbing after a thenThrow stub must not invoke the throwing stub.
    private fun stubTotal(total: Long) {
        org.mockito.kotlin.doReturn(total).whenever(invoiceListQuery).count(any())
    }

    private fun stubRows(rows: List<InvoiceOverviewDto>) {
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(rows)
    }

    private fun verifyPageRequested(pageIndex: Int, pageSize: Int = 20, mode: org.mockito.verification.VerificationMode = times(1)) {
        verify(invoiceListQuery, mode).findPage(
            any(), org.mockito.kotlin.argThat<InvoiceListPage> { this.pageIndex == pageIndex && this.pageSize == pageSize },
        )
    }

    private fun row(id: Long) = InvoiceOverviewDto(
        id = id,
        invoiceNumber = "RE-2026-%05d".format(id),
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
        validationStatus = ValidationStatus.VALID,
    )

    private fun enter(view: InvoiceListView, params: Map<String, String> = emptyMap()) {
        val event: BeforeEnterEvent = mock()
        whenever(event.location).thenReturn(Location("", QueryParameters.simple(params)))
        view.beforeEnter(event)
    }

    private fun liveStatus(view: InvoiceListView): Span = view._find<Span>().single { it.element.getAttribute("aria-live") == "polite" }

    private fun pageSizeSelect(view: InvoiceListView): ComboBox<Int> {
        val f = InvoiceListView::class.java.getDeclaredField("pageSizeSelect")
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    @Test
    fun `happy path - entering the list renders the rows the facade returns and announces the page`() {
        stubTotal(2)
        stubRows(listOf(row(1), row(2)))
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        enter(view)

        assertEquals(view.tr("invoice.accessibility.pageStatus", 1, 1, 2L), liveStatus(view).text)
        verifyPageRequested(0)
        assertFalse(pageSizeSelect(view).isEnabled, "two rows fit on one page")
        assertFalse(view.emptyState.isVisible, "rows are shown, so no empty note")
    }

    @Test
    fun `boundary - an empty result still shows page 1 of 1 and no quick-filter chips`() {
        stubTotal(0)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        enter(view)

        assertEquals(view.tr("invoice.accessibility.pageStatus", 1, 1, 0L), liveStatus(view).text)
        assertTrue(view._find<NativeButton>().none { it.hasClassName("quick-filter-chip") && it.isVisible })
        assertTrue(view.emptyState.isVisible)
        assertEquals(view.tr("invoice.list.empty"), view.emptyState.text)
    }

    @Test
    fun `boundary - a URL page beyond the last page is clamped to the last existing page`() {
        stubTotal(45)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        enter(view, mapOf("page" to "99"))

        verifyPageRequested(2)
        assertEquals(view.tr("invoice.accessibility.pageStatus", 3, 3, 45L), liveStatus(view).text)
    }

    @Test
    fun `boundary - paging keeps the page, changing the page size resets to page 1 and an unchanged size is a no-op`() {
        stubTotal(45)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        enter(view)

        view._get<Pager>().goToPage(1)
        verifyPageRequested(1)

        pageSizeSelect(view).value = 50
        verifyPageRequested(0, pageSize = 50)

        pageSizeSelect(view).value = 50
        verifyPageRequested(0, pageSize = 50)
    }

    @Test
    fun `boundary - a failing facade surfaces to the error handler and does not leave the refresh guard stuck`() {
        whenever(invoiceListQuery.count(any()))
            .thenThrow(IllegalStateException("database unavailable"))
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val failure = assertThrows(IllegalStateException::class.java) { enter(view) }
        assertEquals("database unavailable", failure.message)

        stubTotal(1)
        stubRows(listOf(row(1)))
        enter(view)
        assertEquals(view.tr("invoice.accessibility.pageStatus", 1, 1, 1L), liveStatus(view).text, "the next refresh runs normally")
    }

    @Test
    fun `boundary - quick-filter chips and the reset chip drive the view's filter state`() {
        stubTotal(3)
        whenever(invoiceListQuery.countByStatus(any()))
            .thenReturn(mapOf(InvoiceStatus.SENT to 3L))
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        enter(view)

        view._find<NativeButton>().single { it.text.endsWith(" 3") }._click()
        assertEquals(InvoiceStatus.SENT, view.filterBar.statusFilter)
        assertTrue(view.filterBar.activeFilterChip.isVisible)

        view.filterBar.activeFilterChip._click()
        assertNull(view.filterBar.statusFilter)
        assertFalse(view.filterBar.activeFilterChip.isVisible)
    }

    @Test
    fun `boundary - a finished job event refreshes the list without a toast`() {
        stubTotal(0)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val ui = UI.getCurrent()!!
        ui.add(view)
        val captor = argumentCaptor<Consumer<UiEventBus.JobFinishedEvent>>()
        verify(uiEventBus).registerOffLock(eq(ui), captor.capture())

        captor.firstValue.accept(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 1L, success = true))
        PollTickTestSeam.awaitTriggeredTicks { DirectPollTicks.drain() }

        assertEquals(0, getNotifications().size)
        verifyPageRequested(0, mode = org.mockito.kotlin.atLeast(1))
    }

    @Test
    fun `happy path - the new-invoice and export buttons open their dialogs`() {
        stubTotal(0)
        val exportService: InvoiceWorkbookExportPort = mock()
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, exportService, time = FIXED_TIME_SOURCE)
        UI.getCurrent()!!.add(view)
        enter(view)

        view._get<Button> { id = INVOICE_LIST_EXPORT_BUTTON_ID }._click()
        assertEquals(1, _find<Dialog>().size, "the export dialog opens for the filtered set")

        view._get<Button> { text = view.tr("invoice.new") }._click()
        assertEquals(1, _find<InvoiceCreateDialog>().size)
    }
}
