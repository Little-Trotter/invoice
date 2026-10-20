package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [InvoiceDetailViewOverview.buildEntryPane]'s save-failure field-flashing: the
 * private `fieldsFor(InvoiceValidationError)` `when` (six explicit arms plus an `else`) had no
 * test at all — [InvoiceDetailViewOverviewEntryPaneTest] only exercises the save button's
 * date-guard early return, never an actual [InvoiceValidationException] from the facade. Also
 * covers the recipient `ComboBox`'s lazy `DataProvider` fetch/count callbacks (the
 * `findRecipients`/`countRecipients` lambdas), which a `ComboBox` never queries unless something
 * actually asks its `DataProvider` for data — no existing test does.
 */
class InvoiceDetailViewEntryPaneSaveKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val sender = testSender(91L, "Test sender")
        val recipient = testRecipient(92L, "Test recipient")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(sender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(recipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(sender))
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun createView(): InvoiceDetailView {
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort, mock())),
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                senderQueryPort = senderQueryPort,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
                editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
                refs = refs,
            ),
        )
    }

    private fun invoice(recipient: PartyDto? = PartyDto(id = 92L, name = "Test recipient")): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal,
        // invoiceDateField is a required entry-pane field;
        // every test here clicks save and needs binder.writeBeanIfValid to actually reach the
        // (mocked) facade call, which an empty required field would otherwise block before ever
        // getting there.
        invoiceDate = java.time.LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 91L, name = "Test sender"), recipient = recipient,
        items = emptyList(), latestGenerationJob = null, version = 3L,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun saveButton(component: Component): Button =
        flatten(component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }

    // ── the two toasts this pane raises through the shared notification helper ─────────────────

    @Test
    fun `a successful header save confirms on the button and raises no toast`() {
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(invoice())
        val view = createView()
        val pane = buildPane(view, invoice())
        val button = saveButton(pane.component)

        button._click()
        MockVaadin.clientRoundtrip()

        assertTrue(getNotifications().isEmpty())
        assertEquals(UiTranslations.tr("message.saved"), button.text)
    }

    @Test
    fun `refreshing party data with nothing to change shows the info toast bottom-left`() {
        whenever(invoiceDetailQueryPort.previewPartySnapshotRefresh(1L)).thenReturn(PartySnapshotDiffDto(emptyList(), emptyList()))
        val view = createView()
        val pane = buildPane(view, invoice())

        flatten(pane.component).filterIsInstance<Button>().single { it.hasClassName("ch-btn--refresh-master-data") }._click()
        MockVaadin.clientRoundtrip()

        val toast = getNotifications().single()
        assertEquals(Notification.Position.BOTTOM_START, toast.position)
        assertTrue(toast.themeNames.contains("primary"))
        assertEquals(
            UiTranslations.tr("invoiceDetail.entry.refreshPartySnapshot.noChanges"),
            toast.children.toList().flatMap { it.children.toList() }.first().element.text,
        )
    }

    // ── fieldsFor: every InvoiceValidationError arm the save button's catch block can flash ──────

    private fun triggerSaveFailure(error: InvoiceValidationError): EntryPaneResult {
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).doThrow(InvoiceValidationException(error))
        val view = createView()
        val pane = buildPane(view, invoice())
        saveButton(pane.component)._click()
        MockVaadin.clientRoundtrip()
        return pane
    }

    @Test
    fun `SENDER_PROFILE_MISSING flashes the sender ComboBox invalid`() {
        val pane = triggerSaveFailure(InvoiceValidationError.SENDER_PROFILE_MISSING)
        val senderBox = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.card.sender") }
        assertTrue(senderBox.isInvalid)
    }

    @Test
    fun `RECIPIENT_MISSING flashes the recipient ComboBox invalid`() {
        val pane = triggerSaveFailure(InvoiceValidationError.RECIPIENT_MISSING)
        val recipientBox = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.summary.name") }
        assertTrue(recipientBox.isInvalid)
    }

    @Test
    fun `SERVICE_PERIOD_END_BEFORE_START flashes both service period date pickers invalid`() {
        val pane = triggerSaveFailure(InvoiceValidationError.SERVICE_PERIOD_END_BEFORE_START)
        val fromField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.servicePeriodFrom") }
        val toField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.servicePeriodTo") }
        assertTrue(fromField.isInvalid)
        assertTrue(toField.isInvalid)
    }

    @Test
    fun `DUE_DATE_BEFORE_INVOICE_DATE flashes the invoice date and due date pickers invalid`() {
        val pane = triggerSaveFailure(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE)
        val invoiceDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
        val dueDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.dueDate") }
        assertTrue(invoiceDateField.isInvalid)
        assertTrue(dueDateField.isInvalid)
    }

    @Test
    fun `PAYMENT_TERM_DAYS_NEGATIVE flashes the payment term ComboBox invalid`() {
        val pane = triggerSaveFailure(InvoiceValidationError.PAYMENT_TERM_DAYS_NEGATIVE)
        val paymentTermBox = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.paymentTerm") }
        assertTrue(paymentTermBox.isInvalid)
    }

    @Test
    fun `ITEM_ARTICLE_TAX_VARIANT_MISSING flashes the seller VAT registration country ComboBox invalid`() {
        val pane = triggerSaveFailure(InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING)
        val sellerVatCountryBox = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.sellerVatRegistrationCountry") }
        assertTrue(sellerVatCountryBox.isInvalid)
    }

    @Test
    fun `an InvoiceValidationError with no mapped field (else arm) flashes nothing and does not crash`() {
        val pane = triggerSaveFailure(InvoiceValidationError.NO_ITEMS)
        val anyComboBoxInvalid = flatten(pane.component).filterIsInstance<ComboBox<*>>().any { it.isInvalid }
        val anyDatePickerInvalid = flatten(pane.component).filterIsInstance<DatePicker>().any { it.isInvalid }
        assertFalse(anyComboBoxInvalid, "NO_ITEMS has no mapped field — nothing should flash invalid")
        assertFalse(anyDatePickerInvalid)
    }

    // ── seller VAT DE sentinel: the visible DE default persists as null ──────────────────────────

    @Test
    fun `saving with the visible DE default entry persists a null seller VAT registration country`() {
        // Country.DE in the ComboBox is a presentation-only sentinel; the
        // UpdateInvoiceHeaderCommand (and through it Invoice.sellerVatRegistrationCountry) must
        // still carry null for the German default, keeping reverse-charge eligibility, item tax
        // derivation and the XML export identical to the pre-change domestic behavior.
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(invoice())
        val view = createView()
        val pane = buildPane(view, invoice())
        // The box lives on Tab 2 ("E-Rechnung") — TabSheet attaches its content lazily, so switch
        // there once before looking it up (same caveat as InvoiceDetailViewOverviewEntryPaneTest).
        flatten(pane.component).filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().single().selectedIndex = 1
        MockVaadin.clientRoundtrip()
        val sellerVatCountryBox = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.sellerVatRegistrationCountry") }
        org.junit.jupiter.api.Assertions.assertEquals(
            de.littletrotter.shared.country.Country.DE,
            sellerVatCountryBox.value,
            "sanity: the DE default entry is visibly selected before saving",
        )

        saveButton(pane.component)._click()
        MockVaadin.clientRoundtrip()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        org.mockito.kotlin.verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        org.junit.jupiter.api.Assertions.assertNull(
            captor.firstValue.sellerVatRegistrationCountry,
            "the DE sentinel must map back to null — the persisted German-default encoding",
        )
    }

    // ── recipientBox's lazy DataProvider: fetch/count callbacks ──────────────────────────────────

    private fun recipientDataProvider(pane: EntryPaneResult): com.vaadin.flow.data.provider.DataProvider<RecipientSummaryDto, String?> {
        val box = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.summary.name") }
            .requireAs<ComboBox<RecipientSummaryDto>>()
        return box.dataProvider.requireAs()
    }

    @Test
    fun `recipient search with a null filter fetches and counts with a null filter`() {
        whenever(recipientQueryPort.findRecipients(0, 10, null)).thenReturn(listOf(testRecipient(1L, "A")))
        whenever(recipientQueryPort.countRecipients(null)).thenReturn(1)
        val view = createView()
        val pane = buildPane(view, invoice())

        val provider = recipientDataProvider(pane)
        val results = provider.fetch(Query(0, 10, emptyList(), null, null)).toList()
        val count = provider.size(Query(0, 10, emptyList(), null, null))

        assertTrue(results.isNotEmpty())
        org.junit.jupiter.api.Assertions.assertEquals(1, count)
    }

    @Test
    fun `recipient search with a blank filter is normalized to no filter`() {
        whenever(recipientQueryPort.findRecipients(0, 10, null)).thenReturn(emptyList())
        whenever(recipientQueryPort.countRecipients(null)).thenReturn(0)
        val view = createView()
        val pane = buildPane(view, invoice())

        val provider = recipientDataProvider(pane)
        provider.fetch(Query(0, 10, emptyList(), null, "   ")).toList()
        provider.size(Query(0, 10, emptyList(), null, "   "))

        org.mockito.kotlin.verify(recipientQueryPort).findRecipients(0, 10, null)
        org.mockito.kotlin.verify(recipientQueryPort).countRecipients(null)
    }

    @Test
    fun `recipient search with a real filter text passes it through`() {
        whenever(recipientQueryPort.findRecipients(0, 10, "Mueller")).thenReturn(emptyList())
        whenever(recipientQueryPort.countRecipients("Mueller")).thenReturn(0)
        val view = createView()
        val pane = buildPane(view, invoice())

        val provider = recipientDataProvider(pane)
        provider.fetch(Query(0, 10, emptyList(), null, "Mueller")).toList()
        provider.size(Query(0, 10, emptyList(), null, "Mueller"))

        org.mockito.kotlin.verify(recipientQueryPort).findRecipients(0, 10, "Mueller")
        org.mockito.kotlin.verify(recipientQueryPort).countRecipients("Mueller")
    }
}
