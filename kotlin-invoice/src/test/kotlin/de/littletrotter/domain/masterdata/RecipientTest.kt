package de.littletrotter.domain.masterdata

import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

class RecipientTest {

    private fun baseRecipient(): Recipient = aRecipient {
        customerNumber = CustomerNumber("KD-1")
        name = "Kunde GmbH"
        email = Email("kunde@example.com")
    }

    // ── P1-T03: a CONSUMER is valid without VAT ID or IBAN ──────────────────

    @Test
    fun `a consumer without VAT ID and without IBAN is valid`() {
        val r = baseRecipient().edit { customerType = CustomerType.CONSUMER }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `a business without VAT ID is rejected`() {
        val r = baseRecipient().edit { customerType = CustomerType.BUSINESS; iban = Iban("DE44500105175407324931") }
        assertThrows(InvoiceValidationException::class.java) { r.validate() }
    }

    // BT-91 (buyer IBAN) is never required: this system bills by credit transfer, where the
    // SELLER's IBAN (BT-84) is the one that must be set, not the buyer's — a buyer IBAN would
    // only matter for SEPA direct debit, which this system does not do.
    @Test
    fun `a business without IBAN is valid`() {
        val r = baseRecipient().edit { customerType = CustomerType.BUSINESS; vatId = VatId("DE123456789") }
        assertDoesNotThrow { r.validate() }
    }

    @Test
    fun `a fully specified business is valid`() {
        val r = baseRecipient().edit {
            customerType = CustomerType.BUSINESS
            vatId = VatId("DE123456789")
            iban = Iban("DE44500105175407324931")
        }
        assertDoesNotThrow { r.validate() }
    }

    // Regression guard: a Recipient built the way pre-P1 code always did (no customerType at
    // all) keeps the old, stricter behavior — P1 must not silently loosen validation for
    // callers that haven't been updated to set customerType yet. (IBAN was dropped from this
    // requirement entirely, for every customerType including null — see the class-level
    // comment on Recipient.validate().)
    @Test
    fun `without a customerType, the old strict VAT ID requirement still applies`() {
        val withoutVat = baseRecipient()
        assertThrows(InvoiceValidationException::class.java) { withoutVat.validate() }

        val complete = baseRecipient().edit {
            vatId = VatId("DE123456789")
        }
        assertDoesNotThrow { complete.validate() }
    }

    // ── These tests pin down the *correct* InvoiceValidationError code per field, not just that
    //    validation fails: a wrong code surfaces as the wrong user-facing message, regardless
    //    of which recipient field is actually invalid. ──

    @Test
    fun `a missing customer number is reported as RECIPIENT_CUSTOMER_NUMBER_BLANK`() {
        // CustomerNumber's own init rejects blank now; a Recipient can only ever hold
        // null (not yet assigned), never a blank, non-null value — see CustomerNumberTest.
        val r = baseRecipient().edit { customerNumber = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { r.validate() }
        assertEquals(InvoiceValidationError.RECIPIENT_CUSTOMER_NUMBER_BLANK, ex.error)
    }

    @Test
    fun `a blank recipient name is reported as RECIPIENT_NAME_BLANK`() {
        val r = baseRecipient().edit { name = "" }
        val ex = assertThrows(InvoiceValidationException::class.java) { r.validate() }
        assertEquals(InvoiceValidationError.RECIPIENT_NAME_BLANK, ex.error)
    }

    @Test
    fun `a missing email is reported as RECIPIENT_EMAIL_MISSING`() {
        val r = baseRecipient().edit { email = null }
        val ex = assertThrows(InvoiceValidationException::class.java) { r.validate() }
        assertEquals(InvoiceValidationError.RECIPIENT_EMAIL_MISSING, ex.error)
    }

    @Test
    fun `a business without a VAT id is reported as RECIPIENT_VAT_ID_MISSING`() {
        val r = baseRecipient().edit { customerType = CustomerType.BUSINESS; iban = Iban("DE44500105175407324931") }
        val ex = assertThrows(InvoiceValidationException::class.java) { r.validate() }
        assertEquals(InvoiceValidationError.RECIPIENT_VAT_ID_MISSING, ex.error)
    }

    // ── P1-T01/T02: identifier value objects require value+schemeId together ──

    @Test
    fun `PartyIdentifier without a scheme is rejected`() {
        assertThrows(InvalidParameterException::class.java) { PartyIdentifier.of("987654321", "") }
    }

    @Test
    fun `PartyIdentifier with a scheme from the codelist is accepted`() {
        val id = PartyIdentifier.of("987654321", "0204")
        assertEquals("987654321", id.value)
        assertEquals("0204", id.schemeId)
    }

    @Test
    fun `ElectronicAddress without a scheme is rejected`() {
        assertThrows(InvalidParameterException::class.java) { ElectronicAddress.of("buyer@example.de", "") }
    }

    // ── E1 (tasks4): BT-49 is not limited to the "EM" email scheme ─────

    @Test
    fun `ElectronicAddress accepts a Peppol participant scheme like 9930, not just EM`() {
        val id = ElectronicAddress.of("DE123456789", "9930")
        assertEquals("DE123456789", id.value)
        assertEquals("9930", id.schemeId)
    }

    // ── PartyIdentifier / ElectronicAddress: a blank value is rejected too, not just a blank scheme ──

    @Test
    fun `PartyIdentifier with a blank value is rejected`() {
        assertThrows(InvalidParameterException::class.java) { PartyIdentifier.of("   ", "0204") }
    }

    @Test
    fun `ElectronicAddress with a blank value is rejected`() {
        assertThrows(InvalidParameterException::class.java) { ElectronicAddress.of("   ", "EM") }
    }

    // ── LegalRegistrationIdentifier (BT-30/BT-47) — same value+schemeId shape as the others ──

    @Test
    fun `LegalRegistrationIdentifier requires both a value and a scheme`() {
        assertThrows(InvalidParameterException::class.java) { LegalRegistrationIdentifier.of("   ", "0002") }
        assertThrows(InvalidParameterException::class.java) { LegalRegistrationIdentifier.of("HRB12345", "") }
    }

    @Test
    fun `a fully specified LegalRegistrationIdentifier is accepted and trims its inputs`() {
        val id = LegalRegistrationIdentifier.of(" HRB12345 ", " 0002 ")
        assertEquals("HRB12345", id.value)
        assertEquals("0002", id.schemeId)
    }

    // ── equals() — the identifier embeddables compare by value+schemeId, not by type alone ──

    @Test
    fun `two PartyIdentifiers with the same value and scheme are equal`() {
        assertEquals(PartyIdentifier.of("987654321", "0204"), PartyIdentifier.of("987654321", "0204"))
    }

    @Test
    fun `PartyIdentifiers differing only in value or only in scheme are not equal`() {
        val base = PartyIdentifier.of("987654321", "0204")
        assertNotEquals(base, PartyIdentifier.of("111111111", "0204"))
        assertNotEquals(base, PartyIdentifier.of("987654321", "9999"))
    }

    @Test
    fun `a PartyIdentifier is never equal to an ElectronicAddress, even with identical value and scheme`() {
        assertNotEquals(PartyIdentifier.of("987654321", "EM") as Any, ElectronicAddress.of("987654321", "EM") as Any)
    }

    @Test
    fun `two ElectronicAddresses with the same value and scheme are equal`() {
        assertEquals(ElectronicAddress.of("buyer@example.de", "EM"), ElectronicAddress.of("buyer@example.de", "EM"))
    }

    @Test
    fun `two LegalRegistrationIdentifiers with the same value and scheme are equal`() {
        assertEquals(LegalRegistrationIdentifier.of("HRB12345", "0002"), LegalRegistrationIdentifier.of("HRB12345", "0002"))
    }

    // These identifiers must **not** compare equal merely by matching value+schemeId -- only
    // within their own type. BT-30 (registration identifier) and BT-49 (electronic address) can
    // carry the same value while meaning different things.

    @Test
    fun `ElectronicAddresses differing only in value or only in scheme are not equal`() {
        val base = ElectronicAddress.of("buyer@example.de", "EM")
        assertNotEquals(base, ElectronicAddress.of("other@example.de", "EM"))
        assertNotEquals(base, ElectronicAddress.of("buyer@example.de", "9930"))
    }

    @Test
    fun `an ElectronicAddress is never equal to a PartyIdentifier with identical value and scheme`() {
        assertNotEquals(ElectronicAddress.of("987654321", "EM") as Any, PartyIdentifier.of("987654321", "EM") as Any)
    }

    @Test
    fun `LegalRegistrationIdentifiers differing only in value or only in scheme are not equal`() {
        val base = LegalRegistrationIdentifier.of("HRB12345", "0002")
        assertNotEquals(base, LegalRegistrationIdentifier.of("HRB99999", "0002"))
        assertNotEquals(base, LegalRegistrationIdentifier.of("HRB12345", "0198"))
    }

    @Test
    fun `a LegalRegistrationIdentifier is never equal to a PartyIdentifier with identical value and scheme`() {
        assertNotEquals(LegalRegistrationIdentifier.of("HRB12345", "0002") as Any, PartyIdentifier.of("HRB12345", "0002") as Any)
    }

    // ── P1-T05: BT-10, BT-46 and BT-49 are three separate fields, no derivation ──

    @Test
    fun `routingId, buyerIdentifier and electronicAddress are independent fields`() {
        val r = baseRecipient().edit {
            routingId = "991-01484-64" // BT-10
            buyerIdentifier = PartyIdentifier.of("987654321", "0204") // BT-46
            electronicAddress = ElectronicAddress.of("buyer@example.de", "EM") // BT-49
        }
        assertEquals("991-01484-64", r.routingId)
        assertEquals("987654321", r.buyerIdentifier?.value)
        assertEquals("buyer@example.de", r.electronicAddress?.value)
        // setting one must never have derived/overwritten another
        assertEquals("991-01484-64", r.routingId)
    }

    // ── globalId set doesn't auto-populate BT-46 ─────────────────────

    @Test
    fun `globalId set does not implicitly populate buyerIdentifier`() {
        val r = baseRecipient().edit { globalId = "some-legacy-value" }
        assertNull(r.buyerIdentifier)
    }

    // ── ensureShortHash() — a short hash is assigned at most once ────────────

    @Test
    fun `ensureShortHash assigns a hash and reports true when none existed yet`() {
        val r = baseRecipient().edit { shortHash = null }
        assertTrue(r.ensureShortHash())
        assertNotNull(r.shortHash)
    }

    @Test
    fun `ensureShortHash leaves an existing hash untouched and reports false`() {
        val r = baseRecipient().edit { shortHash = "ABCDEFGHJK" }
        assertFalse(r.ensureShortHash())
        assertEquals("ABCDEFGHJK", r.shortHash)
    }

    // ── equals()/hashCode() — persisted identity, not field-by-field comparison ──
    // Hand-written JPA identity equality (not a data class): two transient (unsaved)
    // recipients must never compare equal by accident, and two rows loaded for the same
    // persisted id must, regardless of which fields differ in memory.

    @Test
    fun `a recipient is equal to itself`() {
        val r = baseRecipient()
        assertEquals(r, r)
    }

    @Test
    fun `a recipient is never equal to an unrelated type`() {
        assertNotEquals(baseRecipient() as Any, "not a recipient" as Any)
    }

    @Test
    fun `two persisted recipients with the same id are equal even if other fields differ`() {
        val a = baseRecipient().edit { id = 42L }
        val b = aRecipient { id = 42L; customerNumber = CustomerNumber("OTHER"); name = "Different" }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two persisted recipients with different ids are not equal`() {
        val a = baseRecipient().edit { id = 1L }
        val b = baseRecipient().edit { id = 2L }
        assertNotEquals(a, b)
    }

    @Test
    fun `two transient (unsaved) recipients are never equal, even with identical fields`() {
        assertNotEquals(baseRecipient(), baseRecipient())
    }

    @Test
    fun `a persisted and a transient recipient are never equal`() {
        val persisted = baseRecipient().edit { id = 1L }
        val transient = baseRecipient()
        assertNotEquals(persisted, transient)
    }

    /**
     * `hashCode()` must not derive from `id`: an entity's hash code has to stay stable across
     * the moment it is first persisted and assigned an id, or it becomes unfindable in any
     * hash-based collection it was already placed in.
     */
    @Test
    fun `der hashCode leitet sich nicht aus der id ab`() {
        val eins = baseRecipient().edit { id = 1L }
        val zwei = baseRecipient().edit { id = 999L }

        assertNotEquals(eins, zwei, "verschiedene ids bleiben verschiedene Entitaeten")
        assertEquals(eins.hashCode(), zwei.hashCode(), "der hashCode traegt die id nicht")
    }

    @Test
    fun `der hashCode bleibt stabil, wenn eine transiente Entitaet ihre id bekommt`() {
        val r = baseRecipient()
        val menge = hashSetOf(r)
        val hashVorher = r.hashCode()

        r.edit { this.id = 7L } // saving assigns the id

        assertEquals(hashVorher, r.hashCode(), "der hashCode darf sich durch die id-Vergabe nicht aendern")
        assertTrue(r in menge, "die Entitaet muss in der Menge auffindbar bleiben, in der sie liegt")
    }

    @Test
    fun `happy path - createDefaultBillingAddress builds an empty default billing address owned by this recipient`() {
        val r = baseRecipient()

        val address = r.createDefaultBillingAddress()

        assertEquals(Address.AddressType.BILLING, address.addressType)
        assertTrue(address.isDefault)
        assertTrue(address.isOwnedByRecipient(r))
    }

    @Test
    fun `boundary - toString reports id, name and customer number for logging without leaking other fields`() {
        val r = baseRecipient()
        r.edit { this.id = 9L }

        val text = r.toString()

        assertTrue(text.contains("9"))
        assertTrue(text.contains("Kunde GmbH"))
        assertTrue(text.contains("KD-1"))
    }

    @Test
    fun `boundary - ownerLabel identifies a recipient-owned address as belonging to the recipient`() {
        val r = baseRecipient()

        assertEquals("recipient", r.ownerLabel())
    }

    @Test
    fun `boundary - customerNumberPrefix and customerNumberSequence are settable reporting columns`() {
        val r = baseRecipient()

        r.edit {
            customerNumberPrefix = "KD"
            customerNumberSequence = 42L
        }

        assertEquals("KD", r.customerNumberPrefix)
        assertEquals(42L, r.customerNumberSequence)
    }

    // ── intent methods ────────────────────────────────────────────────────

    @Test
    fun `update writes every edited field`() {
        val r = Recipient()
        r.update(
            RecipientDetails(
                name = "Kunde GmbH",
                customerNumber = CustomerNumber("KD-1"),
                vatId = VatId("DE999999999"),
                vatIdVerificationNote = "per Brief",
                email = Email("k@example.invalid"),
                customerType = CustomerType.BUSINESS,
                documentLanguage = "en",
            ),
        )
        assertEquals("Kunde GmbH", r.name)
        assertEquals(CustomerNumber("KD-1"), r.customerNumber)
        assertEquals("per Brief", r.vatIdVerificationNote)
        assertEquals(CustomerType.BUSINESS, r.customerType)
        assertEquals("en", r.documentLanguage)
        assertNull(r.id)
    }

    @Test
    fun `recordVatIdVerification stores status and timestamp`() {
        val r = Recipient()
        val at = java.time.OffsetDateTime.parse("2026-03-14T10:00:00Z")
        r.recordVatIdVerification(de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED, at)
        assertEquals(de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED, r.vatIdVerificationStatus)
        assertEquals(at, r.vatIdVerifiedAt)
        r.recordVatIdVerification(de.littletrotter.shared.tax.VatIdVerificationStatus.UNVERIFIED, null)
        assertNull(r.vatIdVerifiedAt)
    }

    @Test
    fun `assignCustomerNumber stores number prefix and sequence and clearCustomerNumber drops only the number`() {
        val r = Recipient()
        r.assignCustomerNumber("KD", 42L, CustomerNumber("KD-42"))
        assertEquals("KD", r.customerNumberPrefix)
        assertEquals(42L, r.customerNumberSequence)
        assertEquals(CustomerNumber("KD-42"), r.customerNumber)
        r.clearCustomerNumber()
        assertNull(r.customerNumber)
        assertEquals(42L, r.customerNumberSequence)
    }

    @Test
    fun `changeElectronicAddress sets and clears the address`() {
        val r = Recipient()
        val address = ElectronicAddress.of("k@example.invalid", "EM")
        r.changeElectronicAddress(address)
        assertEquals(address, r.electronicAddress)
        r.changeElectronicAddress(null)
        assertNull(r.electronicAddress)
    }
}
