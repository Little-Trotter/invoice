package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.withName
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/** The entry pane's save command with a payment term, the general server issue's
 *  navigation (no tab, no field), a server failure without a message, and the save button of a
 *  pane whose header was never touched. */
class InvoiceDetailViewOverviewSaveFlowBoundaryKaribuTest {

    private val harness = DetailViewTestHarness()
    private val term = aPaymentTerm { id = 5L; dueInDays = 14 }.withName("Netto 14")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val sender = testSender(91L, "Test sender")
        val recipient = testRecipient(92L, "Test recipient")
        whenever(harness.senderQueryPort.findSenderById(91L)).thenReturn(sender)
        whenever(harness.recipientQueryPort.findRecipientById(92L)).thenReturn(recipient)
        whenever(harness.senderQueryPort.findAllSenders()).thenReturn(listOf(sender))
        whenever(harness.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        whenever(harness.paymentTermQueries.findAllPaymentTerms()).thenReturn(listOf(term.toDto()))
        whenever(harness.paymentTermQueries.findPaymentTermById(5L)).thenReturn(term.toDto())
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun invoice(paymentTermId: Long? = null): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 91L, name = "Test sender"), recipient = PartyDto(id = 92L, name = "Test recipient"),
        items = emptyList(), latestGenerationJob = null, version = 3L, paymentTermId = paymentTermId,
    )

    private fun pane(current: InvoiceDetailDto): Component {
        val result = harness.view.invoiceEntryPane.build(harness.view, current, onInvoiceUpdated = { harness.view.invoice = it }, onDone = {})
        UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result.component
    }

    // After a save the button briefly shows the saved confirmation instead of its label.
    private fun save(root: Component) =
        DetailViewTestHarness.flatten(root).filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("app.save") || it.text == UiTranslations.tr("message.saved") }.click()

    private fun issueButtons(root: Component): List<Button> =
        DetailViewTestHarness.flatten(root).filterIsInstance<Div>().single { it.classNames.contains("ino-card--warn") }
            .children.toList().filterIsInstance<Button>()

    @Test
    fun `happy path - a header with a payment term saves the payment term id in the command`() {
        whenever(harness.editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(invoice(5L))
        val root = pane(invoice(paymentTermId = 5L))

        save(root)

        val command = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(harness.editInvoiceHeaderUseCase).updateInvoiceHeader(command.capture())
        assertEquals(5L, command.firstValue.paymentTermId)
    }

    @Test
    fun `boundary - clicking the general server issue leaves the selected tab unchanged`() {
        whenever(harness.editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenThrow(IllegalStateException("database unavailable"))
        val root = pane(invoice())
        save(root)
        val tabs = DetailViewTestHarness.flatten(root).filterIsInstance<TabSheet>().single()
        tabs.selectedIndex = 1

        issueButtons(root).single().click()

        assertEquals(1, tabs.selectedIndex, "an issue without a tab never switches tabs")
    }

    @Test
    fun `boundary - a server failure without a message is still listed as one general issue`() {
        whenever(harness.editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenThrow(IllegalStateException())
        val root = pane(invoice())

        save(root)

        val texts = issueButtons(root).map { it.text }
        assertEquals(1, texts.size, texts.toString())
        assertTrue(texts.single().contains(UiTranslations.tr("invoiceDetail.entry.validation.general")))
    }

    @Test
    fun `boundary - saving twice sends two commands with the same expected version`() {
        whenever(harness.editInvoiceHeaderUseCase.updateInvoiceHeader(any())).thenReturn(invoice())
        val root = pane(invoice())

        save(root)
        save(root)

        val command = argumentCaptor<UpdateInvoiceHeaderCommand>()
        verify(harness.editInvoiceHeaderUseCase, org.mockito.kotlin.times(2)).updateInvoiceHeader(command.capture())
        assertEquals(listOf(3L, 3L), command.allValues.map { it.expectedVersion })
    }
}
