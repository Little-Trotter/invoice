package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The entry pane's save and exit flow in `InvoiceDetailViewOverview.kt` after the party
 * card and the dates section moved into their own components: required-party issues and their
 * summary navigation, a non-validation server failure, and both exit paths (Done button, discard
 * confirmation). Setup mirrors [InvoiceDetailViewOverviewEntryPaneTest].
 */
class InvoiceDetailViewOverviewSaveFlowKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val defaultSender = testSender(91L, "Test sender")
        val defaultRecipient = testRecipient(92L, "Test recipient")
        whenever(senderQueryPort.findSenderById(91L)).thenReturn(defaultSender)
        whenever(recipientQueryPort.findRecipientById(92L)).thenReturn(defaultRecipient)
        whenever(senderQueryPort.findAllSenders()).thenReturn(listOf(defaultSender))
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

    private fun invoice(
        sender: PartyDto? = PartyDto(id = 91L, name = "Test sender"),
        recipient: PartyDto? = PartyDto(id = 92L, name = "Test recipient"),
    ): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = sender, recipient = recipient, items = emptyList(), latestGenerationJob = null,
        version = 3L,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto, onDone: () -> Unit = {}): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = onDone)
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun buttonByText(component: Component, text: String): Button =
        flatten(component).filterIsInstance<Button>().single { it.text == text }

    private fun validationSummary(pane: EntryPaneResult): Div =
        flatten(pane.component).filterIsInstance<Div>().single { it.classNames.contains("ino-card--warn") }

    private fun issueButtons(pane: EntryPaneResult): List<Button> =
        validationSummary(pane).children.toList().filterIsInstance<Button>()

    private fun tabSheet(pane: EntryPaneResult): TabSheet = flatten(pane.component).filterIsInstance<TabSheet>().single()

    @Test
    fun `happy path - a complete header saves through the facade and keeps the issue summary hidden`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val pane = buildPane(createView(), current)

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(any())
        assertFalse(validationSummary(pane).isVisible)
    }

    @Test
    fun `boundary - missing sender and recipient block the save and list one issue per party`() {
        val pane = buildPane(createView(), invoice(sender = null, recipient = null))

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
        assertTrue(validationSummary(pane).isVisible)
        val texts = issueButtons(pane).map { it.text }
        assertEquals(2, texts.size, "exactly the two party issues: $texts")
        assertTrue(texts.any { it.contains(UiTranslations.tr("validation.invoice.senderProfileMissing")) })
        assertTrue(texts.any { it.contains(UiTranslations.tr("validation.invoice.recipientMissing")) })
    }

    @Test
    fun `boundary - a missing invoice date blocks save and flashes the date field itself invalid`() {
        // flashClientSideIssueFields' own `when(field)` only ever saw ComboBox fields
        // through the sibling "missing sender/recipient" test above; a required DatePicker field
        // (invoiceDateField) never went through it, leaving that branch untested.
        val current = InvoiceDetailDto(
            id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
            netAmount = null, vatAmount = null, grossAmount = null,
            sender = PartyDto(id = 91L, name = "Test sender"), recipient = PartyDto(id = 92L, name = "Test recipient"),
            items = emptyList(), latestGenerationJob = null, version = 3L,
        )
        val pane = buildPane(createView(), current)

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        assertTrue(validationSummary(pane).isVisible)
        val invoiceDateField = flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
        assertTrue(invoiceDateField.isInvalid, "the missing invoice date itself must be flashed invalid")
    }

    @Test
    fun `boundary - clicking a party issue in the summary switches back to the invoice tab`() {
        val pane = buildPane(createView(), invoice(sender = null))
        buttonByText(pane.component, UiTranslations.tr("app.save")).click()
        tabSheet(pane).selectedIndex = 1
        MockVaadin.clientRoundtrip()

        issueButtons(pane).single().click()

        assertEquals(0, tabSheet(pane).selectedIndex)
    }

    @Test
    fun `boundary - a non-validation server failure is listed as a general server issue without a field`() {
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenThrow(IllegalStateException("database unavailable"))
        val pane = buildPane(createView(), invoice())

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        assertTrue(validationSummary(pane).isVisible)
        val texts = issueButtons(pane).map { it.text }
        assertEquals(1, texts.size)
        assertTrue(texts.single().contains(UiTranslations.tr("invoiceDetail.entry.validation.general")), texts.single())
    }

    // ── InvoiceValidationException (handleValidationException) was entirely untested ───────────

    @Test
    fun `boundary - an InvoiceValidationException from the facade flashes and names the field it points at`() {
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any()))
            .thenThrow(InvoiceValidationException(InvoiceValidationError.SENDER_PROFILE_MISSING))
        val pane = buildPane(createView(), invoice())

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        assertTrue(validationSummary(pane).isVisible)
        val texts = issueButtons(pane).map { it.text }
        assertEquals(1, texts.size)
        assertTrue(texts.single().contains(UiTranslations.tr("invoiceDetail.entry.validation.form")), texts.single())
        val senderBox = flatten(pane.component).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.card.sender") }
        assertTrue(senderBox.isInvalid, "the field the exception names must be flashed invalid")
    }

    @Test
    fun `boundary - a two-field InvoiceValidationException flashes both fields and its issue button switches back to the invoice tab`() {
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any()))
            .thenThrow(InvoiceValidationException(InvoiceValidationError.SERVICE_PERIOD_END_BEFORE_START))
        val pane = buildPane(createView(), invoice())
        tabSheet(pane).selectedIndex = 1
        MockVaadin.clientRoundtrip()

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val datePickers = flatten(pane.component).filterIsInstance<DatePicker>()
        assertTrue(datePickers.count { it.isInvalid } >= 2, "both service-period fields must be flashed invalid")

        issueButtons(pane).single().click()

        assertEquals(0, tabSheet(pane).selectedIndex)
    }

    @Test
    fun `boundary - the Done button of an untouched form leaves the pane without asking`() {
        var doneCalled = false
        val pane = buildPane(createView(), invoice(), onDone = { doneCalled = true })

        buttonByText(pane.component, UiTranslations.tr("invoiceDetail.overview.done")).click()

        assertTrue(doneCalled)
        assertTrue(_find<ConfirmDialog>().isEmpty())
    }

    @Test
    fun `boundary - confirming the discard dialog of a dirty form leaves the pane`() {
        var doneCalled = false
        val pane = buildPane(createView(), invoice(), onDone = { doneCalled = true })
        flatten(pane.component).filterIsInstance<DatePicker>()
            .single { it.label == UiTranslations.tr("invoiceDetail.dueDate") }
            ._setValue(LocalDate.of(2026, 7, 1))
        pane.requestExit()
        val dialog = _find<ConfirmDialog>().single()
        assertFalse(doneCalled)

        ComponentUtil.fireEvent(dialog, ConfirmDialog.ConfirmEvent(dialog, false))

        assertTrue(doneCalled)
    }

    @Test
    fun `boundary - a blank invoice note is stored as absent, not as an empty string`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val pane = buildPane(createView(), current)
        tabSheet(pane).selectedIndex = 2
        MockVaadin.clientRoundtrip()
        flatten(pane.component).filterIsInstance<com.vaadin.flow.component.textfield.TextArea>()
            .single { it.label == UiTranslations.tr("invoiceDetail.invoiceNote") }
            ._setValue("")

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        val captor = org.mockito.kotlin.argumentCaptor<de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(captor.capture())
        assertEquals(null, captor.firstValue.invoiceNote)
    }

    private val emojiText = "Thanks \uD83D\uDE00"

    // The notes tab's content only joins the component tree once the tab has been selected.
    private fun freeTextEditor(pane: EntryPaneResult): HtmlFreeTextEditor {
        tabSheet(pane).selectedIndex = 2
        MockVaadin.clientRoundtrip()
        return flatten(pane.component).filterIsInstance<HtmlFreeTextEditor>().single()
    }

    @Test
    fun `boundary - a free text with an emoji blocks save and is marked at the editor and in the summary`() {
        val pane = buildPane(createView(), invoice())
        val editor = freeTextEditor(pane)
        editor.value = emojiText

        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
        assertTrue(editor.isInvalid)
        assertTrue(editor.errorMessage!!.contains("\uD83D\uDE00"))
        val issues = issueButtons(pane)
        assertEquals(1, issues.size)
        assertTrue(issues.single().text.contains(UiTranslations.tr("invoiceDetail.freeText")))
    }

    @Test
    fun `boundary - clicking the free text issue selects the notes tab`() {
        val pane = buildPane(createView(), invoice())
        freeTextEditor(pane).value = emojiText
        buttonByText(pane.component, UiTranslations.tr("app.save")).click()
        tabSheet(pane).selectedIndex = 0
        MockVaadin.clientRoundtrip()

        issueButtons(pane).single().click()

        assertEquals(2, tabSheet(pane).selectedIndex)
    }

    @Test
    fun `boundary - a renderable free text clears the mark and the save reaches the facade`() {
        val current = invoice()
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val pane = buildPane(createView(), current)
        val editor = freeTextEditor(pane)
        editor.value = emojiText
        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        editor.value = "Thanks"
        buttonByText(pane.component, UiTranslations.tr("app.save")).click()

        assertFalse(editor.isInvalid)
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(any())
    }

    @Test
    fun `boundary - typing an emoji marks the editor invalid before any save`() {
        val pane = buildPane(createView(), invoice())
        val editor = freeTextEditor(pane)

        editor.value = emojiText

        assertTrue(editor.isInvalid)
    }
}
