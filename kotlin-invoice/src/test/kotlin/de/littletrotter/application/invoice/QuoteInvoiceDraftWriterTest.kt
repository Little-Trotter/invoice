package de.littletrotter.application.invoice

import  de.littletrotter.fixture.anInvoice
import de.littletrotter.application.invoice.port.outbound.QuoteReplyPublisherPort
import de.littletrotter.application.quote.port.inbound.QuoteB2bValue
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.buyerSnapshot
import de.littletrotter.domain.invoice.sellerSnapshot
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.edit
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.ui.view.quote.quoteDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.UUID

class QuoteInvoiceDraftWriterTest {

    private val lifecycle: InvoiceLifecycleService = mock()
    private val items: QuoteInvoiceItems = mock()
    private val receipts: QuoteInvoiceReceipts = mock()
    private val senders: SenderProfileRepository = mock()
    private val recipients: RecipientRepository = mock()
    private val b2bValues: B2bFieldValueService = mock()
    private val replies: QuoteReplyPublisherPort = mock()
    private val writer = QuoteInvoiceDraftWriter(lifecycle, items, receipts, senders, recipients, b2bValues, replies)

    private val invoiceDate = LocalDate.of(2026, 10, 6)
    private val invoice = anInvoice(id = 100L, invoiceNumber = InvoiceNumber("RE-2026-0001"), invoiceDate = invoiceDate)
    private val command = CreateInvoiceFromQuoteCommand(UUID.randomUUID(), "AN-2026-0001", LocalDate.of(2026, 10, 5), "PO-9", "clerk", UUID.randomUUID())
    private val receiptSources = listOf(QuoteReceiptSource("a.pdf", "Angebot", LocalDate.of(2026, 10, 1), byteArrayOf(1)))

    @BeforeEach
    fun setUp() {
        whenever(senders.findDefault()).thenReturn(SenderProfile().also { it.edit { id = 1L }; it.edit { name = "Verkäufer GmbH" } })
        whenever(recipients.findById(2L)).thenReturn(Recipient().also { it.edit { id = 2L }; it.edit { name = "Stammdaten-Name" } })
        whenever(lifecycle.create(1L, 2L, null)).thenReturn(invoice)
        whenever(lifecycle.findById(100L)).thenReturn(invoice)
    }

    private fun prepared(modify: (QuoteDetail) -> QuoteDetail = { it }): PreparedQuoteInvoice {
        val detail = quoteDetail(id = command.quoteId.toString()) { modify(it.copy(party = it.party.copy(customerId = "2", name = "Angebots-Name"), b2bValues = emptyList())) }
        return PreparedQuoteInvoice(command, detail, receiptSources, listOf("foto.png"))
    }

    @Test
    fun `the draft is created, filled from the offer, numbered and answered`() {
        val draft = writer.write(prepared())

        assertEquals(100L, draft.invoiceId)
        assertEquals("RE-2026-0001", draft.invoiceNumber)
        val order: InOrder = inOrder(lifecycle, items, receipts, replies)
        order.verify(lifecycle).create(1L, 2L, null)
        order.verify(items, org.mockito.kotlin.times(2)).add(any(), any(), any())
        order.verify(lifecycle).save(invoice)
        order.verify(receipts).attach(100L, receiptSources)
        order.verify(replies).created(command, 100L, "RE-2026-0001")
    }

    @Test
    fun `positions are taken over in offer order`() {
        val detail = prepared { it.copy(items = it.items.reversed()) }

        writer.write(detail)

        val order = inOrder(items)
        detail.detail.items.sortedBy { it.position }.forEach { order.verify(items).add(100L, it, invoiceDate) }
    }

    @Test
    fun `buyer snapshot and references come from the offer, not from master data`() {
        writer.write(prepared())

        val buyer = invoice.buyerSnapshot()!!
        assertEquals("Angebots-Name", buyer.name)
        assertEquals("DE123456789", buyer.vatId?.value)
        assertEquals("Verkäufer GmbH", invoice.sellerSnapshot()!!.name)
        assertEquals(command.quoteId, invoice.sourceQuoteId)
        assertEquals("AN-2026-0001", invoice.references.orderReference)
        assertEquals(LocalDate.of(2026, 10, 5), invoice.references.orderReferenceDate)
        assertEquals("AN-2026-0001", invoice.references.offerReference)
        assertEquals(LocalDate.of(2026, 10, 1), invoice.references.offerReferenceDate)
        assertEquals("PO-9", invoice.references.purchaseOrderReference)
    }

    @Test
    fun `an offer without vat id keeps the master data vat id and bound b2b values reach the invoice`() {
        writer.write(prepared { it.copy(party = it.party.copy(vatId = null), b2bValues = listOf(QuoteB2bValue(11, "PRJ-1"), QuoteB2bValue(13, "B-4711"))) })

        assertEquals("PRJ-1", invoice.references.projectNumber)
        // an explicit customer order number outranks the bound B2B value
        assertEquals("PO-9", invoice.references.purchaseOrderReference)
    }

    @Test
    fun `the bound order reference is used when the acceptance carried none`() {
        val noOrder = CreateInvoiceFromQuoteCommand(command.quoteId, command.quoteNumber, command.acceptedOn, " ", "clerk", command.orderId)
        val base = prepared { it.copy(b2bValues = listOf(QuoteB2bValue(13, "B-4711"))) }

        writer.write(PreparedQuoteInvoice(noOrder, base.detail, receiptSources, emptyList()))

        assertEquals("B-4711", invoice.references.purchaseOrderReference)
    }

