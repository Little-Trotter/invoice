package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.server.VaadinService
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.SchedulePrintJobUseCase
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceDetailView.beforeEnter] (the actual route entry point) had no test at
 * all: every other test in this module constructs [InvoiceDetailView] and calls `renderInvoice`/
 * `buildEntryPane`/etc. directly, bypassing routing entirely. Covers the missing-id guard, the
 * non-numeric-id guard (both fold into the same `invalidId` error render), the not-found guard,
 * and the successful-render path — [BeforeEnterEvent] constructed directly against the current
 * `Router`/`UI`, same technique as [de.littletrotter.ui.MainLayoutAccessControlTest]'s own
 * `beforeEnterEvent` helper (its KDoc explains why: the real route target needs no CDI-backed
 * construction this way, since only the event's target `Class` matters, never an instance).
 */
class InvoiceDetailViewBeforeEnterKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()

    // renderReceiptsTab fetches the invoice-wide budget unconditionally on every
    // render; an unstubbed mock returns null there and NPEs, so every InvoiceReceiptQueryPort
    // double this file builds goes through this helper instead of a bare mock().
    private fun defaultInvoiceReceiptQueryPort(): InvoiceReceiptQueryPort = mock<InvoiceReceiptQueryPort>().apply {
        whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun createView(): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(defaultInvoiceReceiptQueryPort(), mock(), ReceiptDialogLauncher(defaultInvoiceReceiptQueryPort(), mock(), invoiceDetailQueryPort, mock())),
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
    }

    private fun dto(id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun beforeEnterEvent(target: Class<out Component>, params: RouteParameters): BeforeEnterEvent {
        val router = VaadinService.getCurrent().router
        val ui = requireNotNull(UI.getCurrent())
        return BeforeEnterEvent(router, NavigationTrigger.PROGRAMMATIC, Location("invoices/detail"), target, params, ui, emptyList())
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    /** [view].exportPanel now sits inside the real "Versionen" `TabSheet` tab (rendered
     *  by `beforeEnter` itself); a `TabSheet` disables non-selected tab content, so a test that
     *  clicks into the panel must select this tab first, same as the `focus=validation` query
     *  parameter [InvoiceDetailView.beforeEnter] itself honors for real navigations. */
    private fun selectVersionsTab(view: InvoiceDetailView) {
        val tabs = requireNotNull(view.detailTabs.tabSheet)
        val versionsIndex = (0 until tabs.tabCount).first { tabs.getTabAt(it).label == UiTranslations.tr("invoiceDetail.tab.versions") }
        tabs.selectedIndex = versionsIndex
    }

    @Test
    fun `no id route parameter renders the invalid-id error and never queries the facade`() {
        val view = createView()

        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters.empty()))

        verify(detailQueryPort, never()).findInvoiceDetailDto(any())
        val text = flatten(view).joinToString(" ") { (it as? com.vaadin.flow.component.html.Paragraph)?.text ?: "" }
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.error.invalidId")), text)
    }

    @Test
    fun `a non-numeric id route parameter also renders the invalid-id error`() {
        val view = createView()

        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters("id", "not-a-number")))

        verify(detailQueryPort, never()).findInvoiceDetailDto(any())
        val text = flatten(view).joinToString(" ") { (it as? com.vaadin.flow.component.html.Paragraph)?.text ?: "" }
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.error.invalidId")), text)
    }

    @Test
    fun `an id the facade cannot resolve renders the not-found error`() {
        whenever(detailQueryPort.findInvoiceDetailDto(99L)).thenReturn(null)
        val view = createView()

        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters("id", "99")))

        val text = flatten(view).joinToString(" ") { (it as? com.vaadin.flow.component.html.Paragraph)?.text ?: "" }
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.error.notFound", "99")), text)
        assertNull(view.invoice)
    }

    @Test
    fun `a resolvable id renders the invoice and sets no error`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView()

        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters("id", "1")))

        assertEquals(1L, view.invoice?.id)
        val headings = flatten(view).filterIsInstance<H2>()
        assertTrue(headings.none { it.text == UiTranslations.tr("nav.invoices") }, "a successful render must not show the error view's heading")
    }

    // ── the error view's own back-to-list button reaches the navigate call ─────────────────────

    @Test
    fun `the error view's back button reaches the navigate call to InvoiceListView`() {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceListView::class.java)
        val view = createView()
        val ui = UI.getCurrent()!!
        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters.empty()))
        ui.add(view)
        MockVaadin.clientRoundtrip()

        flatten(view).filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("invoiceDetail.backToList") }._click()

        assertEquals("", ui.internals.activeViewLocation.path)
    }

    // ── buildExportPanel's own onValidationFailure/onPrintScheduled wiring (not the copy
    // InvoiceDetailViewValidationBannerTest builds by hand for the panel-only contract) ─────────

    @Test
    fun `buildExportPanel's onValidationFailure callback shows the view's own validation banner`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val schedulePrintJobUseCase = mock<SchedulePrintJobUseCase>()
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        val ex = de.littletrotter.domain.exception.InvoiceValidationException(de.littletrotter.domain.exception.InvoiceValidationError.RECIPIENT_MISSING)
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any())).thenThrow(ex)
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(emptyList())
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val view = InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(
                    defaultInvoiceReceiptQueryPort(),
                    mock(),
                    ReceiptDialogLauncher(defaultInvoiceReceiptQueryPort(), mock(), invoiceDetailQueryPort, mock()),
                ),
                exportPanel = testExportPanel(
                    invoiceDetailQueryPort,
                    generationJobQueryPort,
                    schedulePrintJobUseCase,
                ),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters("id", "1")))
        selectVersionsTab(view)

        val panel = view.detailTabs.exportPanel
        val printButton = flatten(panel).filterIsInstance<Button>().first { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }
        printButton._click()

        val banner = flatten(view).filterIsInstance<ValidationFailureBanner>().first()
        assertTrue(banner.isVisible, "the view's own banner (wired through buildExportPanel, not a hand-built copy) must show")
    }

    @Test
    fun `buildExportPanel's onPrintScheduled callback hides the view's own validation banner`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val schedulePrintJobUseCase = mock<SchedulePrintJobUseCase>()
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        whenever(schedulePrintJobUseCase.schedulePrintJob(any(), any()))
            .thenReturn(123L)
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(emptyList())
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val view = InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(
                    defaultInvoiceReceiptQueryPort(),
                    mock(),
                    ReceiptDialogLauncher(defaultInvoiceReceiptQueryPort(), mock(), invoiceDetailQueryPort, mock()),
                ),
                exportPanel = testExportPanel(
                    invoiceDetailQueryPort,
                    generationJobQueryPort,
                    schedulePrintJobUseCase,
                ),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
        view.beforeEnter(beforeEnterEvent(InvoiceDetailView::class.java, RouteParameters("id", "1")))
        selectVersionsTab(view)
        val banner = flatten(view).filterIsInstance<ValidationFailureBanner>().first()
        banner.show("stale error")
        assertTrue(banner.isVisible, "sanity check: banner must start visible")

        val panel = view.detailTabs.exportPanel
        val printButton = flatten(panel).filterIsInstance<Button>().first { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }
        printButton._click()

        assertFalse(banner.isVisible, "a successful print through buildExportPanel's own wiring must hide the stale banner")
    }
}
