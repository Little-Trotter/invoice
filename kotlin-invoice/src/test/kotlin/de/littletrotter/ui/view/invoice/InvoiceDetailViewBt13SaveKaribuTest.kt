package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * BT-13 ("Bestellreferenz des Käufers") typed into the e-invoice tab's Referenzen step must reach
 * [UpdateInvoiceHeaderCommand.purchaseOrderReference] on save, and a re-rendered pane built from the
 * saved DTO must show it again (guided mode with an effective B2B profile and flat mode).
 */
class InvoiceDetailViewBt13SaveKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val editUseCase: EditInvoiceHeaderUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val sender = testSender(91L, "Test sender")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(testRecipient(92L, "Test recipient"))
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(sender))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun profileRefs(): ReferenceDataMocks = ReferenceDataMocks().apply {
        val profile = B2bProfileDto(id = 7L, name = "Nekar")
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(7L)
        whenever(b2bProfileQueries.findB2bProfileById(7L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(7L)).thenReturn(emptyList())
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))
    }

    private fun flatRefs(): ReferenceDataMocks = ReferenceDataMocks().apply {
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
    }

    private fun createView(refs: ReferenceDataMocks): InvoiceDetailView {
        val jobs = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryService = jobs,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), detailQueryPort)),
                exportPanel = testExportPanel(detailQueryPort, recipientQueryPort, jobs),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort, senderQueryPort = senderQueryPort,
                invoiceDetailQueryPort = detailQueryPort, editInvoiceHeaderUseCase = editUseCase, refs = refs,
            ),
        )
    }

    private fun invoice(bt13: String? = null, bt10: String? = null) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = java.time.LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 91L, name = "Test sender"),
        recipient = PartyDto(id = 92L, name = "Test recipient"),
        items = emptyList(), latestGenerationJob = null, version = 3L,
        purchaseOrderReference = bt13, buyerReference = bt10,
    )

    /** Like the plain tree walk, but also enters each [TabSheet] tab's slotted content. */
    private fun flatten(c: Component): List<Component> {
        val kids = c.children.toList() +
            if (c is TabSheet) (0 until c.tabCount).map { c.getComponent(c.getTabAt(it)) } else emptyList()
        return listOf(c) + kids.distinct().flatMap { flatten(it) }
    }

    private fun build(view: InvoiceDetailView, current: InvoiceDetailDto): EntryPaneResult {
        val r = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        com.vaadin.flow.component.UI.getCurrent().add(r.component)
        MockVaadin.clientRoundtrip()
        // tab 1 = "E-Rechnung" (Referenzen is its first guided step, or the flat form's reference block)
        flatten(r.component).filterIsInstance<TabSheet>().single().selectedIndex = 1
        MockVaadin.clientRoundtrip()
        return r
    }

    private fun bt13Field(c: Component): TextField =
        flatten(c).filterIsInstance<TextField>().single { it.label == UiTranslations.tr("invoiceDetail.purchaseOrderReference") }

    private fun saveAndCapture(refs: ReferenceDataMocks, bt10: String? = null): UpdateInvoiceHeaderCommand {
        val saved = invoice("BST-4711", bt10)
        whenever(editUseCase.updateInvoiceHeader(any())).thenReturn(saved)
        val view = createView(refs).apply { overviewEditMode = true }
        val pane = build(view, invoice(bt10 = bt10))
        bt13Field(pane.component)._setValue("BST-4711")
        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }._click()
        MockVaadin.clientRoundtrip()
        val captor = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(editUseCase).updateInvoiceHeader(captor.capture())
        // reload: a fresh pane from the saved DTO shows the value again
        val reloaded = build(createView(refs).apply { overviewEditMode = true }, saved)
        assertEquals("BST-4711", bt13Field(reloaded.component).value)
        return captor.firstValue
    }

    @Test
    fun `guided Referenzen step - BT-13 typed is saved in the header command and shown after reload`() {
        assertEquals("BST-4711", saveAndCapture(profileRefs(), bt10 = "LEIT-1").purchaseOrderReference)
    }

    @Test
    fun `flat mode - BT-13 typed is saved in the header command and shown after reload`() {
        assertEquals("BST-4711", saveAndCapture(flatRefs()).purchaseOrderReference)
    }

    /** PO 2026-10-01: a blank BT-10 (or mandatory profile header field) must not block the save. The
     *  missing-fields warning is shown first; nothing is persisted until acknowledged, then everything
     *  entered (BT-13) is saved. */
    @Test
    fun `guided mode - blank BT-10 shows the warning first, then saves BT-13 after acknowledging`() {
        whenever(editUseCase.updateInvoiceHeader(any())).thenReturn(invoice("BST-4711"))
        val view = createView(profileRefs()).apply { overviewEditMode = true }
        val pane = build(view, invoice())
        bt13Field(pane.component)._setValue("BST-4711")
        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }._click()
        MockVaadin.clientRoundtrip()
        org.mockito.kotlin.verifyNoInteractions(editUseCase)
        val dialog = com.github.mvysny.kaributesting.v10._find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().single()
        assertEquals(UiTranslations.tr("invoiceDetail.entry.saveWarning.title"), dialog.element.getProperty("header"))
        assertEquals(true, dialog.element.getProperty("message").contains(UiTranslations.tr("invoiceDetail.buyerReference")))
        com.vaadin.flow.component.ComponentUtil.fireEvent(dialog, com.vaadin.flow.component.confirmdialog.ConfirmDialog.ConfirmEvent(dialog, false))
        MockVaadin.clientRoundtrip()
        val captor = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(editUseCase).updateInvoiceHeader(captor.capture())
        assertEquals("BST-4711", captor.firstValue.purchaseOrderReference)
    }
}
