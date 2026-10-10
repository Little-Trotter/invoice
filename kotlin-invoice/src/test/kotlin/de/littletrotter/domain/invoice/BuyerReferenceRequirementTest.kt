package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Recipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber

/** BR-DE-15 / PEPPOL-EN16931-R008 — [ensureBuyerReferenceForFinalization] closes the BT-10 gap
 *  right before SENT, falling back to the recipient's routing id when the invoice itself
 *  carries none. */
class BuyerReferenceRequirementTest {

    @Test
    fun `an already-set buyerReference is left untouched, no recipient lookup needed`() {
        val invoice = Invoice().apply {
            references.buyerReference = "existing-ref"
            recipient = null
        }

        invoice.ensureBuyerReferenceForFinalization()

        assertEquals("existing-ref", invoice.references.buyerReference)
    }

    @Test
    fun `a blank buyerReference with no recipient at all is a real gap, not silently defaulted`() {
        val invoice = Invoice().apply { references.buyerReference = null; recipient = null }

        assertThrows(BuyerReferenceMissingException::class.java) { invoice.ensureBuyerReferenceForFinalization() }
    }

    @Test
    fun `a recipient without a routingId is still a real gap`() {
        val invoice = Invoice().apply {
            references.buyerReference = ""
            recipient = Recipient().apply { customerNumber = CustomerNumber("K-1"); name = "Kunde"; routingId = null }
        }

        assertThrows(BuyerReferenceMissingException::class.java) { invoice.ensureBuyerReferenceForFinalization() }
    }

    @Test
    fun `a recipient with a blank routingId is treated the same as no routingId`() {
        val invoice = Invoice().apply {
            references.buyerReference = "   "
            recipient = Recipient().apply { customerNumber = CustomerNumber("K-1"); name = "Kunde"; routingId = "  " }
        }

        assertThrows(BuyerReferenceMissingException::class.java) { invoice.ensureBuyerReferenceForFinalization() }
    }

    @Test
    fun `a recipient's routingId fills the gap when the invoice itself has none`() {
        val invoice = Invoice().apply {
            references.buyerReference = null
            recipient = Recipient().apply { customerNumber = CustomerNumber("K-1"); name = "Kunde"; routingId = "991-01484-64" }
        }

        invoice.ensureBuyerReferenceForFinalization()

        assertEquals("991-01484-64", invoice.references.buyerReference)
    }
}
