package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Money
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Entry mode replaces the cockpit hero and the Positionen/Versionen tabs with a frozen
 * context bar — never both at once.
 */
class InvoiceDetailViewEntryWorkspaceTest {

    private fun createView(): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryService = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = mock(),
            generationJobQueryService = generationJobQueryService,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(mock(), mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock<InvoiceReceiptQueryPort>().apply {
                    whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
                }, mock(), ReceiptDialogLauncher(mock<InvoiceReceiptQueryPort>().apply {
                    whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
                }, mock(), invoiceDetailQueryPort)),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
    }

    private fun draftInvoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L,
        invoiceNumber = "INV-2026-03-00042",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    @Test
    fun `renderInvoice shows the full cockpit and tabs when not in entry mode`() {
        val view = createView()
        val invoice = draftInvoice()

        view.renderInvoice(invoice)

        assertTrue(view.children.anyMatch { it is TabSheet }, "Document mode must show the tab bar")
        assertFalse(view.children.anyMatch { it.element.classList.contains("entry-context-bar") })
    }

    @Test
    fun `renderInvoice shows only the frozen context bar and workspace in entry mode`() {
        val view = createView().apply { overviewEditMode = true }
        val invoice = draftInvoice()

        view.renderInvoice(invoice)

        assertTrue(view.children.anyMatch { it.element.classList.contains("entry-context-bar") }, "Entry mode must show the context bar")
        assertFalse(view.children.anyMatch { it is TabSheet }, "Positionen/Versionen tabs must be hidden while editing")
    }

    @Test
    fun `entry mode is not entered for read-only invoices even if requested`() {
        val view = createView().apply { overviewEditMode = true }
        val invoice = draftInvoice().copy(status = InvoiceStatus.SENT.ordinal)

        view.renderInvoice(invoice)

        assertFalse(view.overviewEditMode, "read-only invoices have nothing to edit")
        assertTrue(view.children.anyMatch { it is TabSheet })
    }

    @Test
    fun `exitEntryMode leaves the workspace and clears a pending deferred refresh`() {
        val detailQueryPort = mock<InvoiceDetailQueryPort>()
        val view = createView()
        val invoice = draftInvoice()
        org.mockito.kotlin.whenever(detailQueryPort.findInvoiceDetailDto(1L)).thenReturn(
            InvoiceDetailDto(
                id = 1L, invoiceNumber = "INV-2026-03-00042", status = InvoiceStatus.DRAFT.ordinal,
                invoiceDate = LocalDate.now(), netAmount = Money.ZERO, vatAmount = Money.ZERO,
                grossAmount = Money.ZERO, sender = null, recipient = null, items = emptyList(),
                latestGenerationJob = null,
            ),
        )
        view.enterEntryMode(invoice)
        // simulate what saveNewItem etc. do while the workspace above is open
        view.invoice = invoice
        view.pendingRefreshAfterEdit = true

        view.exitEntryMode()

        assertEquals(false, view.overviewEditMode)
        assertEquals(false, view.pendingRefreshAfterEdit)
    }
}
