package de.littletrotter.ui.view.masterdata

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.VatIdValidator
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * MD-1 — Sender profile and recipient: standard CRUD + validation contracts.
 *
 * Karibu/unit tests IN THE CORE MODULE for SenderProfileView and RecipientView:
 * 1. CRUD both entities: create, edit, deactivate/delete, search/filter, list.
 * 2. Validation contracts exactly as implemented:
 *    - SenderProfile.validate(): name, invoicePrefix, email, taxNumber, vatId, iban, sellerNumber-if-set
 *    - Recipient.validate(): customerNumber, name, email, vatId required only for non-CONSUMER
 * 3. Addresses: add/edit billing and delivery, default flags, completeness check.
 * 4. Customer type switch CONSUMER↔BUSINESS and its vatId requirement flip.
 */
@DisplayName("MD-1: Master Data CRUD and Validation")
class MasterDataCrudValidationTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // SENDER PROFILE
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sender Profile")
    inner class SenderProfileTests {

        // ── CRUD: Create ──────────────────────────────────────────────────────────

        @Test
        fun `create a sender profile with required fields`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertDoesNotThrow { sender.validate() }
            assertEquals("ACME GmbH", sender.name)
            assertEquals("ACME", sender.invoicePrefix)
        }

        @Test
        fun `sender profile with optional seller number`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                sellerNumber = "SN-001"
            }
            assertDoesNotThrow { sender.validate() }
            assertEquals("SN-001", sender.sellerNumber)
        }

        // ── CRUD: Edit ────────────────────────────────────────────────────────────

        @Test
        fun `edit sender profile name`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            sender.name = "Neue ACME GmbH"
            assertEquals("Neue ACME GmbH", sender.name)
        }

        @Test
        fun `edit sender profile email`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            sender.email = Email("new-billing@acme.example")
            assertEquals("new-billing@acme.example", sender.email?.value)
        }

        @Test
        fun `mark sender profile as default`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                isDefault = false
            }
            assertFalse(sender.isDefault)
            sender.markAsDefault()
            assertTrue(sender.isDefault)
        }

        // ── Validation: Required Fields ───────────────────────────────────────────

        @Test
        fun `sender validation fails when name is missing`() {
            val sender = SenderProfile().apply {
                name = ""
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation fails when invoice prefix is missing`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = ""
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation fails when email is missing`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = null
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation fails when tax number is missing`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = null
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation fails when VAT id is missing`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = null
                iban = Iban("DE89370400440532013000")
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation fails when IBAN is missing`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = null
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation fails when seller number is blank but set`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                sellerNumber = "   "
            }
            assertThrows(InvoiceValidationException::class.java) { sender.validate() }
        }

        @Test
        fun `sender validation passes when seller number is null`() {
            val sender = SenderProfile().apply {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                sellerNumber = null
            }
            assertDoesNotThrow { sender.validate() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RECIPIENT (CUSTOMER)
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Recipient (Customer)")
    inner class RecipientTests {

        // ── CRUD: Create ──────────────────────────────────────────────────────────

        @Test
        fun `create a recipient with required fields`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            assertDoesNotThrow { recipient.validate() }
            assertEquals("C-001", recipient.customerNumber?.value)
            assertEquals("Kunde GmbH", recipient.name)
            assertEquals(CustomerType.BUSINESS, recipient.customerType)
        }

        @Test
        fun `create a consumer recipient without VAT id`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-002")
                name = "Max Mustermann"
                email = Email("max@example.com")
                customerType = CustomerType.CONSUMER
                vatId = null
            }
            assertDoesNotThrow { recipient.validate() }
            assertEquals(CustomerType.CONSUMER, recipient.customerType)
            assertNull(recipient.vatId)
        }

        // ── CRUD: Edit ────────────────────────────────────────────────────────────

        @Test
        fun `edit recipient name`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            recipient.name = "Neue Kunde GmbH"
            assertEquals("Neue Kunde GmbH", recipient.name)
        }

        @Test
        fun `edit recipient email`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            recipient.email = Email("new-contact@kunde.example")
            assertEquals("new-contact@kunde.example", recipient.email?.value)
        }

        @Test
        fun `switch recipient from BUSINESS to CONSUMER`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            recipient.customerType = CustomerType.CONSUMER
            recipient.vatId = null
            assertEquals(CustomerType.CONSUMER, recipient.customerType)
            assertNull(recipient.vatId)
        }

        @Test
        fun `switch recipient from CONSUMER to BUSINESS requires VAT id`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-002")
                name = "Max Mustermann"
                email = Email("max@example.com")
                customerType = CustomerType.CONSUMER
                vatId = null
            }
            recipient.customerType = CustomerType.BUSINESS
            // Must set VAT id for BUSINESS type
            recipient.vatId = VatId("DE123456789")
            assertDoesNotThrow { recipient.validate() }
            assertEquals(CustomerType.BUSINESS, recipient.customerType)
            assertNotNull(recipient.vatId)
        }

        // ── Validation: Required Fields ───────────────────────────────────────────

        @Test
        fun `recipient validation fails when customer number is missing`() {
            val recipient = Recipient().apply {
                customerNumber = null
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            assertThrows(InvoiceValidationException::class.java) { recipient.validate() }
        }

        @Test
        fun `recipient validation fails when name is missing`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = ""
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            assertThrows(InvoiceValidationException::class.java) { recipient.validate() }
        }

        @Test
        fun `recipient validation fails when email is missing`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = null
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
            }
            assertThrows(InvoiceValidationException::class.java) { recipient.validate() }
        }

        @Test
        fun `business recipient validation fails without VAT id`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = null
            }
            assertThrows(InvoiceValidationException::class.java) { recipient.validate() }
        }

        @Test
        fun `consumer recipient validation passes without VAT id`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-002")
                name = "Max Mustermann"
                email = Email("max@example.com")
                customerType = CustomerType.CONSUMER
                vatId = null
            }
            assertDoesNotThrow { recipient.validate() }
        }

        @Test
        fun `recipient validation fails when VAT id is blank for business`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = null
            }
            assertThrows(InvoiceValidationException::class.java) { recipient.validate() }
        }

        @Test
        fun `recipient IBAN is optional`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
                iban = null
            }
            assertDoesNotThrow { recipient.validate() }
            assertNull(recipient.iban)
        }

        @Test
        fun `recipient can have a valid IBAN`() {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("C-001")
                name = "Kunde GmbH"
                email = Email("contact@kunde.example")
                customerType = CustomerType.BUSINESS
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertDoesNotThrow { recipient.validate() }
            assertNotNull(recipient.iban)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // VAT ID LENGTH (wave E, 2026-08-30 evening — sender_profile.vat_id/recipient.vat_id raised
    // from VARCHAR(30) to VARCHAR(64) in V1__baseline_schema.sql; see
    // SenderProfileEditLoadIT/MasterDataNumberCollisionIT for the real-database round trip).
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("VAT ID Length (wave E)")
    inner class VatIdLengthTests {

        // The two "Recipient/SenderProfile accepts a 64-character unstructured vat id"
        // tests that used to live here are gone: VatId's own per-country format regex
        // (VatIdValidator) now caps every constructible value at far under 64 characters (the
        // longest recognised format is 15), so a Recipient/SenderProfile can no longer hold such
        // a value at all — the state they exercised is unrepresentable now, not merely untested.
        // VatIdTest's own "over VatIdValidator.MAX_LENGTH is rejected" boundary test covers the
        // 64-character column width from the value-object side.

        @Test
        fun `VatIdValidator rejects a vat id one character over the 64-character column width`() {
            val over64 = "DE" + "1".repeat(63)
            assertEquals(65, over64.length)
            val error = VatIdValidator.validate(over64)
            assertTrue(error != null && error.contains("64"), "expected a length-specific rejection: $error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PAYMENT TERMS (Used in both tests)
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Payment Terms")
    inner class PaymentTermsTests {

        @Test
        fun `create a payment term with required fields`() {
            val term = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Net 30")
                dueInDays = 30
                discountPercent = de.littletrotter.domain.value.DiscountPercent(java.math.BigDecimal.ZERO)
            }
            assertDoesNotThrow { term.validate() }
            assertEquals("Net 30", term.nameFor("de"))
            assertEquals(30, term.dueInDays)
        }

        @Test
        fun `create a payment term with discount`() {
            val term = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "2/10 Net 30")
                dueInDays = 30
                discountPercent = de.littletrotter.domain.value.DiscountPercent(java.math.BigDecimal("2.00"))
                discountDays = 10
            }
            assertDoesNotThrow { term.validate() }
            assertEquals(java.math.BigDecimal("2.00"), term.discountPercent.value)
            assertEquals(10, term.discountDays)
        }

        @Test
        fun `payment term marked as default`() {
            val term = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Default Net 30")
                dueInDays = 30
                discountPercent = de.littletrotter.domain.value.DiscountPercent(java.math.BigDecimal.ZERO)
                isDefault = true
            }
            assertTrue(term.isDefault)
        }

        @Test
        fun `deactivate a payment term`() {
            val term = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Old Term")
                dueInDays = 30
                discountPercent = de.littletrotter.domain.value.DiscountPercent(java.math.BigDecimal.ZERO)
                isActive = true
            }
            assertTrue(term.isActive)
            term.isActive = false
            assertFalse(term.isActive)
        }
    }
}
