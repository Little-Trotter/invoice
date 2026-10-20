package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

class RecipientValidationTest {

    private fun validRecipient() = aRecipient {
        customerNumber = CustomerNumber("CUST-1")
        name = "Kunde"
        email = Email("kunde@example.com")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }

    @Test
    fun `valid recipient passes validation`() {
        assertDoesNotThrow { validRecipient().validate() }
    }

    @Test
    fun `a blank customer number is rejected at construction`() {
        // CustomerNumber's own init rejects blank now; a Recipient can only ever hold
        // null (not-yet-assigned) or a genuinely non-blank value.
        assertThrows(InvalidParameterException::class.java) { CustomerNumber("") }
    }

    @Test
    fun `missing customer number throws`() {
        val r = validRecipient().edit { customerNumber = null }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    @Test
    fun `missing name throws`() {
        val r = validRecipient().edit { name = "" }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    // BT-91 (buyer IBAN) is never required: this system bills by credit transfer, where the
    // SELLER's IBAN (BT-84) is the one that must be set, not the buyer's — a buyer IBAN would
    // only matter for SEPA direct debit, which this system does not do.
    @Test
    fun `missing iban is valid`() {
        val r = validRecipient().edit { iban = null }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `missing email throws`() {
        val r = validRecipient().edit { email = null }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    @Test
    fun `a blank vatId is rejected at construction`() {
        // VatId's own init rejects blank now; a Recipient can only ever hold null
        // (not required) or a genuinely non-blank, format-valid value.
        assertThrows(InvalidParameterException::class.java) { VatId("   ") }
    }

    @Test
    fun `a missing vatId is rejected just like a blank one used to be`() {
        val r = validRecipient().edit { vatId = null }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    // ── email validation — null-safe access branches ──────────────────────────

    @Test
    fun `a recipient with blank email value is rejected`() {
        val r = validRecipient().edit { email = Email("") }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    @Test
    fun `a recipient with whitespace-only email value is rejected`() {
        val r = validRecipient().edit { email = Email("   ") }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    @Test
    fun `a recipient with valid email value passes validation`() {
        val r = validRecipient().edit { email = Email("test@example.com") }
        assertDoesNotThrow { r.validate() }
    }
}
