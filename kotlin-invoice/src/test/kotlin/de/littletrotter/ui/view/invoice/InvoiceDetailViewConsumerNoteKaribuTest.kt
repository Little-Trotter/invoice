package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import com.vaadin.flow.component.datepicker.DatePicker
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The private-customer notice field of the entry pane: shown only when the invoice carries one
 *  (or the user decided its content), resettable to the sender default, absent on a frozen invoice. */
class InvoiceDetailViewConsumerNoteKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()
    // ProjectNumberModule (a real, not mocked, InvoiceFormModule) is what renders the live
    // "projectNumber" widget wireFreeTextChips reads from — an unstubbed
    // InvoiceFormModuleRegistry.modules() defaults to an empty list, which would silently leave
    // einvoiceTab.projectNumberField null in every test here.
    private val invoiceFormModuleRegistry: InvoiceFormModuleRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val defaultSender = testSender(91L, "Test sender")
        val defaultRecipient = testRecipient(92L, "Test recipient")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(defaultSender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(defaultRecipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(defaultSender))
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
        whenever(invoiceFormModuleRegistry.modules())
            .thenReturn(listOf(de.littletrotter.application.invoice.ProjectNumberModule()))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()

    private fun createView(): InvoiceDetailView {
        val generationJobQueryService = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryService = generationJobQueryService,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort)),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                senderQueryPort = senderQueryPort,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
                editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
                refs = refs,
                invoiceFormModuleRegistry = invoiceFormModuleRegistry,
            ),
        )
    }

    private fun invoice(
        status: Int = InvoiceStatus.DRAFT.ordinal,
        consumerNote: String? = null,
        origin: String = "SELLER_DEFAULT",
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 91L, name = "Test sender"), recipient = PartyDto(id = 92L, name = "Test recipient"),
        items = emptyList(), latestGenerationJob = null, version = 3L,
        consumerNote = consumerNote, consumerNoteOrigin = origin,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        val tabSheet = flatten(result.component).filterIsInstance<TabSheet>().single()
        tabSheet.selectedIndex = 2
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun consumerField(pane: EntryPaneResult): TextArea =
        flatten(pane.component).filterIsInstance<TextArea>().single { it.label == UiTranslations.tr("invoice.consumerNote") }

    private fun saveCommand(pane: EntryPaneResult): UpdateInvoiceHeaderCommand {
        val tabSheet = flatten(pane.component).filterIsInstance<TabSheet>().single()
        tabSheet.selectedIndex = 0
        MockVaadin.clientRoundtrip()
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
            ._setValue(LocalDate.of(2026, 6, 1))
        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }.click()
        val captor = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        return captor.firstValue
    }

    @Test
    fun `the field is hidden on an invoice without a notice and with the seller default origin`() {
        val pane = buildPane(createView(), invoice())

        assertFalse(consumerField(pane).parent.get().isVisible)
    }

    @Test
    fun `the field shows the stored notice`() {
        val pane = buildPane(createView(), invoice(consumerNote = "Bitte aufbewahren."))

        val field = consumerField(pane)
        assertTrue(field.parent.get().isVisible)
        assertEquals("Bitte aufbewahren.", field.value)
    }

    @Test
    fun `the field stays visible after the user deliberately cleared the notice`() {
        val pane = buildPane(createView(), invoice(consumerNote = null, origin = "MANUAL"))

        assertTrue(consumerField(pane).parent.get().isVisible)
    }

    @Test
    fun `an edited notice is submitted without a reset`() {
        val current = invoice(consumerNote = "Alt")
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val pane = buildPane(createView(), current)
        consumerField(pane).value = "Neu"

        val command = saveCommand(pane)

        assertTrue(command.consumerNoteSubmitted)
        assertEquals("Neu", command.consumerNote)
        assertFalse(command.resetConsumerNoteToSellerDefault)
    }

    @Test
    fun `reset to default asks the next save to restore the sender default`() {
        val current = invoice(consumerNote = "Eigener Text", origin = "MANUAL")
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val pane = buildPane(createView(), current)

        flatten(pane.component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoice.consumerNote.reset") }.click()
        val field = consumerField(pane)
        assertEquals("", field.value)
        assertTrue(field.isReadOnly)

        val command = saveCommand(pane)

        assertTrue(command.resetConsumerNoteToSellerDefault)
    }

    @Test
    fun `a frozen invoice builds no entry pane and so offers no notice field`() {
        whenever(detailQueryPort.findCompletenessChecks(any())).thenReturn(emptyList())
        whenever(detailQueryPort.findB2bComplianceFindings(any())).thenReturn(emptyList())
        val view = createView()

        view.invoice = invoice(status = InvoiceStatus.SENT.ordinal, consumerNote = "Bitte aufbewahren.")

        assertTrue(flatten(view).filterIsInstance<TextArea>().none { it.label == UiTranslations.tr("invoice.consumerNote") })
    }
}
