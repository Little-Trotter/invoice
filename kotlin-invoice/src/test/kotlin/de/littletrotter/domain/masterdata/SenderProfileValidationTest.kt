package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

class SenderProfileValidationTest {

    @Test
    fun `valid sender profile passes validation`() {
        val s = SenderProfile().apply {
            name = "ACME GmbH"
            invoicePrefix = "ACME"
            email = Email("billing@acme.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
        }
        assertDoesNotThrow { s.validate() }
    }

    @Test
    fun `missing name throws`() {
        val s = SenderProfile().apply { name = ""; invoicePrefix = "PFX" }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `blank invoice prefix throws`() {
        val s = SenderProfile().apply { name = "ACME"; invoicePrefix = "" }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    // ── E1 (E1): BT-34 is not limited to the "EM" email scheme, and is
    //    independent of the BT-29 seller identifier ──────────────────────────

    @Test
    fun `electronicAddress accepts a Peppol participant scheme like 9930, not just EM`() {
        val s = validSenderProfile().apply {
            sellerIdentifier = PartyIdentifier.of("DE123456789", "9930") // BT-29
            electronicAddress = ElectronicAddress.of("DE123456789", "9930") // BT-34
        }
        assertDoesNotThrow { s.validate() }
        assertEquals("9930", s.electronicAddress?.schemeId)
        assertEquals("DE123456789", s.electronicAddress?.value)
        // setting the seller identifier must never have derived/overwritten the electronic address
        assertEquals("9930", s.sellerIdentifier?.schemeId)
    }

    private fun validSenderProfile(): SenderProfile = SenderProfile().apply {
        name = "ACME GmbH"
        invoicePrefix = "ACME"
        email = Email("billing@acme.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }

    // ── validate() — the remaining Pflichtfelder beyond name/invoicePrefix ──────

    @Test
    fun `a sender without any email address is rejected`() {
        val s = validSenderProfile().apply { email = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a sender without a tax number is rejected`() {
        val s = validSenderProfile().apply { taxNumber = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a blank tax number is rejected at construction`() {
        // TaxNumber's own init rejects blank now; a SenderProfile can only ever hold
        // null (not yet set) or a genuinely non-blank, format-valid value.
        assertThrows(InvalidParameterException::class.java) { TaxNumber("   ") }
    }

    @Test
    fun `a sender without a VAT id is rejected`() {
        val s = validSenderProfile().apply { vatId = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a blank VAT id is rejected at construction`() {
        // VatId's own init rejects blank now; a SenderProfile can only ever hold null
        // (not yet set) or a genuinely non-blank, format-valid value.
        assertThrows(InvalidParameterException::class.java) { VatId("   ") }
    }

    @Test
    fun `a sender without an IBAN is rejected`() {
        val s = validSenderProfile().apply { iban = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a blank, non-null seller number is rejected`() {
        val s = validSenderProfile().apply { sellerNumber = "   " }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a non-blank seller number is accepted`() {
        val s = validSenderProfile().apply { sellerNumber = "SN-001" }
        assertDoesNotThrow { s.validate() }
    }

    // ── These tests pin down the *correct* InvoiceValidationError code per field, not just that
    //    validation fails: a wrong code surfaces as the wrong user-facing message, regardless
    //    of which sender field is actually invalid. ──

    @Test
    fun `a blank sender name is reported as SENDER_NAME_BLANK`() {
        val s = validSenderProfile().apply { name = "" }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_NAME_BLANK, ex.error)
    }

    @Test
    fun `a blank invoice prefix is reported as SENDER_INVOICE_PREFIX_BLANK`() {
        val s = validSenderProfile().apply { invoicePrefix = "" }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_INVOICE_PREFIX_BLANK, ex.error)
    }

    @Test
    fun `a missing email is reported as SENDER_EMAIL_MISSING`() {
        val s = validSenderProfile().apply { email = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_EMAIL_MISSING, ex.error)
    }

    @Test
    fun `a missing tax number is reported as SENDER_TAX_NUMBER_MISSING`() {
        val s = validSenderProfile().apply { taxNumber = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_TAX_NUMBER_MISSING, ex.error)
    }

    @Test
    fun `a missing VAT id is reported as SENDER_VAT_ID_MISSING`() {
        val s = validSenderProfile().apply { vatId = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_VAT_ID_MISSING, ex.error)
    }

    @Test
    fun `a missing IBAN is reported as SENDER_IBAN_MISSING`() {
        val s = validSenderProfile().apply { iban = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_IBAN_MISSING, ex.error)
    }

    @Test
    fun `a blank, non-null seller number is reported as SENDER_NUMBER_BLANK`() {
        val s = validSenderProfile().apply { sellerNumber = "   " }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_NUMBER_BLANK, ex.error)
    }

    // ── ensureShortHash() — a short hash is assigned at most once ───────────────

    @Test
    fun `ensureShortHash assigns a hash and reports true when none existed yet`() {
        val s = SenderProfile().apply { shortHash = null }
        assertTrue(s.ensureShortHash())
        assertNotNull(s.shortHash)
    }

    @Test
    fun `ensureShortHash leaves an existing hash untouched and reports false`() {
        val s = SenderProfile().apply { shortHash = "ABCDEFGHJK" }
        assertFalse(s.ensureShortHash())
        assertEquals("ABCDEFGHJK", s.shortHash)
    }

    // ── equals()/hashCode() — persisted identity, not field-by-field comparison ──
    // This is hand-written JPA identity equality (not a data class), the classic entity-equals
    // trap: two transient (unsaved) instances must never compare equal by accident, and two
    // rows loaded for the same persisted id must, regardless of which fields differ in memory.

    @Test
    fun `a sender profile is equal to itself`() {
        val s = validSenderProfile()
        assertEquals(s, s)
    }

    @Test
    fun `a sender profile is never equal to an unrelated type`() {
        assertNotEquals(validSenderProfile() as Any, "not a sender profile" as Any)
    }

    @Test
    fun `two persisted sender profiles with the same id are equal even if other fields differ`() {
        val a = validSenderProfile().apply { id = 42L }
        val b = SenderProfile().apply { id = 42L; name = "Different GmbH" }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two persisted sender profiles with different ids are not equal`() {
        val a = validSenderProfile().apply { id = 1L }
        val b = validSenderProfile().apply { id = 2L }
        assertNotEquals(a, b)
    }

    @Test
    fun `two transient (unsaved) sender profiles are never equal, even with identical fields`() {
        val a = validSenderProfile()
        val b = validSenderProfile()
        assertNotEquals(a, b)
    }

    /**
     * IMP-5 — hashCode must stay stable across persist (id null→value). Holds contract that
     * entity stays hashable even after gaining id.
     */
    @Test
    fun `hashCode stays stable when transient entity gains id`() {
        val s = validSenderProfile()
        val set = hashSetOf(s)
        val hashBefore = s.hashCode()

        s.id = 42L // persist assigns id

        assertEquals(hashBefore, s.hashCode(), "hashCode must not change after id assignment")
        assertTrue(s in set, "entity must remain findable in set after id assignment")
    }

    @Test
    fun `two transient profiles remain two entries in a set`() {
        val a = validSenderProfile()
        val b = validSenderProfile()
        assertEquals(2, hashSetOf(a, b).size, "unequal entities must not displace each other")
    }

    @Test
    fun `a persisted and a transient sender profile are never equal`() {
        val persisted = validSenderProfile().apply { id = 1L }
        val transient = validSenderProfile()
        assertNotEquals(persisted, transient)
    }

    /**
     * IMP-5 — hashCode must not derive from id. Tests the intent, not implementation detail.
     */
    @Test
    fun `hashCode does not derive from id`() {
        val one = validSenderProfile().apply { id = 1L }
        val two = validSenderProfile().apply { id = 999L }

        assertNotEquals(one, two, "different ids remain different entities")
        assertEquals(one.hashCode(), two.hashCode(), "hashCode does not carry id")
    }

    // ── consumer note ────────────────────────────────────────────────────────

    @Test
    fun `consumer note is enabled by default and has no text`() {
        val s = validSenderProfile()

        assertTrue(s.consumerNoteEnabled)
        assertNull(s.consumerNote)
        assertNull(s.consumerNoteFor("de"))
    }

    @Test
    fun `setConsumerNote creates a consumer purpose group, updates in place and clears on last removal`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }
        val en = de.littletrotter.domain.reference.Language().apply { id = 2; code = "en" }

        s.setConsumerNote(lang, "Erster")
        s.setConsumerNote(lang, "Zweiter")
        s.setConsumerNote(en, "Second")

        assertEquals(de.littletrotter.domain.reference.LocalizedTextPurpose.SELLER_CONSUMER_NOTE, s.consumerNote!!.purpose)
        assertEquals("Zweiter", s.consumerNoteFor("de"))
        assertEquals("Second", s.consumerNoteFor("en"))
        assertNull(s.invoiceNote, "the invoice note is a separate group")

        s.setConsumerNote(en, " ")
        assertNotNull(s.consumerNote)
        s.setConsumerNote(lang, null)
        assertNull(s.consumerNote)
    }

    @Test
    fun `setConsumerNote with blank on a profile without group stays null`() {
        val s = validSenderProfile()

        s.setConsumerNote(de.littletrotter.domain.reference.Language().apply { id = 1; code = "de" }, "")

        assertNull(s.consumerNote)
    }

    // ── setInvoiceNote() — comprehensive branch coverage ─────────────────────

    @Test
    fun `setInvoiceNote creates new group when setting first note on null group`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }

        s.setInvoiceNote(lang, "Test note")

        assertNotNull(s.invoiceNote)
        assertEquals("Test note", s.invoiceNoteFor("de"))
    }

    @Test
    fun `setInvoiceNote with whitespace-only value removes entry like null`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }

        // First set a note
        s.setInvoiceNote(lang, "Initial note")
        assertNotNull(s.invoiceNote)

        // Then clear with whitespace
        s.setInvoiceNote(lang, "   ")
        assertNull(s.invoiceNote, "invoiceNote should be null after clearing with whitespace")
    }

    @Test
    fun `setInvoiceNote with empty string removes entry like null`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }

        // First set a note
        s.setInvoiceNote(lang, "Initial note")
        assertNotNull(s.invoiceNote)

        // Then clear with empty string
        s.setInvoiceNote(lang, "")
        assertNull(s.invoiceNote, "invoiceNote should be null after clearing with empty string")
    }

    @Test
    fun `setInvoiceNote clears group when removing last entry`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }

        // Set a note
        s.setInvoiceNote(lang, "Only note")
        assertNotNull(s.invoiceNote)

        // Remove it
        s.setInvoiceNote(lang, null)

        assertNull(s.invoiceNote, "invoiceNote should be null after removing the only entry")
    }

