package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceFromQuoteResult
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.shared.ActorOverride
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCreatedFromQuote
import de.littletrotter.domain.event.InvoiceCreationFromQuoteRejected
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.NoActiveNumberRangeException
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.ui.view.quote.quoteDetail
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.UUID

class CreateInvoiceFromQuoteTest {

    private val invoices: InvoiceRepository = mock()
    private val reader: QuoteOfferReader = mock()
    private val writer: QuoteInvoiceDraftWriter = mock()
    private val replies: QuoteReplyRecorder = mock()
    private val actorOverride = ActorOverride()
    private val events: Event<DomainEvent> = mock()
    private val useCase = CreateInvoiceFromQuote(invoices, reader, writer, replies, actorOverride, events)

    private val command = CreateInvoiceFromQuoteCommand(UUID.randomUUID(), "AN-1", LocalDate.of(2026, 10, 5), null, "clerk", UUID.randomUUID())
    private val prepared = PreparedQuoteInvoice(command, quoteDetail(), emptyList(), listOf("foto.png"))

    private fun fired(): DomainEvent = argumentCaptor<DomainEvent>().also { verify(events).fireAsync(it.capture()) }.firstValue

    @Test
    fun `a repeated order answers with the existing draft and creates nothing`() {
        val existing = Invoice().also { it.id = 7L; it.invoiceNumber = InvoiceNumber("RE-2026-0007") }
        whenever(invoices.findBySourceQuoteId(command.quoteId)).thenReturn(existing)

        val result = useCase.create(command)

        assertEquals(InvoiceFromQuoteResult.Created(7L, "RE-2026-0007", alreadyExisted = true), result)
        verify(replies).created(command, 7L, "RE-2026-0007")
        verify(reader, never()).read(any())
        verify(writer, never()).write(any())
    }

    @Test
    fun `a repeated order for an invoiced offer is answered with the new order id`() {
        val existing = Invoice().also { it.id = 7L; it.invoiceNumber = InvoiceNumber("RE-2026-0007") }
        whenever(invoices.findBySourceQuoteId(command.quoteId)).thenReturn(existing)
        val again = command.copy(orderId = UUID.randomUUID())

        useCase.create(again)

        verify(replies).created(again, 7L, "RE-2026-0007")
        verify(writer, never()).write(any())
    }

    @Test
    fun `a new order reads the offer, writes the draft and tells the clerk`() {
        whenever(reader.read(command)).thenReturn(prepared)
        whenever(writer.write(prepared)).thenReturn(QuoteInvoiceDraft(8L, "RE-8"))

        val result = useCase.create(command)

        assertEquals(InvoiceFromQuoteResult.Created(8L, "RE-8", alreadyExisted = false), result)
        assertEquals(InvoiceCreatedFromQuote(8L, "AN-1", "RE-8", listOf("foto.png")), fired())
        verify(replies, never()).rejected(any(), any())
    }

    @Test
    fun `the whole call runs as the ordering person and the override ends afterwards`() {
        var seen: String? = null
        whenever(reader.read(command)).thenAnswer { seen = actorOverride.current(); prepared }
        whenever(writer.write(prepared)).thenReturn(QuoteInvoiceDraft(8L, "RE-8"))

        useCase.create(command)

        assertEquals("clerk", seen)
        assertNull(actorOverride.current())
    }

    private fun assertRejected(cause: Throwable, reason: String) {
        whenever(reader.read(command)).thenReturn(prepared)
        whenever(writer.write(prepared)).thenThrow(cause)

        val result = useCase.create(command)

        assertEquals(InvoiceFromQuoteResult.Rejected(reason), result)
        verify(replies).rejected(command, reason)
        assertEquals(InvoiceCreationFromQuoteRejected("AN-1", reason), fired())
    }

    @Test
    fun `a refusal of the writer is answered as rejected`() = assertRejected(QuoteInvoiceRejectedException("Grund A"), "Grund A")

    @Test
    fun `a missing number range is answered with its own message`() =
        assertRejected(NoActiveNumberRangeException(NumberRangeSubject.INVOICE), "Kein aktiver Nummernkreis für 'INVOICE' konfiguriert.")

    @Test
    fun `a domain error is answered with its message`() = assertRejected(InvalidParameterException("Ungültig"), "Ungültig")

    @Test
    fun `a receipt validation error is answered with its message`() =
        assertRejected(ApplicationValidationException("Beleg zu groß"), "Beleg zu groß")

    @Test
    fun `too many receipts are answered with the limit`() {
        val cause = ReceiptCountLimitExceededException(10, 12)
        assertRejected(cause, "Zu viele Belege für eine Rechnung: ${cause.message}")
    }

    @Test
    fun `an offer without a document is answered as rejected before any write`() {
        whenever(reader.read(command)).thenThrow(QuoteInvoiceRejectedException("Das Angebotsdokument (PDF) liegt nicht vor."))

        val result = useCase.create(command)

        assertEquals(InvoiceFromQuoteResult.Rejected("Das Angebotsdokument (PDF) liegt nicht vor."), result)
        verify(writer, never()).write(any())
    }

    @Test
    fun `an offer that no longer exists is answered as rejected`() {
        whenever(reader.read(command)).thenThrow(QuoteNotFoundException("404"))

        val result = useCase.create(command) as InvoiceFromQuoteResult.Rejected

        assertEquals("Das Angebot ist im Angebotsdienst nicht mehr vorhanden: 404", result.reason)
    }

    @Test
    fun `an unreachable offer service propagates so the message is retried`() {
        whenever(reader.read(command)).thenThrow(QuoteServiceException("down"))

        assertThrows(QuoteServiceException::class.java) { useCase.create(command) }

        verify(replies, never()).rejected(any(), any())
        assertNull(actorOverride.current())
    }
}
