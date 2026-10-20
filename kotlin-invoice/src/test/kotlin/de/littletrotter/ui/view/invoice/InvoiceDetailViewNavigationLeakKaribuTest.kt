package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.HasErrorParameter
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.server.VaadinService
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.SchedulePrintJobUseCase
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Proves [InvoiceDetailView]'s
 * [InvoiceDetailView.onAttach]/[InvoiceDetailView.onDetach] pair (the [UiEventBus] registration
 * plus the [PollingViewLifecycle] poll fallback both go through, see [InvoiceDetailView.onAttach]'s
 * own body) leaves no trace behind after repeated navigation: 100 into/out-of cycles on the SAME
 * [UI] and the SAME [UiEventBus] instance (exactly what a real browser tab re-navigating within one
 * session does) must return both the bus listener count and the UI's poll interval to whatever they
 * were before the very first attach — the two structural preconditions a "sessions no longer
 * usable after repeated navigation" symptom needs (a leaked listener keeps the detached view, and
 * therefore its [UI], reachable from the bus forever).
 *
 * The functional half of the proof matters more than the raw count: after the 100 cycles, a single
 * [UiEventBus.publish] for the currently attached (101st) view must reach [InvoiceDetailQueryPort]
 * exactly once — not 101 times, which is what would happen if any of the 100 detached views had
 * kept its bus registration alive.
 */
class InvoiceDetailViewNavigationLeakKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val generationJobQueryPort: GenerationJobQueryPort = mock()
    private val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
    private val uiEventBus = UiEventBus()

    @BeforeEach
    fun setup() {
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun createView(): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val receiptPort = mock<InvoiceReceiptQueryPort>().apply {
            whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
        }
        val attachReceiptsUseCase = mock<AttachReceiptsUseCase>()
        val receiptDialogLauncher = ReceiptDialogLauncher(receiptPort, attachReceiptsUseCase, invoiceDetailQueryPort, mock())
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = uiEventBus,
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(receiptPort, attachReceiptsUseCase, receiptDialogLauncher),
                exportPanel = testExportPanel(
                    invoiceDetailQueryPort,
                    generationJobQueryPort,
                    schedulePrintJobUseCase,
                ),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
    }

    private fun dto(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun beforeEnterEvent(ui: UI): BeforeEnterEvent {
        val router = VaadinService.getCurrent().router
        return BeforeEnterEvent(
            router, NavigationTrigger.PROGRAMMATIC, Location("invoices/detail", QueryParameters.empty()),
            InvoiceDetailView::class.java, RouteParameters("id", "1"), ui, emptyList(),
        )
    }

    private fun findInvoiceDetailDtoCallCount(): Int =
        mockingDetails(detailQueryPort).invocations.count { it.method.name == "findInvoiceDetailDto" }

    @Test
    fun `100 navigate-in-out cycles leave the bus listener count and poll interval at baseline`() {
        val ui = UI.getCurrent()!!
        val baselineListenerCount = uiEventBus.listenerCount()
        val baselinePollInterval = ui.pollInterval

        repeat(100) {
            val view = createView()
            ui.add(view) // onAttach: registers on uiEventBus, arms the poll fallback
            view.beforeEnter(beforeEnterEvent(ui))
            ui.remove(view) // onDetach: must undo both registrations
        }

        assertEquals(
            baselineListenerCount, uiEventBus.listenerCount(),
            "100 navigate-in/out cycles must leave the UiEventBus listener count at its pre-navigation baseline",
        )
        assertEquals(
            baselinePollInterval, ui.pollInterval,
            "100 navigate-in/out cycles must leave the UI's poll interval restored to its pre-navigation baseline",
        )
    }

    @Test
    fun `after 100 navigate-in-out cycles a job-finished event reaches only the currently attached view, not 100 accumulated ghost listeners`() {
        val ui = UI.getCurrent()!!

        repeat(100) {
            val view = createView()
            ui.add(view)
            view.beforeEnter(beforeEnterEvent(ui))
            ui.remove(view)
        }

        val finalView = createView()
        ui.add(finalView)
        finalView.beforeEnter(beforeEnterEvent(ui))
        val callsBeforePublish = findInvoiceDetailDtoCallCount()

        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 1L, invoiceId = 1L, success = true))
        // the event is handled off the session lock: wait for the load before counting its queries
        PollTickTestSeam.awaitTriggeredTicks { MockVaadin.runUIQueue(true) }
        MockVaadin.clientRoundtrip()

        assertEquals(
            callsBeforePublish + 1, findInvoiceDetailDtoCallCount(),
            "exactly one listener (the currently attached view) must react to the event; " +
                "a higher count means a detached view from an earlier navigation cycle stayed registered",
        )
    }
}
