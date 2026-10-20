package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.shared.country.Country
import de.littletrotter.test.FIXED_TODAY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The seller's foreign tax number is frozen onto the seller snapshot when the invoice is issued. */
class InvoiceRegistrationTaxNumberTest {

    private fun sender(taxNumber: String?): SenderProfile = aSenderProfile { name = "Muster GmbH" }.also {
        it.updateVatRegistrations(
            listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.ofNullable(taxNumber))),
            FIXED_TODAY,
        )
    }

    private fun recipientIn(country: Country?): Recipient = aRecipient().also {
        if (country != null) {
            it.addAddress(Address.forRecipient(it, Address.AddressType.BILLING, "Rue 1", "10115", "Stadt", country, true))
        }
    }

    private fun invoiceFor(sender: SenderProfile, country: Country?, recipientCountry: Country? = Country.LU): Invoice {
        val recipient = recipientIn(recipientCountry)
        val invoice = anInvoice(senderProfile = sender, recipient = recipient, sellerVatRegistrationCountry = country)
        invoice.applyPartySnapshots(InvoicePartySnapshot.forSeller(sender), InvoicePartySnapshot.forBuyer(recipient))
        return invoice
    }

    @Test
    fun `freezing copies the tax number of the selected registration`() {
        val invoice = invoiceFor(sender("1990010112345"), Country.LU)

        invoice.freezeRegistrationTaxNumber()

        assertEquals("1990010112345", invoice.sellerSnapshot()?.registrationTaxNumber?.value)
    }

    @Test
    fun `a later profile change does not reach the frozen value`() {
        val sender = sender("1990010112345")
        val invoice = invoiceFor(sender, Country.LU)
        invoice.freezeRegistrationTaxNumber()

        sender.updateVatRegistrations(
            listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.of("9999999999999"))),
            FIXED_TODAY,
        )

        assertEquals("1990010112345", invoice.sellerSnapshot()?.registrationTaxNumber?.value)
    }

    @Test
    fun `no registration country or no tax number freezes none`() {
        val withoutCountry = invoiceFor(sender("1990010112345"), null)
        withoutCountry.freezeRegistrationTaxNumber()
        val withoutNumber = invoiceFor(sender(null), Country.LU)
        withoutNumber.freezeRegistrationTaxNumber()

        assertNull(withoutCountry.sellerSnapshot()?.registrationTaxNumber)
        assertNull(withoutNumber.sellerSnapshot()?.registrationTaxNumber)
    }

    @Test
    fun `a selected seller wins over the sender profile`() {
        val selected = sender("1990010112345")
        val invoice = anInvoice(
            senderProfile = sender("7777777777777"),
            selectedSeller = selected,
            recipient = recipientIn(Country.LU),
            sellerVatRegistrationCountry = Country.LU,
        )
        invoice.applyPartySnapshots(InvoicePartySnapshot.forSeller(selected), InvoicePartySnapshot.forBuyer(aRecipient()))

        invoice.freezeRegistrationTaxNumber()

        assertEquals("1990010112345", invoice.sellerSnapshot()?.registrationTaxNumber?.value)
    }

    @Test
    fun `a recipient in another country makes the registration not effective`() {
        val invoice = invoiceFor(sender("1990010112345"), Country.LU, recipientCountry = Country.DE)

        invoice.freezeRegistrationTaxNumber()

        assertNull(invoice.sellerSnapshot()?.registrationTaxNumber)
    }

    @Test
    fun `a recipient without any address makes the registration not effective`() {
        val invoice = invoiceFor(sender("1990010112345"), Country.LU, recipientCountry = null)

        invoice.freezeRegistrationTaxNumber()

        assertNull(invoice.sellerSnapshot()?.registrationTaxNumber)
    }

    @Test
    fun `a recipient delivery address country counts when there is no billing address`() {
        val sender = sender("1990010112345")
        val recipient = aRecipient().also {
            it.addDeliveryAddress(Address.forRecipient(it, Address.AddressType.DELIVERY, "Rue 2", "10115", "Stadt", Country.LU, true))
        }
        val invoice = anInvoice(senderProfile = sender, recipient = recipient, sellerVatRegistrationCountry = Country.LU)
        invoice.applyPartySnapshots(InvoicePartySnapshot.forSeller(sender), InvoicePartySnapshot.forBuyer(recipient))

        invoice.freezeRegistrationTaxNumber()

        assertEquals("1990010112345", invoice.sellerSnapshot()?.registrationTaxNumber?.value)
    }

    @Test
    fun `a country without a matching registration freezes none`() {
        val invoice = invoiceFor(sender("1990010112345"), Country.FR, recipientCountry = Country.FR)

        invoice.freezeRegistrationTaxNumber()

        assertNull(invoice.sellerSnapshot()?.registrationTaxNumber)
    }

    @Test
    fun `an invoice without any seller profile freezes none`() {
        val seller = sender("1990010112345")
        val recipient = recipientIn(Country.LU)
        val invoice = anInvoice(senderProfile = null, recipient = recipient, sellerVatRegistrationCountry = Country.LU)
        invoice.applyPartySnapshots(InvoicePartySnapshot.forSeller(seller), InvoicePartySnapshot.forBuyer(recipient))

        invoice.freezeRegistrationTaxNumber()

        assertNull(invoice.sellerSnapshot()?.registrationTaxNumber)
    }

    @Test
    fun `freezing without snapshots is a no-op`() {
        anInvoice(senderProfile = sender("1"), sellerVatRegistrationCountry = Country.LU).freezeRegistrationTaxNumber()
    }

    @Test
    fun `the buyer row refuses a registration tax number`() {
        val buyer = InvoicePartySnapshot.forBuyer(aRecipient())

        assertThrows(InvalidParameterException::class.java) { buyer.freezeRegistrationTaxNumber(ForeignTaxNumber.of("1")) }
    }

    @Test
    fun `a refreshed snapshot copy carries the registration tax number`() {
        val target = InvoicePartySnapshot.forSeller(sender(null))
        val source = InvoicePartySnapshot.forSeller(sender(null)).also { it.freezeRegistrationTaxNumber(ForeignTaxNumber.of("55")) }

        target.copyIdentityFrom(source)

        assertEquals("55", target.registrationTaxNumber?.value)
    }

    @Test
    fun `a draft reads the live profile, an issued invoice its frozen value`() {
        val seller = sender("1990010112345")
        val recipient = recipientIn(Country.LU)
        val draft = anInvoice(id = 1L, senderProfile = seller, recipient = recipient, sellerVatRegistrationCountry = Country.LU)
        draft.applyPartySnapshots(InvoicePartySnapshot.forSeller(seller), InvoicePartySnapshot.forBuyer(recipient))
        assertNull(draft.registrationTaxNumberFreeze())

        draft.freezeRegistrationTaxNumber()
        draft.transitionTo(InvoiceStatus.IN_REVIEW)
        draft.transitionTo(InvoiceStatus.ISSUED)

        val freeze = draft.registrationTaxNumberFreeze()
        assertEquals(Country.LU, freeze?.country)
        assertEquals("1990010112345", freeze?.taxNumber?.value)
    }

    @Test
    fun `an issued invoice without a seller snapshot reads the live profile`() {
        val issued = anInvoice(
            status = InvoiceStatus.ISSUED,
            senderProfile = sender("1990010112345"),
            sellerVatRegistrationCountry = Country.LU,
        )
        assertNull(issued.registrationTaxNumberFreeze())
    }
}