    @Test
    fun `free b2b values are stored as invoice field values`() {
        val free = B2bTerm.entries.first { it.binding == B2bTermBinding.FREE && it.level == de.littletrotter.shared.b2b.B2bTermLevel.HEADER }

        writer.write(prepared { it.copy(b2bValues = listOf(QuoteB2bValue(free.code, "wert"))) })

        verify(b2bValues).saveFieldValues(B2bFieldOwnerType.INVOICE, 100L, mapOf(free.officialCode to "wert"))
    }

    @Test
    fun `without any free b2b value nothing is stored`() {
        writer.write(prepared())

        verify(b2bValues, never()).saveFieldValues(any(), any(), any())
    }

    @Test
    fun `an unknown or uncatalogued b2b term is refused before anything is created`() {
        val unknown = assertThrows(QuoteInvoiceRejectedException::class.java) {
            writer.write(prepared { it.copy(b2bValues = listOf(QuoteB2bValue(999_999, "x"))) })
        }
        val reference = B2bTerm.entries.first { it.binding == null }
        val uncatalogued = assertThrows(QuoteInvoiceRejectedException::class.java) {
            writer.write(prepared { it.copy(b2bValues = listOf(QuoteB2bValue(reference.code, "x"))) })
        }

        assertEquals("Das B2B-Feld BT-999999 des Angebots ist unbekannt.", unknown.reason)
        assertEquals("Das B2B-Feld BT-${reference.code} des Angebots ist unbekannt.", uncatalogued.reason)
        verify(lifecycle, never()).create(any(), any(), any())
    }

    @Test
    fun `a bound term other than project and order number cannot be taken over`() {
        val other = B2bTerm.entries.first { it.binding == B2bTermBinding.BOUND && it.boundProperty !in setOf("projectNumber", "purchaseOrderReference") }

        val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) {
            writer.write(prepared { it.copy(b2bValues = listOf(QuoteB2bValue(other.code, "x"))) })
        }

        assertEquals("Das B2B-Feld ${other.officialCode} des Angebots kann nicht in die Rechnung übernommen werden.", refusal.reason)
    }

    @Test
    fun `a missing default sender profile is refused`() {
        whenever(senders.findDefault()).thenReturn(null)

        val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) { writer.write(prepared()) }

        assertEquals("Es ist kein Standard-Absenderprofil festgelegt.", refusal.reason)
        verify(lifecycle, never()).create(any(), any(), any())
    }

    @Test
    fun `a customer that is not in master data is refused by name`() {
        val unknownId = prepared { it.copy(party = it.party.copy(customerId = "99")) }
        val notNumeric = prepared { it.copy(party = it.party.copy(customerId = "c-1")) }

        listOf(unknownId, notNumeric).forEach {
            val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) { writer.write(it) }
            assertEquals("Der Kunde 'Angebots-Name' ist in den Stammdaten nicht vorhanden.", refusal.reason)
        }
    }

    @Test
    fun `nothing is answered when the draft fails half way`() {
        whenever(items.add(any(), any(), any())).thenThrow(QuoteInvoiceRejectedException("Einheit"))

        assertThrows(QuoteInvoiceRejectedException::class.java) { writer.write(prepared()) }

        verify(replies, never()).created(any(), any(), any())
        assertNull(invoice.sourceQuoteId)
    }

    private fun withDates(delivery: LocalDate?, from: LocalDate?, to: LocalDate?): PreparedQuoteInvoice {
        val dated = command.copy(deliveryDate = delivery, servicePeriodFrom = from, servicePeriodTo = to)
        return PreparedQuoteInvoice(dated, prepared().detail, receiptSources, emptyList())
    }

    @Test
    fun `the delivery date of the order reaches the invoice header`() {
        writer.write(withDates(LocalDate.of(2026, 9, 30), null, null))

        assertEquals(LocalDate.of(2026, 9, 30), invoice.deliveryDate)
        assertNull(invoice.servicePeriodFrom)
        assertNull(invoice.servicePeriodTo)
    }

    @Test
    fun `the service period of the order reaches the invoice header`() {
        writer.write(withDates(null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))

        assertEquals(LocalDate.of(2026, 9, 1), invoice.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 9, 30), invoice.servicePeriodTo)
        assertNull(invoice.deliveryDate)
    }

    @Test
    fun `an order without service dates leaves the header dates open`() {
        writer.write(prepared())

        assertNull(invoice.deliveryDate)
        assertNull(invoice.servicePeriodFrom)
    }

    @Test
    fun `a period that ends before it starts is refused before any invoice exists`() {
        val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) {
            writer.write(withDates(null, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
        }

        assertEquals("Das Ende des Leistungszeitraums liegt vor dem Beginn.", refusal.reason)
        verify(lifecycle, never()).create(any(), any(), any())
    }

    @Test
    fun `a period with one end only is refused`() {
        assertThrows(QuoteInvoiceRejectedException::class.java) { writer.write(withDates(null, LocalDate.of(2026, 9, 1), null)) }
        assertThrows(QuoteInvoiceRejectedException::class.java) { writer.write(withDates(null, null, LocalDate.of(2026, 9, 1))) }
    }

    @Test
    fun `a delivery date outside the service period is refused`() {
        val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) {
            writer.write(withDates(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
        }

        assertEquals("Das Leistungsdatum liegt außerhalb des Leistungszeitraums.", refusal.reason)
    }
}
