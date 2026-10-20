package de.littletrotter.domain.masterdata

import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
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
        val s = aSenderProfile {
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
        val s = aSenderProfile { name = ""; invoicePrefix = "PFX" }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `blank invoice prefix throws`() {
        val s = aSenderProfile { name = "ACME"; invoicePrefix = "" }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    // ── E1 (E1): BT-34 is not limited to the "EM" email scheme, and is
    //    independent of the BT-29 seller identifier ──────────────────────────

    @Test
    fun `electronicAddress accepts a Peppol participant scheme like 9930, not just EM`() {
        val s = validSenderProfile().edit {
            sellerIdentifier = PartyIdentifier.of("DE123456789", "9930") // BT-29
            electronicAddress = ElectronicAddress.of("DE123456789", "9930") // BT-34
        }
        assertDoesNotThrow { s.validate() }
        assertEquals("9930", s.electronicAddress?.schemeId)
        assertEquals("DE123456789", s.electronicAddress?.value)
        // setting the seller identifier must never have derived/overwritten the electronic address
        assertEquals("9930", s.sellerIdentifier?.schemeId)
    }

    private fun validSenderProfile(): SenderProfile = aSenderProfile {
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
        val s = validSenderProfile().edit { email = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a sender without a tax number is rejected`() {
        val s = validSenderProfile().edit { taxNumber = null }
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
        val s = validSenderProfile().edit { vatId = null }
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
        val s = validSenderProfile().edit { iban = null }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a blank, non-null seller number is rejected`() {
        val s = validSenderProfile().edit { sellerNumber = "   " }
        assertThrows(InvoiceValidationException::class.java) { s.validate() }
    }

    @Test
    fun `a non-blank seller number is accepted`() {
        val s = validSenderProfile().edit { sellerNumber = "SN-001" }
        assertDoesNotThrow { s.validate() }
    }

    // ── These tests pin down the *correct* InvoiceValidationError code per field, not just that
    //    validation fails: a wrong code surfaces as the wrong user-facing message, regardless
    //    of which sender field is actually invalid. ──

    @Test
    fun `a blank sender name is reported as SENDER_NAME_BLANK`() {
        val s = validSenderProfile().edit { name = "" }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_NAME_BLANK, ex.error)
    }

    @Test
    fun `a blank invoice prefix is reported as SENDER_INVOICE_PREFIX_BLANK`() {
        val s = validSenderProfile().edit { invoicePrefix = "" }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_INVOICE_PREFIX_BLANK, ex.error)
    }

    @Test
    fun `a missing email is reported as SENDER_EMAIL_MISSING`() {
        val s = validSenderProfile().edit { email = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_EMAIL_MISSING, ex.error)
    }

    @Test
    fun `a missing tax number is reported as SENDER_TAX_NUMBER_MISSING`() {
        val s = validSenderProfile().edit { taxNumber = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_TAX_NUMBER_MISSING, ex.error)
    }

    @Test
    fun `a missing VAT id is reported as SENDER_VAT_ID_MISSING`() {
        val s = validSenderProfile().edit { vatId = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_VAT_ID_MISSING, ex.error)
    }

    @Test
    fun `a missing IBAN is reported as SENDER_IBAN_MISSING`() {
        val s = validSenderProfile().edit { iban = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_IBAN_MISSING, ex.error)
    }

    @Test
    fun `a blank, non-null seller number is reported as SENDER_NUMBER_BLANK`() {
        val s = validSenderProfile().edit { sellerNumber = "   " }
        val ex = assertThrows(InvoiceValidationException::class.java) { s.validate() }
        assertEquals(InvoiceValidationError.SENDER_NUMBER_BLANK, ex.error)
    }

    // ── ensureShortHash() — a short hash is assigned at most once ───────────────

    @Test
    fun `ensureShortHash assigns a hash and reports true when none existed yet`() {
        val s = aSenderProfile { shortHash = null }
        assertTrue(s.ensureShortHash())
        assertNotNull(s.shortHash)
    }

    @Test
    fun `ensureShortHash leaves an existing hash untouched and reports false`() {
        val s = aSenderProfile { shortHash = "ABCDEFGHJK" }
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
        val a = validSenderProfile().edit { id = 42L }
        val b = aSenderProfile { id = 42L; name = "Different GmbH" }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two persisted sender profiles with different ids are not equal`() {
        val a = validSenderProfile().edit { id = 1L }
        val b = validSenderProfile().edit { id = 2L }
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

        s.edit { id = 42L } // persist assigns id

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
        val persisted = validSenderProfile().edit { id = 1L }
        val transient = validSenderProfile()
        assertNotEquals(persisted, transient)
    }

    /**
     * IMP-5 — hashCode must not derive from id. Tests the intent, not implementation detail.
     */
    @Test
    fun `hashCode does not derive from id`() {
        val one = validSenderProfile().edit { id = 1L }
        val two = validSenderProfile().edit { id = 999L }

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
    fun `changeConsumerNote creates a consumer purpose group, updates in place and clears on last removal`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }
        val en = de.littletrotter.domain.reference.Language().edit { id = 2; code = "en" }

        s.changeConsumerNote(lang, "Erster")
        s.changeConsumerNote(lang, "Zweiter")
        s.changeConsumerNote(en, "Second")

        assertEquals(de.littletrotter.domain.reference.LocalizedTextPurpose.SELLER_CONSUMER_NOTE, s.consumerNote!!.purpose)
        assertEquals("Zweiter", s.consumerNoteFor("de"))
        assertEquals("Second", s.consumerNoteFor("en"))
        assertNull(s.invoiceNote, "the invoice note is a separate group")

        s.changeConsumerNote(en, " ")
        assertNotNull(s.consumerNote)
        s.changeConsumerNote(lang, null)
        assertNull(s.consumerNote)
    }

    @Test
    fun `changeConsumerNote with blank on a profile without group stays null`() {
        val s = validSenderProfile()

        s.changeConsumerNote(de.littletrotter.domain.reference.Language().edit { id = 1; code = "de" }, "")

        assertNull(s.consumerNote)
    }

    // ── changeInvoiceNote() — comprehensive branch coverage ─────────────────────

    @Test
    fun `changeInvoiceNote creates new group when setting first note on null group`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }

        s.changeInvoiceNote(lang, "Test note")

        assertNotNull(s.invoiceNote)
        assertEquals("Test note", s.invoiceNoteFor("de"))
    }

    @Test
    fun `changeInvoiceNote with whitespace-only value removes entry like null`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }

        // First set a note
        s.changeInvoiceNote(lang, "Initial note")
        assertNotNull(s.invoiceNote)

        // Then clear with whitespace
        s.changeInvoiceNote(lang, "   ")
        assertNull(s.invoiceNote, "invoiceNote should be null after clearing with whitespace")
    }

    @Test
    fun `changeInvoiceNote with empty string removes entry like null`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }

        // First set a note
        s.changeInvoiceNote(lang, "Initial note")
        assertNotNull(s.invoiceNote)

        // Then clear with empty string
        s.changeInvoiceNote(lang, "")
        assertNull(s.invoiceNote, "invoiceNote should be null after clearing with empty string")
    }

    @Test
    fun `changeInvoiceNote clears group when removing last entry`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }

        // Set a note
        s.changeInvoiceNote(lang, "Only note")
        assertNotNull(s.invoiceNote)

        // Remove it
        s.changeInvoiceNote(lang, null)

        assertNull(s.invoiceNote, "invoiceNote should be null after removing the only entry")
    }

    @Test
    fun `changeInvoiceNote updates existing entry instead of creating new one`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }

        s.changeInvoiceNote(lang, "First note")
        val groupBefore = s.invoiceNote

        s.changeInvoiceNote(lang, "Second note")

        assertSame(groupBefore, s.invoiceNote, "should reuse same group")
        assertEquals("Second note", s.invoiceNoteFor("de"))
    }

    @Test
    fun `changeInvoiceNote preserves other language entries when removing one`() {
        val s = validSenderProfile()
        val deLang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }
        val enLang = de.littletrotter.domain.reference.Language().edit { id = 2; code = "en"; isFallback = false }

        s.changeInvoiceNote(deLang, "Deutscher Hinweis")
        s.changeInvoiceNote(enLang, "English note")

        s.changeInvoiceNote(deLang, null)

        assertNotNull(s.invoiceNote, "group should still exist because en entry remains")
        assertNull(s.invoiceNoteFor("de"))
        assertEquals("English note", s.invoiceNoteFor("en"))
    }

    @Test
    fun `validate accepts blank email when Email object itself is null`() {
        val s = validSenderProfile().edit { email = null }
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
    fun `boundary - changeInvoiceNote(lang, null) on a profile that never had a note is a no-op`() {
        val s = validSenderProfile()
        val lang = de.littletrotter.domain.reference.Language().edit { id = 1; code = "de"; isFallback = true }

        s.changeInvoiceNote(lang, null)

        assertNull(s.invoiceNote)
    }

    @Test
    fun `boundary - createDefaultBillingAddress builds an empty DE billing address owned by this sender`() {
        val s = validSenderProfile().edit { id = 9L }

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
        val s = validSenderProfile().edit { id = 7L }
        assertTrue(s.toString().contains("7"))
        assertTrue(s.toString().contains("ACME GmbH"))
    }

    // ── intent methods ────────────────────────────────────────────────────

    @Test
    fun `update writes every edited field and leaves identity and defaults alone`() {
        val s = SenderProfile()
        s.update(
            SenderProfileDetails(
                name = "Neu GmbH",
                taxNumber = TaxNumber("1234567890"),
                vatId = VatId("DE999999999"),
                invoicePrefix = "NEU-",
                email = Email("neu@example.invalid"),
                registeredSeat = "Bonn",
                craftsChamberRegistered = true,
            ),
        )
        assertEquals("Neu GmbH", s.name)
        assertEquals("NEU-", s.invoicePrefix)
        assertEquals("Bonn", s.registeredSeat)
        assertEquals(VatId("DE999999999"), s.vatId)
        assertTrue(s.craftsChamberRegistered)
        assertNull(s.id)
        assertFalse(s.isDefault)
    }

    @Test
    fun `editableDetails mirrors the values written by update`() {
        val s = SenderProfile()
        val details = SenderProfileDetails(name = "A", bankName = "Bank", website = "w", legalForm = LegalForm.SOLE_PROPRIETOR)
        s.update(details)
        assertEquals(details, s.editableDetails())
    }

    @Test
    fun `copyEditableFieldsFrom moves the edited fields and the default flag`() {
        val source = SenderProfile().apply {
            update(SenderProfileDetails(name = "Quelle", invoicePrefix = "Q-", phone = "123"))
            markAsDefault()
        }
        val target = SenderProfile().apply { update(SenderProfileDetails(name = "Ziel")) }
        target.copyEditableFieldsFrom(source)
        assertEquals("Quelle", target.name)
        assertEquals("Q-", target.invoicePrefix)
        assertEquals("123", target.phone)
        assertTrue(target.isDefault)
        source.revokeDefault()
        target.copyEditableFieldsFrom(source)
        assertFalse(target.isDefault)
    }

    @Test
    fun `changeInvoicePrefix replaces only the prefix`() {
        val s = SenderProfile().apply { update(SenderProfileDetails(name = "A", phone = "1")) }
        s.changeInvoicePrefix("RE-")
        assertEquals("RE-", s.invoicePrefix)
        assertEquals("A", s.name)
        assertEquals("1", s.phone)
    }

    @Test
    fun `changeElectronicAddress sets and clears the address and keeps other fields`() {
        val s = SenderProfile().apply { update(SenderProfileDetails(name = "A")) }
        val address = ElectronicAddress.of("billing@example.invalid", "EM")
        s.changeElectronicAddress(address)
        assertEquals(address, s.electronicAddress)
        assertEquals("A", s.name)
        s.changeElectronicAddress(null)
        assertNull(s.electronicAddress)
    }

    @Test
    fun `assignSellerNumber stores number prefix and sequence and clearSellerNumber drops only the number`() {
        val s = SenderProfile()
        s.assignSellerNumber("S-", 7L, "S-0007")
        assertEquals("S-", s.sellerNumberPrefix)
        assertEquals(7L, s.sellerNumberSequence)
        assertEquals("S-0007", s.sellerNumber)
        s.clearSellerNumber()
        assertNull(s.sellerNumber)
        assertEquals(7L, s.sellerNumberSequence)
    }

    @Test
    fun `changeDefaultPaymentTerm sets and clears the term`() {
        val s = SenderProfile()
        val term = PaymentTerm()
        s.changeDefaultPaymentTerm(term)
        assertSame(term, s.defaultPaymentTerm)
        s.changeDefaultPaymentTerm(null)
        assertNull(s.defaultPaymentTerm)
    }

    @Test
    fun `enableConsumerNote switches the notice flag`() {
        val s = SenderProfile()
        s.enableConsumerNote(false)
        assertFalse(s.consumerNoteEnabled)
        s.enableConsumerNote(true)
        assertTrue(s.consumerNoteEnabled)
    }
}
