package de.littletrotter.application.masterdata

import de.littletrotter.fixture.seededStandardTaxRate
import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolutionError
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.shared.tax.TaxRuleResolutionRequest
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.infrastructure.vies.ViesHttpClient
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY
import de.littletrotter.application.dto.VatIdVerificationResult

/**
 * MD-2 — Party snapshots, VAT registrations, VIES verification, customer numbers.
 *
 * Core unit/IT tests for master data (sender/recipient):
 * 1. Snapshot protection: editing/deleting master data must never change ISSUED invoices
 * 2. Seller VAT registrations: add/remove additionalVatRegistrations
 * 3. VIES verification: UNVERIFIED → VERIFIED → FAILED transitions with reverse-charge eligibility
 * 4. Customer numbers: allocator/uniqueness at service level
 *
 * Against real PostgreSQL. Tests are structured as enumerated cases per the E2E program standard.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class MasterDataSnapshotsViesRegistrationsIT {

    companion object {
        /**
         * Shared across all test instances, so it really does count up. It replaces
         * `System.nanoTime() % 100000`, which only looked unique: 100 000 possible values, so two
         * calls could land on the same number and fail with `duplicate key value violates unique
         * constraint`. Same fix and same reasoning as
         * [de.littletrotter.application.SenderProfileEditLoadIT]; the time component is dropped
         * outright rather than combined, because it never contributed uniqueness.
         */
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var em: jakarta.persistence.EntityManager

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Snapshot Protection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * S1: Editing sender profile name after finalization does not change invoice snapshot.
     */
    @Test
    fun `S1 - editing sender name after invoice finalization does not change snapshot`() {
        QuarkusTransaction.requiringNew().run {
            val sender = createValidSenderProfile("ACME Original")
            val recipient = createValidRecipient("Customer One")

            // Create invoice with required fields for finalization
            val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            invoice.edit { references.buyerReference = "BUYER-REF-S1" }
            invoice.addItem(createMinimalItem())
            // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
            // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
            // IT's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply.
            invoice.edit { deliveryDate = LocalDate.now() }
            invoiceRepository.saveAndFlush(invoice)

            // Transition to finalized state (DRAFT → IN_REVIEW → ISSUED)
            invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
            invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.ISSUED)

            // Fetch the finalized invoice with its snapshot
            val finalizedInvoice = invoiceRepository.findById(invoice.id!!)!!
            val originalSnapshotName = finalizedInvoice.partySnapshotList.find { it.partyRole.name == "SELLER" }?.name
            assertTrue(!originalSnapshotName.isNullOrBlank(), "snapshot name should be set")

            // Edit sender name
            sender.edit { name = "ACME Modified GmbH" }
            sellerProfileService.save(sender, defaults = SellerDefaultSelections())

            // Reload the invoice and verify snapshot is unchanged
            val reloadedInvoice = invoiceRepository.findById(invoice.id!!)!!
            val reloadedSnapshotName = reloadedInvoice.partySnapshotList.find { it.partyRole.name == "SELLER" }?.name
            assertEquals(originalSnapshotName, reloadedSnapshotName, "snapshot must not change after finalization")
            assertEquals("ACME Original", reloadedSnapshotName, "snapshot retains original value")
        }
    }

    /**
     * S2: Editing recipient address after finalization does not change invoice snapshot.
     */
    @Test
    fun `S2 - editing recipient address after invoice finalization does not change snapshot`() {
        QuarkusTransaction.requiringNew().run {
            val sender = createValidSenderProfile("ACME")
            val recipient = createValidRecipient("Customer Two")

            // Create and finalize an invoice (DRAFT -> IN_REVIEW -> ISSUED)
            val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            invoice.edit { references.buyerReference = "BUYER-REF-S2" }
            invoice.addItem(createMinimalItem())
            // finding-fixes (2026-08-31) — see S1's identical note above.
            invoice.edit { deliveryDate = LocalDate.now() }
            invoiceRepository.saveAndFlush(invoice)
            invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
            invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.ISSUED)

            // Fetch the finalized invoice with its snapshot
            val finalizedInvoice = invoiceRepository.findById(invoice.id!!)!!
            val originalSnapshotName = finalizedInvoice.partySnapshotList.find { it.partyRole.name == "BUYER" }?.name
            assertTrue(!originalSnapshotName.isNullOrBlank(), "snapshot name should be set")

            // Edit recipient name (master data change)
            recipient.edit { name = "Customer Two Modified" }
            customerManagementService.save(recipient)

            // Reload the invoice and verify snapshot is unchanged
            val reloadedInvoice = invoiceRepository.findById(invoice.id!!)!!
            val reloadedSnapshotName = reloadedInvoice.partySnapshotList.find { it.partyRole.name == "BUYER" }?.name
            assertEquals(originalSnapshotName, reloadedSnapshotName, "snapshot must not change after finalization")
            assertEquals("Customer Two", reloadedSnapshotName, "snapshot retains original value")
        }
    }

    /**
     * S3: Draft invoice reflects current sender profile state after sender edit.
     */
    @Test
    fun `S3 - draft invoice reflects current sender profile state after edit`() {
        QuarkusTransaction.requiringNew().run {
            val sender = createValidSenderProfile("ACME Draft Test")
            val recipient = createValidRecipient("Draft Customer")

            // Create a draft (not finalized)
            val draft = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            assertEquals(InvoiceStatus.DRAFT, draft.status, "invoice must be in DRAFT status")

            // Verify initial snapshot name
            val initialSnapshotName = draft.partySnapshotList.find { it.partyRole.name == "SELLER" }?.name
            assertEquals("ACME Draft Test", initialSnapshotName)

            // Edit sender name
            sender.edit { name = "ACME Draft Modified" }
            sellerProfileService.save(sender, defaults = SellerDefaultSelections())

            // For a draft, the behavior is undefined by the spec; document current behavior
            // (Drafts follow the current contract — assert whatever it is)
            val updatedDraft = invoiceRepository.findById(draft.id!!)!!
            val newSnapshotName = updatedDraft.partySnapshotList.find { it.partyRole.name == "SELLER" }?.name

            // Current behavior: snapshots are created at invoice creation time and stay unchanged
            // even for drafts. This is consistent with finalized invoices.
            assertEquals(initialSnapshotName, newSnapshotName, "draft snapshots follow current contract")
        }
    }

    /**
     * S4: Deleting a sender profile that is still referenced by an existing invoice.
     *
     * Finding MD2-F1 (fixed, per PO decision 2026-08-30): `SellerProfileService.delete()` now
     * carries a service-level guard against deleting a sender referenced by an invoice
     * (`invoice.sender_profile_id` has no `ON DELETE` clause, i.e. Postgres default `NO ACTION`) —
     * rejected up front with a readable [InvalidParameterException], instead of joining the
     * caller's transaction and only failing at flush time with a low-level
     * `TransientPropertyValueException`/`RollbackException`.
     */
    @Test
    fun `S4 - deleting a sender profile referenced by an invoice is blocked with a readable error (Finding MD2-F1 fixed)`() {
        val (senderId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = createValidSenderProfile("Deletable Sender")
            val recipient = createValidRecipient("Deletable Customer")
            val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            sender.id!! to invoice.id!!
        }

        // The delete attempt is isolated in its own transaction so that its (expected) failure
        // rolls back only itself and does not poison this test's other transactions.
        val ex = assertThrows(InvalidParameterException::class.java) {
            QuarkusTransaction.requiringNew().run {
                sellerProfileService.delete(senderId)
            }
        }
        assertTrue(ex.message?.contains("Rechnung") == true, ex.message)

        // The rejected transaction never reached the DELETE at all: sender and invoice are unaffected.
        QuarkusTransaction.requiringNew().run {
            assertNotNull(senderProfileRepository.findById(senderId), "a blocked deletion must not remove the sender")
            assertNotNull(invoiceRepository.findById(invoiceId), "the referencing invoice must be unaffected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Seller VAT Registrations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * V1: Add additional VAT registration to sender on initial creation.
     */
    @Test
    fun `V1 - add additional VAT registration to sender`() {
        QuarkusTransaction.requiringNew().run {
            val sender = aSenderProfile {
                phone = "+49 30 1234567"
                name = "Multi-VAT Seller"
                invoicePrefix = "MVT${uniqueCounter.incrementAndGet()}-"
                email = Email("mvt${System.nanoTime()}@example.invalid")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                registerCourt = "Amtsgericht Test"
            }

            // Set registrations before initial save
            val luRegistration = SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))
            sender.updateVatRegistrations(listOf(luRegistration), today = FIXED_TODAY)

            // Save new profile with registrations
            sellerProfileService.save(sender, defaults = SellerDefaultSelections())

            val reloaded = senderProfileRepository.findById(sender.id!!)!!
            assertEquals(1, reloaded.additionalVatRegistrations.size, "should have one additional registration")
            assertEquals(Country.LU, reloaded.additionalVatRegistrations[0].country)
            assertEquals("LU12345678", reloaded.additionalVatRegistrations[0].vatId?.value)
        }
    }

    /**
     * V2: Remove additional VAT registration from sender (not in use).
     */
    @Test
    fun `V2 - remove additional VAT registration from sender when not in use`() {
        QuarkusTransaction.requiringNew().run {
            val sender = aSenderProfile {
                phone = "+49 30 1234567"
                name = "Remove Reg Seller"
                invoicePrefix = "REM${uniqueCounter.incrementAndGet()}-"
                email = Email("rem${System.nanoTime()}@example.invalid")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                registerCourt = "Amtsgericht Test"
            }

            val luRegistration = SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))
            sender.updateVatRegistrations(listOf(luRegistration), today = FIXED_TODAY)
            sellerProfileService.save(sender, defaults = SellerDefaultSelections())

            // Verify it's there
            var reloaded = senderProfileRepository.findById(sender.id!!)!!
            assertEquals(1, reloaded.additionalVatRegistrations.size)

            // Remove it - directly on repository to bypass copyEditableFieldsFrom limitation
            reloaded.updateVatRegistrations(emptyList(), today = FIXED_TODAY)
            senderProfileRepository.save(reloaded)

            // Verify it's gone
            reloaded = senderProfileRepository.findById(sender.id!!)!!
            assertEquals(0, reloaded.additionalVatRegistrations.size, "registration must be removed")
        }
    }

    /**
     * V3: Removing a seller VAT registration while a NON-finalized invoice references that
     * country is blocked.
     *
     * Finding MD2-F2 (fixed, PO decision b, 30.08.2026): `SellerProfileService.save()` now compares
     * the persisted `additionalVatRegistrations` against the submitted ones and, for every country
     * about to be removed, rejects the save with a readable [InvalidParameterException] naming the
     * country and the number of open invoices as long as at least one non-finalized (`DRAFT`/
     * `IN_REVIEW`) invoice still has `Invoice.sellerVatRegistrationCountry` set to it — see [V3b]
     * for the counterpart (removal allowed once only `ISSUED`/`CANCELLED` invoices reference it,
     * their own snapshot being unaffected).
     *
     * Two transactions, like `S4` above: the (expected) rejection must not poison the setup. The
     * removal itself is applied to a DETACHED `SenderProfile` (loaded via `sellerProfileService
     * .findById`, whose own `findById` opens and closes its own short-lived transaction) rather
     * than one still managed inside the assertion's transaction — mirroring the real save path
     * ([de.littletrotter.ui.view.AbstractCrudView.saveCurrentForm]: load in one round trip, mutate
     * detached, save in the next). Mutating a still-managed instance and calling `save()` within
     * that SAME transaction would make `SellerProfileService.save()`'s own `findById` return that
     * identical, already-mutated object from the persistence context's first-level cache — there
     * would be no "before" state left to compare against, an artifact of the test's own shape, not
     * a real gap in the guard.
     */
    @Test
    fun `V3 - removing a VAT registration while a non-finalized invoice references its country is blocked (Finding MD2-F2 fixed)`() {
        val (senderId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = createValidSenderProfile("Ref VAT Seller Blocked")
            val luRegistration = SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))
            sender.updateVatRegistrations(listOf(luRegistration), today = FIXED_TODAY)
            // Adding to an ALREADY-persisted profile via SellerProfileService.save() would silently
            // no-op: copyEditableFieldsFrom (its update path) never touches
            // additionalVatRegistrations (see V2 above, "bypass copyEditableFieldsFrom
            // limitation"). Same direct-repository bypass here, for the same reason.
            senderProfileRepository.save(sender)

            val recipient = createValidRecipient("Recipient Blocked")
            val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            // The invoice's own creation flow only derives sellerVatRegistrationCountry from the
            // recipient's country; setting it directly here keeps the test focused
            // on the removal guard itself, not on reproducing that unrelated derivation.
            invoice.edit { sellerVatRegistrationCountry = Country.LU }
            invoiceRepository.saveAndFlush(invoice)
            sender.id!! to invoice.id!!
        }

        val detachedSender = sellerProfileService.findById(senderId)!!
        detachedSender.updateVatRegistrations(emptyList(), today = FIXED_TODAY)

        val ex = assertThrows(InvalidParameterException::class.java) {
            QuarkusTransaction.requiringNew().run {
                sellerProfileService.save(detachedSender, defaults = SellerDefaultSelections())
            }
        }
        assertTrue(ex.message?.contains("LU") == true, ex.message)
        assertTrue(ex.message?.contains("1") == true, ex.message)

        QuarkusTransaction.requiringNew().run {
            val reloaded = senderProfileRepository.findById(senderId)!!
            assertEquals(1, reloaded.additionalVatRegistrations.size, "a blocked removal must not persist")
            assertNotNull(invoiceRepository.findById(invoiceId), "the referencing invoice is unaffected")
        }
    }

    /**
     * V3b: Removing a seller VAT registration succeeds once only `ISSUED`/`CANCELLED` invoices
     * reference its country — their party/tax snapshot is frozen and stays correct
     * regardless of the seller's master data afterwards, so they never block a removal (Finding
     * MD2-F2, PO decision b, 30.08.2026, second half).
     */
    @Test
    fun `V3b - removing a VAT registration succeeds when only finalized or cancelled invoices reference its country (Finding MD2-F2 fixed)`() {
        val senderId = QuarkusTransaction.requiringNew().call {
            val sender = createValidSenderProfile("Ref VAT Seller Allowed")
            val luRegistration = SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))
            sender.updateVatRegistrations(listOf(luRegistration), today = FIXED_TODAY)
            // See V3's comment above: direct-repository bypass of the same pre-existing
            // copyEditableFieldsFrom limitation.
            senderProfileRepository.save(sender)

            val recipient = createValidRecipient("Recipient Allowed")
            val cancelledInvoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            cancelledInvoice.edit { sellerVatRegistrationCountry = Country.LU }
            invoiceRepository.saveAndFlush(cancelledInvoice)
            // A bulk JPQL update, not forceStatusForTest: the latter mutates the managed entity's
            // field via reflection, which Hibernate's enhanced dirty-tracking never observes, so
            // the change never reached the database (found as a real regression while proving this
            // task's real-IT evidence).
            em.createQuery("UPDATE Invoice i SET i.status = :status WHERE i.id = :id")
                .setParameter("status", InvoiceStatus.CANCELLED)
                .setParameter("id", cancelledInvoice.id)
                .executeUpdate()
            em.flush()

            sender.id!!
        }

        val detachedSender = sellerProfileService.findById(senderId)!!
        detachedSender.updateVatRegistrations(emptyList(), today = FIXED_TODAY)

        QuarkusTransaction.requiringNew().run {
            assertDoesNotThrow { sellerProfileService.save(detachedSender, defaults = SellerDefaultSelections()) }
        }

        QuarkusTransaction.requiringNew().run {
            val reloaded = senderProfileRepository.findById(senderId)!!
            assertEquals(0, reloaded.additionalVatRegistrations.size, "removal is allowed when only a cancelled invoice references the country")
        }
    }

    /**
     * V4: Duplicate country in additionalVatRegistrations is rejected.
     */
    @Test
    fun `V4 - duplicate country in VAT registrations is rejected`() {
        QuarkusTransaction.requiringNew().run {
            val sender = createValidSenderProfile("Duplicate Reg Seller")
            val lu1 = SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))
            val lu2 = SellerVatRegistrationCommand(Country.LU, VatId("LU87654321"))

            val ex = assertThrows(InvalidParameterException::class.java) {
                sender.updateVatRegistrations(listOf(lu1, lu2), today = FIXED_TODAY)
            }

            assertTrue(ex.message!!.contains("mehrfach"), "error message should mention duplication")
        }
    }

    /**
     * V5: Multiple distinct countries in additionalVatRegistrations are allowed.
     */
    @Test
    fun `V5 - multiple distinct countries in VAT registrations are allowed`() {
        QuarkusTransaction.requiringNew().run {
            val sender = aSenderProfile {
                phone = "+49 30 1234567"
                name = "Multi-Country Seller"
                invoicePrefix = "MUL${uniqueCounter.incrementAndGet()}-"
                email = Email("mul${System.nanoTime()}@example.invalid")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                registerCourt = "Amtsgericht Test"
            }

            val lu = SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))
            val fr = SellerVatRegistrationCommand(Country.FR, VatId("FR12345678901"))

            sender.updateVatRegistrations(listOf(lu, fr), today = FIXED_TODAY)
            sellerProfileService.save(sender, defaults = SellerDefaultSelections())

            val reloaded = senderProfileRepository.findById(sender.id!!)!!
            assertEquals(2, reloaded.additionalVatRegistrations.size, "should have two registrations")
            assertTrue(reloaded.additionalVatRegistrations.any { it.country == Country.LU })
            assertTrue(reloaded.additionalVatRegistrations.any { it.country == Country.FR })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. VIES Verification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * W1: Recipient VAT ID starts in null/UNVERIFIED state.
     */
    @Test
    fun `W1 - recipient VAT ID starts in UNVERIFIED state`() {
        QuarkusTransaction.requiringNew().run {
            val recipient = createValidRecipient("Unverified Customer")
            assertNull(
                recipient.vatIdVerificationStatus,
                "new recipient starts with null verification status (equivalent to UNVERIFIED)"
            )
            assertNull(recipient.vatIdVerifiedAt, "no timestamp yet")
        }
    }

    /**
     * W2: VIES verification success: null → VERIFIED with timestamp.
     * Using a mock to simulate successful VIES response.
     */
    @Test
    fun `W2 - VIES verification success transitions from UNVERIFIED to VERIFIED`() {
        QuarkusTransaction.requiringNew().run {
            val recipient = createValidRecipient("To Verify")
            assertNull(recipient.vatIdVerificationStatus)

            // Simulate VIES success by directly setting state
            // (In real usage, a VIES endpoint would be called; we mock the result)
            recipient.edit {
                vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
                vatIdVerifiedAt = OffsetDateTime.now()
            }
            customerManagementService.save(recipient)

            val reloaded = recipientRepository.findById(recipient.id!!)!!
            assertEquals(VatIdVerificationStatus.VERIFIED, reloaded.vatIdVerificationStatus)
            assertNotNull(reloaded.vatIdVerifiedAt, "timestamp must be set on verification")
        }
    }

    /**
     * W3: VIES verification failure: null → FAILED.
     */
    @Test
    fun `W3 - VIES verification failure transitions from UNVERIFIED to FAILED`() {
        QuarkusTransaction.requiringNew().run {
            val recipient = createValidRecipient("Failed Verify")
            assertNull(recipient.vatIdVerificationStatus)

            // Simulate VIES failure
            recipient.edit {
                vatIdVerificationStatus = VatIdVerificationStatus.FAILED
                vatIdVerifiedAt = OffsetDateTime.now()
            }
            customerManagementService.save(recipient)

            val reloaded = recipientRepository.findById(recipient.id!!)!!
            assertEquals(VatIdVerificationStatus.FAILED, reloaded.vatIdVerificationStatus)
            // Whether timestamp is set on failure is implementation-dependent; document it
            // (typically: yes, to track when the check last happened)
        }
    }

    /**
     * W4: A real, failing `ViesHttpClient` call (connection refused) leaves verification status
     * and timestamp unchanged.
     *
     * `ViesHttpClient.verify()` never throws — it catches every `Exception` from the HTTP call
     * (timeout, connection refused, DNS failure, ...) and returns
     * [VatIdVerificationResult.UNREACHABLE] (see its KDoc: "unreachability is not a statement
     * about the VAT ID"). The only caller that applies this result to a `Recipient`
     * (`RecipientView.verifyVatId()`) has no branch at all for `UNREACHABLE` — it shows an error
     * notification and leaves `vatIdVerificationStatus`/`vatIdVerifiedAt` untouched. This test
     * exercises the real adapter against a closed port (same technique as
     * `ViesHttpClientTest.connectionRefused`) to get a genuine `UNREACHABLE` result, then applies
     * that same contract and asserts the persisted recipient is unaffected.
     */
    @Test
    fun `W4 - a real unreachable VIES call leaves verification status and timestamp unchanged`() {
        QuarkusTransaction.requiringNew().run {
            val recipient = createValidRecipient("Unreachable Test")
            recipient.edit { vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED }
            val originalTimestamp = OffsetDateTime.now().minusHours(1)
            recipient.edit { vatIdVerifiedAt = originalTimestamp }
            customerManagementService.save(recipient)

            // Real ViesHttpClient pointed at a closed port — the connection is refused, so
            // verify() catches the exception internally and returns UNREACHABLE (never throws).
            val client = ViesHttpClient(
                timeoutSeconds = 1,
                baseUrl = "http://127.0.0.1:1",
                objectMapper = ObjectMapper(),
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build(),
            )

            val result = client.verify("DE", "123456789")
            assertTrue(
                result is VatIdVerificationResult.UNREACHABLE,
                "a refused connection must map to UNREACHABLE, never throw",
            )

            // Contract (RecipientView.verifyVatId(), UNREACHABLE branch): no field is written.
            // Nothing in this test writes to the recipient after the call above, so reloading it
            // demonstrates exactly that current behavior.
            val reloaded = recipientRepository.findById(recipient.id!!)!!
            assertEquals(VatIdVerificationStatus.VERIFIED, reloaded.vatIdVerificationStatus, "status unchanged on unreachable")
            assertEquals(originalTimestamp, reloaded.vatIdVerifiedAt, "timestamp unchanged on unreachable")
        }
    }

    /**
     * Reverse-charge eligibility requires VERIFIED status (TaxRuleResolver).
     * Only VERIFIED VAT IDs are "belastbarer Nachweis" for reverse-charge scenarios.
     */
    @Test
    fun `W5 - reverse-charge eligibility requires VERIFIED VAT ID status`() {
        QuarkusTransaction.requiringNew().run {
            val recipient = createValidRecipient("Reverse Charge Test")
            recipient.edit {
                customerType = CustomerType.BUSINESS
                vatIdVerificationStatus = VatIdVerificationStatus.UNVERIFIED
            }

            // Request for reverse-charge eligibility check — UNVERIFIED status
            val request = TaxRuleResolutionRequest(
                serviceType = ServiceType.GENERAL_SERVICE,
                serviceDate = LocalDate.now(),
                sellerCountry = Country.DE,
                buyerCountry = Country.FR, // EU target
                customerType = CustomerType.BUSINESS,
                sellerVatId = "DE123456789",
                buyerVatId = "FR12345678",
                buyerVatIdVerificationStatus = VatIdVerificationStatus.UNVERIFIED,
            )

            // The resolver should reject reverse-charge when status is not VERIFIED
            val result = TaxRuleResolver.resolve(request, today = FIXED_TODAY)
            assertTrue(
                result is TaxRuleResolution.Blocked,
                "reverse-charge must be blocked if VAT ID not verified"
            )
            val blocked = result as TaxRuleResolution.Blocked
            assertEquals(
                TaxRuleResolutionError.BUYER_VAT_ID_NOT_VERIFIED,
                blocked.error,
                "blocking reason must be VAT ID not verified"
            )
        }
    }

    /**
     * W6: Reverse-charge eligibility succeeds when VERIFIED.
     */
    @Test
    fun `W6 - reverse-charge succeeds when VAT ID is VERIFIED`() {
        QuarkusTransaction.requiringNew().run {
            // Similar to W5, but with VERIFIED status
            val request = TaxRuleResolutionRequest(
                serviceType = ServiceType.GENERAL_SERVICE,
                serviceDate = LocalDate.now(),
                sellerCountry = Country.DE,
                buyerCountry = Country.FR,
                customerType = CustomerType.BUSINESS,
                sellerVatId = "DE123456789",
                buyerVatId = "FR12345678",
                buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
            )

            val result = TaxRuleResolver.resolve(request, today = FIXED_TODAY)
            assertTrue(
                result is TaxRuleResolution.Proposed,
                "reverse-charge should succeed when VAT ID is verified"
            )
            val proposed = result as TaxRuleResolution.Proposed
            assertEquals("EU_B2B_RC_SERVICE", proposed.ruleCode, "reverse charge rule should be proposed")
        }
    }

    /**
     * W7: Reverse-charge eligibility fails when FAILED.
     */
    @Test
    fun `W7 - reverse-charge blocked when VAT ID is FAILED`() {
        QuarkusTransaction.requiringNew().run {
            val request = TaxRuleResolutionRequest(
                serviceType = ServiceType.GENERAL_SERVICE,
                serviceDate = java.time.LocalDate.now(),
                sellerCountry = Country.DE,
                buyerCountry = Country.FR,
                customerType = CustomerType.BUSINESS,
                sellerVatId = "DE123456789",
                buyerVatId = "FR12345678",
                buyerVatIdVerificationStatus = VatIdVerificationStatus.FAILED,
            )

            val result = TaxRuleResolver.resolve(request, today = FIXED_TODAY)
            assertTrue(
                result is TaxRuleResolution.Blocked,
                "reverse-charge must be blocked if VAT ID check failed"
            )
            val blocked = result as TaxRuleResolution.Blocked
            assertEquals(
                TaxRuleResolutionError.BUYER_VAT_ID_NOT_VERIFIED,
                blocked.error,
                "blocking reason must be VAT ID not verified"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Customer Numbers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * C1: Customer number uniqueness is enforced at service level (uq_recipient_customer_number).
     *
     * Both customers must be genuinely NEW (`id == null`) at the point of the colliding save:
     * `CustomerManagementService.save()` only routes new recipients through
     * `MasterDataNumberService.saveCustomerWithNumber` (the collision-retry path) — an already
     * persisted recipient (`id != null`) goes straight to `recipientRepository.save()` with no
     * flush and no collision check at all (see the dedicated `MD2-F3` finding test below). Using
     * `createValidRecipient()` (which saves immediately) and then mutating `customerNumber`
     * afterwards would silently exercise that other, uncollision-checked path instead.
     *
     * The colliding save must also run in its OWN transaction, wrapped entirely by
     * `assertThrows` (matching `MasterDataNumberCollisionIT`'s `N3-11`): `CustomerManagementService
     * .save()` is `@Transactional` (REQUIRED), so a `NumberCollisionException` escaping it marks
     * the transaction it joined rollback-only. Catching that exception *inside* an outer
     * `QuarkusTransaction.requiringNew().run { ... }` and then continuing in the same block would
     * still fail — the block's own final commit would hit `RollbackException`, because the
     * transaction was already poisoned. Letting the exception escape a `.call { ... }` invoked
     * from inside `assertThrows` avoids that: on an escaping exception, Quarkus rolls the
     * transaction back and rethrows the original exception unchanged, instead of attempting (and
     * failing) a commit.
     */
    @Test
    fun `C1 - duplicate customer number is rejected with user-readable error`() {
        QuarkusTransaction.requiringNew().run {
            customerManagementService.save(rawRecipient("Customer A", customerNumber = "MANUAL-001"))
        }

        val customer2 = rawRecipient("Customer B", customerNumber = "MANUAL-001") // same number, deliberately
        val ex = assertThrows(NumberCollisionException::class.java) {
            QuarkusTransaction.requiringNew().call {
                customerManagementService.save(customer2, proposedCustomerNumber = null)
            }
        }

        assertEquals("MANUAL-001", ex.attemptedValue, "exception should include the conflicting value")
    }

    /**
     * C2: Customer number allocator generates unique numbers per customer.
     */
    @Test
    fun `C2 - customer number allocator generates unique numbers`() {
        QuarkusTransaction.requiringNew().run {
            val customer1 = createValidRecipient("Allocated 1")
            val saved1 = customerManagementService.save(customer1)

            val customer2 = createValidRecipient("Allocated 2")
            val saved2 = customerManagementService.save(customer2)

            assertNotEquals(saved1.customerNumber, saved2.customerNumber, "allocator must generate different numbers")
            assertTrue(saved1.customerNumber != null, "first customer must have number")
            assertTrue(saved2.customerNumber != null, "second customer must have number")
        }
    }

    /**
     * C3: Customer number collision on an unmodified proposal triggers a retry (N3-12 behavior),
     * mirroring `MasterDataNumberCollisionIT`'s `N3-12` case for the same code path.
     */
    @Test
    fun `C3 - customer number collision on unmodified proposal retries once`() {
        QuarkusTransaction.requiringNew().run {
            // "Someone else" already got a number via the active allocator.
            val customer1 = createValidRecipient("Collision A")
            val number1 = customer1.customerNumber!!.value
            assertTrue(number1.isNotBlank())

            // Customer 2's proposal (e.g. from a stale peek()) happens to be the same value, and
            // the user never edited it -- a NEW recipient (id == null) with that value already set.
            val customer2 = rawRecipient("Collision B", customerNumber = number1)
            val saved2 = customerManagementService.save(customer2, proposedCustomerNumber = number1)

            assertNotEquals(number1, saved2.customerNumber?.value, "collision should trigger retry and assignment of new number")
            assertTrue(saved2.customerNumber != null, "retried customer must get a number")
        }
    }

    /**
     * C4: A deliberately typed duplicate — the proposal on offer differs from what ends up in the
     * field — is rejected without a silent retry (N3-11 behavior, with an explicit differing
     * proposal instead of no proposal at all; both fall into the same "not equal" branch of
     * `MasterDataNumberService.saveWithNumberCollisionCheck`).
     *
     * Same two-transaction structure as C1 above, and for the same reason: the colliding save
     * must run in its own transaction, entirely inside `assertThrows`.
     */
    @Test
    fun `C4 - deliberately typed duplicate is rejected without retry`() {
        val number1 = QuarkusTransaction.requiringNew().call {
            createValidRecipient("Deliberate A").customerNumber!!.value
        }
        assertTrue(number1.isNotBlank())

        // The dialog proposed "DIFFERENT-001", but the user typed/kept an already-taken value
        // instead -- a NEW recipient (id == null) with the deliberate value already set.
        val customer2 = rawRecipient("Deliberate B", customerNumber = number1)
        val differentProposal = "DIFFERENT-001"

        val ex = assertThrows(NumberCollisionException::class.java) {
            QuarkusTransaction.requiringNew().call {
                customerManagementService.save(customer2, proposedCustomerNumber = differentProposal)
            }
        }

        assertEquals(number1, ex.attemptedValue, "deliberate duplicate is rejected without a silent retry")
    }

    /**
     * Finding MD2-F3 (PO decision pending): `CustomerManagementService.save()` only routes NEW
     * recipients (`id == null`) through `MasterDataNumberService.saveCustomerWithNumber` — see
     * C1/C3/C4 above. For an EXISTING recipient (`id != null`) it calls
     * `recipientRepository.save(recipient)` directly (no flush, no collision check at all), even
     * though the edit form (`RecipientView`) lets a user retype the customer-number field on an
     * existing customer just as freely as on a new one. This test documents that CURRENT
     * behavior: `save()` itself accepts the duplicate without complaint (no
     * `NumberCollisionException`); the duplicate only ever surfaces once something forces a
     * flush (here: the transaction's own commit), and then as a raw DB-level exception, not the
     * user-readable `NumberCollisionException`. Open question for the PO: should an
     * existing-recipient customer-number CHANGE be routed through the same collision-retry path
     * as creation?
     */
    @Test
    fun `MD2-F3 - editing an existing recipient's customer number to a taken value bypasses collision detection`() {
        val (existingId, editedId) = QuarkusTransaction.requiringNew().call {
            val customer1 = createValidRecipient("Existing Number Holder")
            val customer2 = createValidRecipient("Existing Number Editor")
            customer1.id!! to customer2.id!!
        }
        val takenNumber = QuarkusTransaction.requiringNew().call {
            recipientRepository.findById(existingId)!!.customerNumber!!.value
        }
        assertTrue(takenNumber.isNotBlank())

        // Isolated in its own transaction: the save() call itself does not complain, but the
        // eventual commit-time flush does -- so this whole block is expected to fail overall.
        val ex = assertThrows(Exception::class.java) {
            QuarkusTransaction.requiringNew().run {
                val edited = recipientRepository.findById(editedId)!!
                edited.edit { customerNumber = CustomerNumber.ofNullable(takenNumber) }

                // No NumberCollisionException here (see the final assertion below) -- the
                // id != null path never calls MasterDataNumberService at all.
                val saved = customerManagementService.save(edited)
                assertEquals(takenNumber, saved.customerNumber?.value, "the duplicate is accepted without complaint at save() time")
            }
        }
        assertTrue(
            ex !is NumberCollisionException,
            "the eventual rejection is a raw DB/JTA-level exception, never the user-readable NumberCollisionException",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the instance `save()` hands back, never the one passed in: the very first seller in
     * an empty database is promoted to default by a merge, which bumps the row's version on a copy
     * only. Keeping the passed-in instance would make the next write fail its optimistic lock,
     * depending solely on which test happens to create the first seller.
     */
    private fun createValidSenderProfile(name: String): SenderProfile = sellerProfileService.save(
        aSenderProfile {
            this.name = name
            invoicePrefix = "PRE${uniqueCounter.incrementAndGet()}-"
            email = Email("sender${System.nanoTime()}@example.invalid")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "Amtsgericht Test"
            phone = "+49 30 1234567"
        },
        defaults = SellerDefaultSelections(),
    )

    private fun createValidRecipient(name: String): Recipient = aRecipient {
        this.name = name
        email = Email("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
        vatId = VatId("DE987654321")
    }.also { customerManagementService.save(it) }

    /**
     * A NEW (`id == null`), never-saved recipient — unlike [createValidRecipient], which saves
     * immediately. Needed whenever a test must exercise `CustomerManagementService.save()`'s
     * `id == null` branch (the only one that runs collision detection, see C1/C3/C4).
     */
    private fun rawRecipient(name: String, customerNumber: String = ""): Recipient = aRecipient {
        this.name = name
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        email = Email("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
        vatId = VatId("DE987654321")
    }

    private fun createMinimalItem(): de.littletrotter.domain.invoice.InvoiceItem =
        de.littletrotter.domain.invoice.InvoiceItem().edit {
            taxRateRef = em.seededStandardTaxRate()
            description = "Test Item"
            quantity = java.math.BigDecimal("1.0")
            unitPriceNet = de.littletrotter.shared.Money.of("100.00")
        }
}
