package de.littletrotter.domain.invoice

import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** [InvoicePartySnapshot], [InvoicePaymentTermSnapshot] write operations. */
class InvoicePartyAndTermSnapshotsTest {

    // ── InvoicePartySnapshot ───────────────────────────────────────────────────────────────────

    @Test
    fun `forSeller takes the seller role, name and vat id from the sender profile`() {
        val sender = aSenderProfile {
            name = "Sender GmbH"
            vatId = VatId("DE123456789")
        }

        val snapshot = InvoicePartySnapshot.forSeller(sender)

        assertEquals(PartyRole.SELLER, snapshot.partyRole)
        assertEquals("Sender GmbH", snapshot.name)
        assertEquals(VatId("DE123456789"), snapshot.vatId)
        assertNull(snapshot.buyerReference)
    }

    @Test
    fun `forBuyer defaults the buyer reference to the routing id and accepts an override`() {
        val recipient = aRecipient {
            name = "Buyer AG"
            routingId = "991-01484-64"
        }

        assertEquals("991-01484-64", InvoicePartySnapshot.forBuyer(recipient).buyerReference)
        assertEquals("OVERRIDE", InvoicePartySnapshot.forBuyer(recipient, "OVERRIDE").buyerReference)
        assertNull(InvoicePartySnapshot.forBuyer(recipient, null).buyerReference)
        assertEquals(PartyRole.BUYER, InvoicePartySnapshot.forBuyer(recipient).partyRole)
    }

    @Test
    fun `correctIdentity replaces the name and the vat id`() {
        val snapshot = InvoicePartySnapshot.forSeller(aSenderProfile { name = "Old"; vatId = VatId("DE123456789") })

        snapshot.correctIdentity("New", VatId("DE987654321"))

        assertEquals("New", snapshot.name)
        assertEquals(VatId("DE987654321"), snapshot.vatId)
    }

    @Test
    fun `correctIdentity without a vat id keeps the existing vat id`() {
        val snapshot = InvoicePartySnapshot.forSeller(aSenderProfile { name = "Old"; vatId = VatId("DE123456789") })

        snapshot.correctIdentity("New", null)

        assertEquals("New", snapshot.name)
        assertEquals(VatId("DE123456789"), snapshot.vatId)
    }

    // ── InvoicePaymentTermSnapshot ─────────────────────────────────────────────────────────────

    @Test
    fun `of copies days and discount from the payment term and resolves the name`() {
        val term = aPaymentTerm(
            name = "Netto 14",
            dueInDays = 14,
            discountPercent = DiscountPercent(BigDecimal("2.00")),
            discountDays = 7,
        )

        val snapshot = InvoicePaymentTermSnapshot.of(term, "de")

        assertEquals("Netto 14", snapshot.name)
        assertEquals(14, snapshot.dueInDays)
        assertEquals(BigDecimal("2.00"), snapshot.discountPercent.value)
        assertEquals(7, snapshot.discountDays)
    }

    @Test
    fun `of a term without any name freezes an empty name`() {
        val snapshot = InvoicePaymentTermSnapshot.of(aPaymentTerm(dueInDays = 3), "en")

        assertEquals("", snapshot.name)
        assertEquals(3, snapshot.dueInDays)
        assertNull(snapshot.discountDays)
    }
}
