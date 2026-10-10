package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.time.OffsetDateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [InvoiceDetailView]'s "Journal" tab: shown (with entries) when
 * an audit journal source is wired, simply absent (not broken) when it
 * isn't — see [InvoiceDetailTabs.buildJournalPanel]'s own KDoc. Also pins the tab ORDER
 * requirement noted at the `ts.add(...)` call site: Journal follows "E-Rechnung" as the final tab.
 */
class InvoiceDetailViewJournalTabTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()

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

    private fun createView(auditJournalQueryService: AuditJournalQueryService? = null): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryService = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryService = generationJobQueryService,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(defaultInvoiceReceiptQueryPort(), mock(), ReceiptDialogLauncher(defaultInvoiceReceiptQueryPort(), mock(), invoiceDetailQueryPort)),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
                auditJournalQueryService = auditJournalQueryService,
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
    }

    private fun actionLine(seq: Long, businessTx: Long = seq): InvoiceAuditActionLine = InvoiceAuditActionLine(
        businessTx = businessTx, action = InvoiceAuditAction.UNSPECIFIED,
        actor = "oidc-sub-1", actorName = "alice",
        occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
        invoiceIds = listOf(1L), invoiceNumbers = listOf("INV-1"),
        rows = listOf(
            InvoiceAuditJournalEntry(
                seq = seq, businessTx = businessTx, invoiceId = 1L, invoiceNumber = "INV-1",
                actor = "oidc-sub-1", actorName = "alice",
                occurredAt = OffsetDateTime.parse("2026-09-04T10:00:00Z"),
                event = InvoiceAuditEvent.INVOICE_CREATED, action = InvoiceAuditAction.UNSPECIFIED,
                detail = """{"invoice_number": "INV-1"}""",
                documentSha256 = null, prevHash = "genesis", entryHash = "hash-$seq",
            ),
        ),
    )

    private fun dto(id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun tabLabels(view: InvoiceDetailView): List<String> {
        val tabSheet = requireNotNull(view.detailTabs.tabSheet) { "expected a rendered (non-entry-mode) invoice" }
        return (0 until tabSheet.tabCount).map { tabSheet.getTabAt(it).label }
    }

    @Test
    fun `no auditJournalQueryService wired - no Journal tab, no crash`() {
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView(auditJournalQueryService = null)

        view.renderInvoice(dto(1L))

        assertFalse(tabLabels(view).contains(UiTranslations.tr("invoiceDetail.tab.journal")))
    }

    @Test
    fun `auditJournalQueryService wired - Journal tab appears after the E-invoice tab`() {
        val journalQueryService: AuditJournalQueryService = mock()
        whenever(journalQueryService.findActionsByInvoice(any(), any(), any(), any())).thenReturn(
            listOf(actionLine(seq = 1L)),
        )
        whenever(journalQueryService.countActionsByInvoice(1L)).thenReturn(1L)
        val view = createView(auditJournalQueryService = journalQueryService)

        view.renderInvoice(dto(1L))

        val labels = tabLabels(view)
        val journalIndex = labels.indexOf(UiTranslations.tr("invoiceDetail.tab.journal"))
        val versionsIndex = labels.indexOf(UiTranslations.tr("invoiceDetail.tab.versions"))
        assertTrue(journalIndex >= 0, "expected a Journal tab, got: $labels")
        assertEquals(versionsIndex + 1, journalIndex, "Journal must sit immediately AFTER E-Rechnung")
        assertEquals(labels.lastIndex, journalIndex, "Journal must be the final invoice-detail tab")
    }

    @Test
    fun `the Journal tab's panel actually shows the fetched entry`() {
        val journalQueryService: AuditJournalQueryService = mock()
        whenever(journalQueryService.findActionsByInvoice(any(), any(), any(), any())).thenReturn(
            listOf(actionLine(seq = 7L, businessTx = 7L)),
        )
        whenever(journalQueryService.countActionsByInvoice(1L)).thenReturn(1L)
        val view = createView(auditJournalQueryService = journalQueryService)

        val panel = view.detailTabs.buildJournalPanel(dto(1L))

        assertTrue(panel != null)
        val items = panel!!.grid.dataProvider.fetch(Query()).toList()
        assertEquals(listOf(7L), items.map { it.businessTx })
    }

    @Test
    fun `focus=validation still opens the E-invoice tab when the Journal tab follows it`() {
        val journalQueryService: AuditJournalQueryService = mock()
        whenever(journalQueryService.findActionsByInvoice(any(), any(), any(), any())).thenReturn(emptyList())
        whenever(journalQueryService.countActionsByInvoice(any())).thenReturn(0L)
        whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(dto(1L))
        val view = createView(auditJournalQueryService = journalQueryService)

        val event = com.vaadin.flow.router.BeforeEnterEvent(
            com.vaadin.flow.server.VaadinService.getCurrent().router,
            com.vaadin.flow.router.NavigationTrigger.PROGRAMMATIC,
            com.vaadin.flow.router.Location("invoice/1", com.vaadin.flow.router.QueryParameters.simple(mapOf("focus" to "validation"))),
            InvoiceDetailView::class.java,
            com.vaadin.flow.router.RouteParameters("id", "1"),
            requireNotNull(com.vaadin.flow.component.UI.getCurrent()),
            emptyList(),
        )

        view.beforeEnter(event)

        val tabSheet = requireNotNull(view.detailTabs.tabSheet)
        assertEquals(UiTranslations.tr("invoiceDetail.tab.versions"), tabSheet.getTabAt(tabSheet.selectedIndex).label)
    }
}
