package de.littletrotter.domain.invoice

import de.littletrotter.fixture.aRecipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.anInvoice

/** BR-DE-15 / PEPPOL-EN16931-R008 — [ensureBuyerReferenceForFinalization] closes the BT-10 gap
 *  right before ISSUED, falling back to the recipient's routing id when the invoice itself
 *  carries none. */
class BuyerReferenceRequirementTest {

    @Test
    fun `an already-set buyerReference is left untouched, no recipient lookup needed`() {
        val invoice = anInvoice(recipient = null, references = InvoiceReferencesChange(buyerReference = "existing-ref"))

        invoice.ensureBuyerReferenceForFinalization()

        assertEquals("existing-ref", invoice.references.buyerReference)
    }

    @Test
    fun `a blank buyerReference with no recipient at all is a real gap, not silently defaulted`() {
        val invoice = anInvoice(recipient = null, references = InvoiceReferencesChange(buyerReference = null))

        assertThrows(BuyerReferenceMissingException::class.java) { invoice.ensureBuyerReferenceForFinalization() }
    }

    @Test
    fun `a recipient without a routingId is still a real gap`() {
        val invoice = anInvoice(
            recipient = aRecipient { customerNumber = CustomerNumber("K-1"); name = "Kunde"; routingId = null },
            references = InvoiceReferencesChange(buyerReference = ""),
        )

        assertThrows(BuyerReferenceMissingException::class.java) { invoice.ensureBuyerReferenceForFinalization() }
    }

    @Test
    fun `a recipient with a blank routingId is treated the same as no routingId`() {
        val invoice = anInvoice(
            recipient = aRecipient { customerNumber = CustomerNumber("K-1"); name = "Kunde"; routingId = "  " },
            references = InvoiceReferencesChange(buyerReference = "   "),
        )

        assertThrows(BuyerReferenceMissingException::class.java) { invoice.ensureBuyerReferenceForFinalization() }
    }

    @Test
    fun `a recipient's routingId fills the gap when the invoice itself has none`() {
        val invoice = anInvoice(
            recipient = aRecipient { customerNumber = CustomerNumber("K-1"); name = "Kunde"; routingId = "991-01484-64" },
            references = InvoiceReferencesChange(buyerReference = null),
        )

        invoice.ensureBuyerReferenceForFinalization()

        assertEquals("991-01484-64", invoice.references.buyerReference)
    }
}
