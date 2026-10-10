package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.view
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * The entry pane's B2B visibility and save-guard arms the existing suites
 * leave open: an invoice date the field itself rejects (outside its range) blocks the save with a
 * form issue that leads back to the invoice tab, a routing-id recipient (guided e-invoice mode) with an empty buyer reference is
 * blocked with an e-invoice issue that reveals the field, and a buyer reference that already
 * diverges from the recipient's routing id opens unlocked.
 */
class InvoiceDetailViewOverviewBranchKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val senderQueryPort: SenderQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase = mock()
    private val routing = "991-12345-67"

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
        MockVaadin.tearDown()
    }

    private fun invoice(
        routingId: String? = null,
        buyerReference: String? = null,
        recipient: PartyDto? = PartyDto(id = 92L, name = "Test recipient", routingId = routingId),
        sellerVatRegistrationCountry: String? = null,
    ) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 91L, name = "Test sender"),
        recipient = recipient,
        items = emptyList(), latestGenerationJob = null, version = 3L, buyerReference = buyerReference,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry,
    )

    private fun all(c: Component): List<Component> {
        val children = if (c is TabSheet) (0 until c.tabCount).map { c.getComponent(c.getTabAt(it)) } else c.children.toList()
        return listOf(c) + children.flatMap { all(it) }
    }

    private fun pane(current: InvoiceDetailDto): EntryPaneResult {
        val detailView = view(refs, detailQueryPort, recipientQueryPort, senderQueryPort, editInvoiceHeaderUseCase, invoiceDetailQueryPort)
        val result = detailView.invoiceEntryPane.build(detailView, current, onInvoiceUpdated = { detailView.invoice = it }, onDone = {})
        UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun EntryPaneResult.save() = all(component).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }.click()

    private fun EntryPaneResult.issues(): List<Button> =
        all(component).filterIsInstance<Div>().filter { it.classNames.contains("ino-card--warn") }
            .flatMap { it.children.toList().filterIsInstance<Button>() }

    private fun EntryPaneResult.tabs(): TabSheet = all(component).filterIsInstance<TabSheet>().single()

    private fun EntryPaneResult.buyerReference(): TextField =
        all(component).filterIsInstance<TextField>().single { it.label == UiTranslations.tr("invoiceDetail.buyerReference") }

    @Test
    fun `happy path - a buyer reference diverging from the routing id opens unlocked and saves`() {
        val current = invoice(routingId = routing, buyerReference = "PO-4711", sellerVatRegistrationCountry = "AT")
        whenever(editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(current)
        val p = pane(current)

        assertFalse(p.buyerReference().isReadOnly, "a deliberate earlier override stays editable")
        p.save()

        val command = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(command.capture())
        assertEquals("AT", command.firstValue.sellerVatRegistrationCountry)
        assertEquals("PO-4711", command.firstValue.buyerReference)
    }

    @Test
    fun `boundary - a B2B profile contributes only header and seller-party seller fields, a recipient-less buyer reference is kept`() {
        val profile = B2bProfileDto(id = 7L, name = "B2B-Standard")
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(7L)
        whenever(refs.b2bProfileQueries.findB2bProfileById(7L)).thenReturn(profile)
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))
        val sellerFields = listOf(B2bTerm.BT_58, B2bTerm.BT_84, B2bTerm.BT_155, null).map { t ->
            B2bProfileFieldDto(term = t, requirement = B2bFieldRequirement.OPTIONAL, businessDefinition = "Feld ${t?.officialCode}")
        }
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(sellerFields)

        val p = pane(invoice(recipient = null, buyerReference = "PO-1"))

        verify(refs.b2bFieldDefinitionQueries, atLeastOnce()).findB2bSellerFields(7L)
        assertEquals("PO-1", p.buyerReference().value)
        assertFalse(p.buyerReference().isReadOnly, "without a recipient routing id any stored reference is an override")
    }

    @Test
    fun `boundary - a buyer reference equal to the routing id stays the locked suggestion`() {
        val p = pane(invoice(routingId = routing, buyerReference = routing))

        assertTrue(p.buyerReference().isReadOnly)
    }

    @Test
    fun `boundary - an invoice date outside the field's own range blocks the save with a form issue that returns to the invoice tab`() {
        val p = pane(invoice())
        val invoiceDate = all(p.component).filterIsInstance<DatePicker>().single { it.label == UiTranslations.tr("invoiceDetail.invoiceDate") }
        invoiceDate.max = LocalDate.of(2026, 6, 30)
        invoiceDate._setValue(LocalDate.of(2026, 7, 15))

        p.save()

        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
        val issue = p.issues().single()
        assertTrue(issue.text.contains(UiTranslations.tr("invoiceDetail.invoiceDate")), issue.text)
        assertTrue(issue.text.contains(UiTranslations.tr("invoiceDetail.entry.validation.date.afterMax", "30.06.2026")), issue.text)
        assertFalse(issue.text.contains(UiTranslations.tr("invoiceDetail.entry.validation.form")), issue.text)
        p.tabs().selectedIndex = 1
        issue.click()
        assertEquals(0, p.tabs().selectedIndex)
    }

    private fun datePicker(p: EntryPaneResult, labelKey: String) =
        all(p.component).filterIsInstance<DatePicker>().single { it.label == UiTranslations.tr(labelKey) }

    @Test
    fun `boundary - an invoice date before the field's earliest day names that day in the issue`() {
        val p = pane(invoice())
        val invoiceDate = datePicker(p, "invoiceDetail.invoiceDate")
        invoiceDate.min = LocalDate.of(2026, 1, 1)
        invoiceDate._setValue(LocalDate.of(2025, 12, 31))

        p.save()

        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
        val text = p.issues().single().text
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.entry.validation.date.beforeMin", "01.01.2026")), text)
    }

    @Test
    fun `boundary - a due date after the field's latest day names the due date and that day`() {
        val p = pane(invoice())
        val dueDate = datePicker(p, "invoiceDetail.dueDate")
        dueDate.max = LocalDate.of(2026, 8, 31)
        dueDate._setValue(LocalDate.of(2026, 9, 1))

        p.save()

        val text = p.issues().single().text
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.dueDate")), text)
        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.entry.validation.date.afterMax", "31.08.2026")), text)
    }

    @Test
    fun `boundary - an empty Vaadin rejection message falls back to the correct-marked hint and never ends in a colon`() {
        val p = pane(invoice())
        val invoiceDate = datePicker(p, "invoiceDetail.invoiceDate")
        invoiceDate.max = LocalDate.of(2026, 6, 30)
        invoiceDate.i18n = invoiceDate.i18n.setMaxErrorMessage("")
        invoiceDate._setValue(LocalDate.of(2026, 7, 15))

        p.save()

        val text = p.issues().single().text
        assertTrue(text.endsWith(UiTranslations.tr("invoiceDetail.entry.validation.correctMarked")), text)
        assertFalse(text.trimEnd().endsWith(":"), text)
    }

    @Test
    fun `boundary - guided e-invoice mode with an empty buyer reference warns first and saves only after acknowledging`() {
        val p = pane(invoice(routingId = routing))
        val hint = all(p.component).filterIsInstance<HasText>().map { it.text }
        assertTrue(hint.any { it == UiTranslations.tr("invoiceDetail.entry.einvoice.guidedHint", "1") }, "one missing e-invoice input: $hint")

        p.save()

        verify(editInvoiceHeaderUseCase, never()).updateInvoiceHeader(any())
        val dlg = _find<com.vaadin.flow.component.confirmdialog.ConfirmDialog>().single()
        assertTrue(
            dlg.element.getProperty("message").contains(UiTranslations.tr("invoiceDetail.buyerReference")),
            "warning lists the missing field",
        )
        com.vaadin.flow.component.ComponentUtil.fireEvent(dlg, com.vaadin.flow.component.confirmdialog.ConfirmDialog.ConfirmEvent(dlg, false))
        verify(editInvoiceHeaderUseCase).updateInvoiceHeader(any())
    }
}
