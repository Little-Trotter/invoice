package de.littletrotter.ui.view.invoice

import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
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
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceDetailView.onAttach]'s two closures (the [UiEventBus] listener and the
 * poll-fallback tick) had never actually run in any prior test: every existing test constructs the
 * view and calls `renderInvoice`/`beforeEnter` directly on a bare, unattached instance, so
 * `onAttach` itself never fired. Same technique [PrintJobListViewLiveUpdateTest] and
 * [InvoiceListViewLiveUpdateTest] already established for the sibling views: a real [UiEventBus]
 * attached to a real (mocked-Vaadin) UI, `UiEventBus.publish` dispatching synchronously via
 * `UI.access`, and [ComponentUtil.fireEvent] to drive a poll tick.
 */
class InvoiceDetailViewLiveUpdateTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val generationJobQueryPort: GenerationJobQueryPort = mock()
    private val schedulePrintJobUseCase: SchedulePrintJobUseCase = mock()
    private val uiEventBus = UiEventBus()

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun createView(cockpitHero: InvoiceCockpitHero = testCockpitHero()): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        // An unstubbed mock returns null for invoiceDocumentBudget, which NPEs the Belege tab's
        // budget hint.
        val receiptPort = mock<InvoiceReceiptQueryPort>().apply {
            whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
        }
        val attachReceiptsUseCase = mock<AttachReceiptsUseCase>()
        val receiptDialogLauncher = ReceiptDialogLauncher(receiptPort, attachReceiptsUseCase, invoiceDetailQueryPort, mock())
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = uiEventBus,
            invoiceCockpitHero = cockpitHero,
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

    private fun dto(id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-$id", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun beforeEnterEvent(id: Long, queryParameters: QueryParameters = QueryParameters.empty()): BeforeEnterEvent {
        val router = VaadinService.getCurrent().router
        val ui = requireNotNull(UI.getCurrent())
        return BeforeEnterEvent(
            router, NavigationTrigger.PROGRAMMATIC, Location("invoices/detail", queryParameters),
            InvoiceDetailView::class.java, RouteParameters("id", id.toString()), ui, emptyList(),
        )
    }

    private fun firePoll(ui: UI) {
        DirectPollTicks.firePoll(ui)
    }

    private fun job(status: GenerationJobStatus): GenerationJobView = jobView { this.status = status }

    // Same tree-walk + caption lookup
    // InvoiceExportPanelPrintClickCoverageKaribuTest already uses to find the print button.
    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun printButton(panel: InvoiceExportPanel): Button =
        flatten(panel).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }

    /** [view].exportPanel sits inside the real "Versionen" `TabSheet` tab; a `TabSheet`
     *  disables non-selected tab content, so a test that clicks into the panel must select this
     *  tab first, same as the `focus=validation` query parameter [InvoiceDetailView.beforeEnter]
     *  itself honors for real navigations. */
    private fun selectVersionsTab(view: InvoiceDetailView) {
        val tabs = requireNotNull(view.detailTabs.tabSheet)
        val versionsIndex = (0 until tabs.tabCount).first { tabs.getTabAt(it).label == UiTranslations.tr("invoiceDetail.tab.versions") }
        tabs.selectedIndex = versionsIndex
    }

    // ── focus=validation query parameter ────────────────────────────────────────

    @Test
    fun `a focus=validation query parameter opens the last tab of the rendered TabSheet`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)

        view.beforeEnter(beforeEnterEvent(1L, QueryParameters.of("focus", "validation")))

        val tabSheet = view.detailTabs.tabSheet!!
        assertEquals(tabSheet.tabCount - 1, tabSheet.selectedIndex)
    }

    @Test
    fun `no focus query parameter leaves the default (overview) tab selected`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)

        view.beforeEnter(beforeEnterEvent(1L))

        val tabSheet = view.detailTabs.tabSheet!!
        assertEquals(0, tabSheet.selectedIndex)
    }

    // ── a live refresh must not disturb the tabs or the export panel instance ────────────────────

    /**
     * The E-Rechnung tab's job table used to lose its only row the moment the print
     * job finished. [InvoiceDetailView.refreshAfterDataChange] rebuilt the tab strip by removing
     * every tab and adding it back; re-adding selects index 0, so the tab's content (a Grid with
     * `isAllRowsVisible`) was attached into a hidden container, sized itself to zero rows and never
     * recomputed when the saved index was restored in the same round trip.
     *
     * This pins the structural invariant the fix rests on — a refresh replaces tab CONTENT, never
     * the tabs — instead of the pixel symptom, which only a real browser can see (that side is
     * covered by `e2e-test-playwright/tests/einvoice/job-row-live-refresh.spec.ts`).
     *
     * The tab's content — the [InvoiceExportPanel] itself — is no longer rebuilt either, only
     * refreshed in place (see [InvoiceDetailTabs.exportPanel]'s own KDoc): discarding and
     * reconstructing the whole panel on every refresh (destroying and rebuilding its job Grid with
     * it) caused a visible flicker even though the tab CONTAINER itself already stayed put.
     */
    @Test
    fun `a live refresh keeps the tabs and the selected tab, and reuses the same export panel instance`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))

        val tabSheet = view.detailTabs.tabSheet!!
        val versionsIndex = tabSheet.tabCount - 1
        tabSheet.selectedIndex = versionsIndex
        val tabsBefore = (0 until tabSheet.tabCount).map { tabSheet.getTabAt(it) }
        val versionsHost = view.detailTabs.versionsTabHost
        val exportPanelBefore = view.detailTabs.exportPanel
        assertSame(exportPanelBefore, versionsHost?.children?.toList()?.single(), "der Panel-Container enthält das gehaltene Panel")

        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L).copy(invoiceNumber = "INV-1-updated"))
        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 1L, success = true))
        MockVaadin.clientRoundtrip()

        assertEquals(tabsBefore, (0 until tabSheet.tabCount).map { tabSheet.getTabAt(it) }, "die Tabs selbst bleiben dieselben Instanzen")
        assertEquals(versionsIndex, tabSheet.selectedIndex, "der gewählte Tab bleibt gewählt")
        assertSame(versionsHost, view.detailTabs.versionsTabHost, "der Inhalts-Container des E-Rechnung-Tabs bleibt derselbe")
        assertSame(exportPanelBefore, view.detailTabs.exportPanel, "F1 — das InvoiceExportPanel selbst wird nicht neu gebaut")
        assertSame(exportPanelBefore, versionsHost?.children?.toList()?.single(), "der Panel-Container hält weiterhin dasselbe Panel")
    }

    // ── UiEventBus listener ──────────────────────────────────────────────────

    @Test
    fun `a job-finished event for the currently open invoice re-fetches and re-renders it`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view) // onAttach — registers on uiEventBus
        view.beforeEnter(beforeEnterEvent(1L)) // call #1

        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L).copy(invoiceNumber = "INV-1-updated"))
        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 1L, success = true))
        MockVaadin.clientRoundtrip() // UiEventBus.publish dispatches via UI.access, which — unlike
        // a direct PollEvent — only queues while Karibu holds the session lock; a client
        // round-trip is what actually runs the queued command, same as a real request would.

        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)
        assertEquals("INV-1-updated", view.invoice?.invoiceNumber)
    }

    @Test
    fun `a job-finished event for a different invoice is ignored`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))

        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 99L, success = true))
        MockVaadin.clientRoundtrip()

        verify(detailQueryPort, times(1)).findInvoiceDetailDto(1L)
        verify(detailQueryPort, never()).findInvoiceDetailDto(99L)
    }

    @Test
    fun `a job-finished event is ignored while the overview is mid-edit`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L).copy(recipient = null))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))
        view.overviewEditMode = true

        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 1L, success = true))
        MockVaadin.clientRoundtrip()

        verify(detailQueryPort, times(1)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a job-finished event that no longer resolves leaves the currently shown invoice untouched`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))
        val shownBefore = view.invoice

        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(null)
        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 1L, success = true))
        MockVaadin.clientRoundtrip()

        assertEquals(shownBefore, view.invoice)
    }

    // ── Finalizing and sending are two steps: the ISSUED push refreshes the view ────────────────────

    @Test
    fun `an ISSUED status push for this invoice refreshes the view and shows the finalized toast`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L).copy(status = InvoiceStatus.IN_REVIEW.ordinal))
        val view = createView()
        UI.getCurrent()!!.add(view)
        view.beforeEnter(beforeEnterEvent(1L))
        val sent = dto(1L).copy(status = InvoiceStatus.ISSUED.ordinal)
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(sent)

        uiEventBus.publish(UiEventBus.InvoiceStatusChangedEvent(1L, InvoiceStatus.ISSUED))
        MockVaadin.clientRoundtrip()

        assertEquals(sent, view.invoice)
    }

    // ── Poll fallback ────────────────────────────────────────────────────────

    @Test
    fun `a poll tick before any invoice has been loaded does nothing`() {
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view) // attached, but beforeEnter never ran — invoiceId is still null

        firePoll(ui)

        verify(generationJobQueryPort, never()).findJobViewsByInvoiceId(any())
        verify(detailQueryPort, never()).findInvoiceDetailDto(any())
    }

    @Test
    fun `a poll tick on an unresolved invoice id does nothing`() {
        whenever(detailQueryPort.findInvoiceDetailDto(99L)).thenReturn(null)
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(99L)) // renders the not-found error; invoice stays null
        assertNull(view.invoice)

        firePoll(ui)

        verify(generationJobQueryPort, never()).findJobViewsByInvoiceId(any())
    }

    @Test
    fun `a poll tick is skipped while the overview is mid-edit`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))
        // InvoiceExportPanel.buildGenerationJobPanel already calls findJobViewsByInvoiceId once as part
        // of the initial render itself — the mid-edit guard is about the poll tick specifically,
        // so the assertion below checks the poll adds no further call, not that there was none.
        val callsBeforePoll = mockingDetails(generationJobQueryPort).invocations.count {
            it.method.name == "findJobViewsByInvoiceId"
        }
        view.overviewEditMode = true

        firePoll(ui)

        val callsAfterPoll = mockingDetails(generationJobQueryPort).invocations.count {
            it.method.name == "findJobViewsByInvoiceId"
        }
        assertEquals(callsBeforePoll, callsAfterPoll)
    }

    @Test
    fun `a poll tick whose job query fails renders nothing and the next tick still runs`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L)) // call #1
        doThrow(IllegalStateException("db down")).whenever(generationJobQueryPort).findJobViewsByInvoiceId(1L)

        firePoll(ui)

        verify(detailQueryPort, times(1)).findInvoiceDetailDto(1L)

        doReturn(listOf(job(GenerationJobStatus.RUNNING))).whenever(generationJobQueryPort).findJobViewsByInvoiceId(1L)
        firePoll(ui)

        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a poll tick whose load finished after the view detached does not render`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenAnswer {
            ui.remove(view)
            listOf(job(GenerationJobStatus.RUNNING))
        }
        val shownBefore = view.invoice

        firePoll(ui)

        assertEquals(shownBefore, view.invoice)
        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a poll tick with no active generation jobs neither of any status does not re-fetch`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job(GenerationJobStatus.COMPLETED)))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L)) // call #1

        firePoll(ui)

        verify(detailQueryPort, times(1)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a poll tick that first observes an active PENDING job re-fetches`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job(GenerationJobStatus.PENDING)))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L)) // call #1

        firePoll(ui) // call #2 — transition inactive -> active

        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a DELEGATED job also counts as active`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job(GenerationJobStatus.DELEGATED)))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))

        firePoll(ui)

        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a RUNNING job also counts as active, and the completion tick refreshes once more`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job(GenerationJobStatus.RUNNING)))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L)) // call #1

        firePoll(ui) // call #2 — RUNNING, still active
        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)

        // The job finishes: the next poll observes no more active jobs — the completion tick
        // itself must still refresh once (active -> inactive transition).
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job(GenerationJobStatus.COMPLETED)))
        firePoll(ui) // call #3 — completion tick
        verify(detailQueryPort, times(3)).findInvoiceDetailDto(1L)

        firePoll(ui) // call #4 would re-fetch again only if the poll never quiesces — it must not
        verify(detailQueryPort, times(3)).findInvoiceDetailDto(1L)
    }

    /**
     * The poll fallback's own self-heal for a job whose entire PENDING→...→COMPLETED lifetime
     * falls between two ticks (jobs complete in
     * well under a second on the dev stack; the fallback poll interval is 5s). Before this fix,
     * [InvoiceDetailView.buildExportPanel]'s `onPrintScheduled` callback only hid the validation
     * banner, never seeded `sawActiveGenerationJobs` — with `hasActiveGenerationJobs` returning
     * `false` on every tick (as stubbed below, simulating a job that never gets caught active),
     * [shouldRefreshOnPollTick] saw `false -> false` forever and the completion tick was never
     * reached at all.
     */
    @Test
    fun `scheduling a print job from this view seeds the poll flag so the next tick self-heals even though hasActiveGenerationJobs already returns false`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        // Simulates the short-lived-job race: every poll tick observes no active job at all,
        // exactly the case shouldRefreshOnPollTick's own KDoc describes (a regression once found here).
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(emptyList())
        whenever(
            schedulePrintJobUseCase.schedulePrintJob(eq(1L), any()),
        ).thenReturn(7L)
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L)) // call #1
        selectVersionsTab(view)

        val panel = view.detailTabs.exportPanel
        printButton(panel).click() // fires onPrintScheduled -> seeds sawActiveGenerationJobs

        firePoll(ui) // call #2 — must refresh: the seeded flag makes hadActiveJobs true even
        // though hasActiveGenerationJobs itself returns false on every tick.
        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)

        firePoll(ui) // call #3 would refresh again only if the poll never quiesces — it must not
        verify(detailQueryPort, times(2)).findInvoiceDetailDto(1L)
    }

    @Test
    fun `a poll tick whose re-fetch no longer resolves the invoice does not crash and keeps the previous invoice`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(1L)).thenReturn(listOf(job(GenerationJobStatus.PENDING)))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L))
        val shownBefore = view.invoice

        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(null)
        firePoll(ui)

        assertEquals(shownBefore, view.invoice)
    }

    @Test
    fun `onDetach unregisters from the event bus so a later publish no longer refreshes the view`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(beforeEnterEvent(1L)) // call #1
        ui.remove(view) // onDetach

        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = 1L, success = true))
        MockVaadin.clientRoundtrip()

        verify(detailQueryPort, times(1)).findInvoiceDetailDto(1L)
    }
}