    @Test
    fun `setInvoiceNote updates existing entry instead of creating new one`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }

        s.setInvoiceNote(lang, "First note")
        val groupBefore = s.invoiceNote

        s.setInvoiceNote(lang, "Second note")

        assertSame(groupBefore, s.invoiceNote, "should reuse same group")
        assertEquals("Second note", s.invoiceNoteFor("de"))
    }

    @Test
    fun `setInvoiceNote preserves other language entries when removing one`() {
        val s = validSenderProfile()
        val deLang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }
        val enLang = de.littletrotter.domain.reference.Language().apply { id = 2; code = "en"; isFallback = false }

        s.setInvoiceNote(deLang, "Deutscher Hinweis")
        s.setInvoiceNote(enLang, "English note")

        s.setInvoiceNote(deLang, null)

        assertNotNull(s.invoiceNote, "group should still exist because en entry remains")
        assertNull(s.invoiceNoteFor("de"))
        assertEquals("English note", s.invoiceNoteFor("en"))
    }

    @Test
    fun `validate accepts blank email when Email object itself is null`() {
        val s = validSenderProfile().apply { email = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `validate accepts email with non-blank value`() {
        val s = validSenderProfile()
        assertDoesNotThrow { s.validate() }
    }

    // ── boundary - methods with no dedicated pure-domain coverage yet ────────────

    @Test
    fun `boundary - invoiceNoteFor returns null when no note group exists at all`() {
        val s = validSenderProfile()
        assertNull(s.invoiceNoteFor("de"))
    }

    @Test
    fun `boundary - setInvoiceNote(lang, null) on a profile that never had a note is a no-op`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().apply { id = 1; code = "de"; isFallback = true }

        s.setInvoiceNote(lang, null)

        assertNull(s.invoiceNote)
    }

    @Test
    fun `boundary - createDefaultBillingAddress builds an empty DE billing address owned by this sender`() {
        val s = validSenderProfile().apply { id = 9L }

        val address = s.createDefaultBillingAddress()

        assertEquals(Address.AddressType.BILLING, address.addressType)
        assertEquals(Country.DE, address.country)
        assertTrue(address.isDefault)
        assertTrue(s.ownsAddress(address))
    }

    @Test
    fun `boundary - ownerLabel is sender`() {
        assertEquals("sender", validSenderProfile().ownerLabel())
    }

    @Test
    fun `boundary - toString includes id and name`() {
        val s = validSenderProfile().apply { id = 7L }
        assertTrue(s.toString().contains("7"))
        assertTrue(s.toString().contains("ACME GmbH"))
    }
}
