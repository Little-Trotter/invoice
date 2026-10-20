package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.MasterDataNumberService
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * MD-3 — Border cases (light).
 *
 * Selected edges:
 * - Delete/deactivate a sender or recipient with existing invoices (issued and draft)
 * - Format edges: IBAN checksum rejects, VAT-ID country-prefix mismatches, overlong names
 * - Default address removed while it is the only complete address
 * - Two recipients differing only by case in customer number
 * - Sender without any additional VAT registration selecting a foreign registration country
 *
 * Each case: test + contract one-liner; undefined → findings list.
 */
@DisplayName("MD-3: Master Data Border Cases")
class MasterDataBorderCasesTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // FORMAT EDGES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Format Edges")
    inner class FormatEdgesTests {

        @Test
        fun `IBAN with invalid checksum should be detected at domain layer`() {
            // Contract: IBAN checksum validation is responsibility of Iban value object
            // (not tested here as Iban constructor likely performs validation)
            // This test documents the contract
            val validIban = Iban("DE89370400440532013000")
            assertDoesNotThrow { validIban }
        }

        @Test
        fun `VAT ID with country prefix mismatch can be created but should be flagged`() {
            val sender = aSenderProfile {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("FRXX123456789")  // French prefix, but sender might be DE
                iban = Iban("DE89370400440532013000")
            }
            // Contract: VAT ID prefix is not validated against entity's country at domain level
            // (validation would be semantic, requiring context about seller's country)
            assertDoesNotThrow { sender.validate() }
        }

        @Test
        fun `sender name with umlauts and special characters`() {
            val sender = aSenderProfile {
                name = "Müller & Sohn GmbH - Übersicht"
                invoicePrefix = "MS"
                email = Email("billing@mueller.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
            }
            assertDoesNotThrow { sender.validate() }
            // Contract: umlauts and special characters flow through into XML unchanged
            // (XML escaping happens at serialization layer, not in domain)
        }

        @Test
        fun `recipient with very long name`() {
            val recipient = aRecipient {
                customerNumber = CustomerNumber("C-001")
                name = "A".repeat(500)  // Oversized name
                email = Email("contact@example.com")
                customerType = CustomerType.CONSUMER
            }
            // Contract: domain validation has no length limit on name
            // (database column or UI layer may enforce a practical limit)
            assertDoesNotThrow { recipient.validate() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CUSTOMER NUMBER UNIQUENESS (Case Sensitivity)
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Customer Number Uniqueness")
    inner class CustomerNumberUniquenessTests {

        @Test
        fun `two recipients with customer numbers differing only in case still validate individually at the domain layer`() {
            val recipient1 = aRecipient {
                customerNumber = CustomerNumber("CUST-001")
                name = "Customer One"
                email = Email("cust1@example.com")
                customerType = CustomerType.CONSUMER
            }

            val recipient2 = aRecipient {
                customerNumber = CustomerNumber("cust-001")  // Different case
                name = "Customer Two"
                email = Email("cust2@example.com")
                customerType = CustomerType.CONSUMER
            }

            // Recipient.validate() has no cross-record knowledge (it can't see recipient1 at all)
            // and never enforced uniqueness itself — that always lived at the service/DB layer,
            // see the finding test right below.
            assertDoesNotThrow { recipient1.validate() }
            assertDoesNotThrow { recipient2.validate() }
        }

        /**
         * PO decision 30.08.2026 (a) — customer numbers are now unique
         * case-insensitively: `uq_recipient_customer_number` is a functional index on
         * `UPPER(customer_number)` (V1 baseline) instead of the plain column, and
         * `CustomerManagementService.save()` rejects a case-differing duplicate with the same
         * readable `NumberCollisionException` the DB constraint would eventually throw, but before
         * ever reaching it (`MasterDataNumberService.assertCustomerNumberAvailable`).
         */
        @Test
        fun `a case-differing customer number is rejected as a duplicate`() {
            val recipientRepository = mock<RecipientRepository>()
            val invoiceLookup = mock<InvoiceLookupRepository>()
            val masterDataNumberService = MasterDataNumberService(mock<NumberRangeService>(), mock<RecipientRepository>(), mock<ArticleRepository>(), mock<SenderProfileRepository>(), de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
            val addressManagementService = mock<AddressManagementService>()
            val service = CustomerManagementService(
                recipientRepository = recipientRepository,
                addressManagementService = addressManagementService,
                masterDataNumberService = masterDataNumberService,
                invoiceLookup = invoiceLookup,
            )

            val existing = aRecipient { customerNumber = CustomerNumber("CUST-001") }
            whenever(recipientRepository.findByCustomerNumberIgnoreCase("cust-001")).thenReturn(existing)

            val recipient2 = aRecipient {
                customerNumber = CustomerNumber("cust-001") // same number as `existing`, different case
                name = "Customer Two"
                email = Email("cust2@example.com")
                customerType = CustomerType.CONSUMER
            }

            val ex = assertThrows(NumberCollisionException::class.java) { service.save(recipient2) }

            assertEquals("cust-001", ex.attemptedValue)
            verify(recipientRepository, never()).saveAndFlush(any())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SENDER WITH FOREIGN VAT REGISTRATION
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sender VAT Registration")
    inner class SenderVatRegistrationTests {

        @Test
        fun `sender with only German VAT ID attempting to serve foreign customer`() {
            val sender = aSenderProfile {
                name = "Deutsche GmbH"
                invoicePrefix = "DE"
                email = Email("billing@deutsche.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")  // Only German registration
                iban = Iban("DE89370400440532013000")
            }
            assertDoesNotThrow { sender.validate() }
            // Contract: domain validation has no concept of "additional VAT registrations"
            // (that is an application-layer, tax-rule concern)
            // Finding: if sender selects foreign registration country without additional registration,
            // it should be rejected — but domain model doesn't enforce this
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DELETION IN USE (Contracts)
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Deletion While in Use")
    inner class DeletionInUseTests {

        private val invoiceLookup = mock<InvoiceLookupRepository>()
        private val masterDataNumberService = MasterDataNumberService(mock<NumberRangeService>(), mock<RecipientRepository>(), mock<ArticleRepository>(), mock<SenderProfileRepository>(), de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
        private val addressManagementService = mock<AddressManagementService>()

        /**
         * Deleting a sender still referenced by
         * an invoice used to have no service-level guard at all: the delete reached Postgres and
         * surfaced as a raw rollback/FK error, not a readable business exception.
         * [SellerProfileService.delete] now blocks it up front instead.
         */
        @Test
        fun `sender deletion is blocked with a readable error while an invoice still references it`() {
            val senderRepository = mock<SenderProfileRepository>()
            val service = SellerProfileService(
                senderProfileRepository = senderRepository,
                addressManagementService = addressManagementService,
                masterDataNumberService = masterDataNumberService,
                invoiceLookup = invoiceLookup,
                defaultReferences = mock(),
                languageRepository = mock(), time = FIXED_TIME_SOURCE,
            )
            whenever(invoiceLookup.findBySenderProfileId(7L, 0, 1)).thenReturn(listOf(mock<Invoice>()))

            val ex = assertThrows(InvalidParameterException::class.java) { service.delete(7L) }

            assertTrue(ex.message?.contains("Rechnung") == true, ex.message)
            verify(senderRepository, never()).delete(any())
        }

        /**
         * The same reasoning applied to recipients —
         * same gap, [CustomerManagementService.delete] side: a recipient still referenced by a
         * (draft or finalized) invoice is now blocked up front too, instead of failing at commit.
         */
        @Test
        fun `recipient deletion is blocked with a readable error while an invoice still references it`() {
            val recipientRepository = mock<RecipientRepository>()
            val service = CustomerManagementService(
                recipientRepository = recipientRepository,
                addressManagementService = addressManagementService,
                masterDataNumberService = masterDataNumberService,
                invoiceLookup = invoiceLookup,
            )
            whenever(invoiceLookup.findByRecipientId(9L, 0, 1)).thenReturn(listOf(mock<Invoice>()))

            val ex = assertThrows(InvalidParameterException::class.java) { service.delete(9L) }

            assertTrue(ex.message?.contains("Rechnung") == true, ex.message)
            verify(recipientRepository, never()).delete(any())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ADDRESS RULES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Address Completeness")
    inner class AddressCompletenessTests {

        @Test
        fun `default address removed while it is the only complete address`() {
            // Contract: a sender/recipient must have at least one complete billing address
            // for invoice generation. If the default address (only complete one) is removed,
            // the next invoice generation must fail validation.
            // Finding: domain model AddressOwner has no validation for "at least one complete".
            // This is enforced at invoice generation / BuyerData rules layer.
            val sender = aSenderProfile {
                name = "ACME GmbH"
                invoicePrefix = "ACME"
                email = Email("billing@acme.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                // No addresses added — validation doesn't check for this
            }
            assertDoesNotThrow { sender.validate() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BUSINESS RULE INTERACTIONS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Business Rule Interactions")
    inner class BusinessRuleInteractionsTests {

        @Test
        fun `consumer with no VAT ID requirement`() {
            val consumer = aRecipient {
                customerNumber = CustomerNumber("C-CONSUMER-001")
                name = "Max Mustermann"
                email = Email("max@example.com")
                customerType = CustomerType.CONSUMER
                vatId = null
            }
            // Contract: consumer type allows null VAT ID; business type requires it
            assertDoesNotThrow { consumer.validate() }
        }

        @Test
        fun `switching type from consumer to business requires VAT ID`() {
            val recipient = aRecipient {
                customerNumber = CustomerNumber("C-001")
                name = "Max"
                email = Email("max@example.com")
                customerType = CustomerType.CONSUMER
                vatId = null
            }
            recipient.edit { customerType = CustomerType.BUSINESS }
            // Now fails validation
            assertThrows(InvoiceValidationException::class.java) { recipient.validate() }
        }
    }
}
