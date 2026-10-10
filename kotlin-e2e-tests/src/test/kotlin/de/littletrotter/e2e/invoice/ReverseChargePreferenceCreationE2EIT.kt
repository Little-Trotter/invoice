package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.RecipientVatInfo
import de.littletrotter.domain.invoice.ReverseChargeCreationDecision
import de.littletrotter.domain.invoice.resolveReverseChargeCreationDecision
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * [resolveReverseChargeCreationDecision] wired end to end through
 * [CreateInvoiceUseCase.createInvoiceWithRecipient] / `InvoiceLifecycleService.create`, real
 * Postgres.
 *
 * `InvoiceLifecycleService.create` deliberately never reads `Recipient.reverseChargePreference`
 * itself (`create` stays a pure consumer of an explicit decision) — only
 * `InvoiceCreateDialog` resolves the preference into an explicit answer. This suite has no
 * Vaadin layer to drive, so it plays the dialog's exact role instead: it derives the decision
 * from the recipient's standing preference via the same production pure function the dialog
 * calls ([resolveReverseChargeCreationDecision], never a hand-picked true/false), then threads
 * that already-resolved answer into the input port — proving the whole pipeline (preference -> pure
 * decision -> input port -> service -> persisted [Invoice.reverseCharge]) end to end against real
 * Postgres.
 *
 * [InvoiceCreateForCustomerE2EIT] already covers the port's behaviour apart from reverse charge; this file is
 * scoped to the recipient's [ReverseChargePreference] and its interaction with the existing
 * eligibility gate (`REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE`).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReverseChargePreferenceCreationE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    private val createdInvoiceIds = mutableListOf<Long>()
    private val createdRecipientIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            createdRecipientIds.forEach { em.find(Recipient::class.java, it)?.let(em::remove) }
        }
    }

    private fun sender(tag: String): SenderProfile {
        val sender = SenderProfile().apply {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        return sender
    }

    /** LU cross-border business recipient — [preference]/[verification] vary per test, everything
     *  else stays fixed so the assertions isolate exactly the dimension under test. */
    private fun luRecipient(
        tag: String,
        preference: ReverseChargePreference,
        verification: VatIdVerificationStatus?,
    ): Recipient {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Michels S.A."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("LU21416127")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = verification
            reverseChargePreference = preference
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        return recipient
    }

    /** The exact fixture shape that originally broke [de.littletrotter.domain.invoice
     *  .deriveSellerVatRegistrationCountry]: a recipient whose VAT-ID stays DE-format (never
     *  updated to match a country change) while its real, billed country is LU. Reused here to
     *  prove the address-based fix reaches [de.littletrotter.domain.invoice
     *  .resolveReverseChargeCreationDecision]/`InvoiceLifecycleService.create` too, not just the
     *  two original `SystemWorkflow*IT` classes. */
    private fun luRecipientWithMismatchedVatId(tag: String, preference: ReverseChargePreference): Recipient {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Michels S.A."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE111111111")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            reverseChargePreference = preference
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        return recipient
    }

    /** [sender] plus a real LU VAT registration — the seller side of the "matching registration"
     *  scenarios. */
    private fun senderWithLuRegistration(tag: String): SenderProfile {
        val s = sender(tag)
        s.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), java.time.LocalDate.now())
        return s
    }

    /** Plays [de.littletrotter.ui.view.invoice.InvoiceCreateDialog]'s own role: derives the
     *  creation-time answer from the recipient's standing preference exactly the way the dialog
     *  does, never a hand-picked true/false. Every fixture in this file resolves outright (no
     *  fixture lands in the Ask branch — that dialog behaviour is Karibu's own coverage,
     *  `InvoiceCreateDialogKaribuTest`). [recipientCountry] mirrors
     *  [de.littletrotter.ui.view.invoice.InvoiceCreateDialog]'s own resolution (billing address,
     *  falling back to delivery), the same signal `InvoiceLifecycleService.create` uses; it is
     *  no longer derived from [Recipient.vatId]. */
    private fun resolvedDecisionFor(sender: SenderProfile, recipient: Recipient): Boolean {
        val recipientCountry = (recipient.billingAddress ?: recipient.deliveryAddress)?.country
        val decision = resolveReverseChargeCreationDecision(
            recipient.reverseChargePreference,
            sender.vatId?.value,
            RecipientVatInfo(recipient.vatId?.value, recipientCountry),
            sender.additionalVatRegistrations.map { it.country }.toSet(),
        )
        check(decision is ReverseChargeCreationDecision.Resolved) { "fixture expected to resolve outright, not ask: $decision" }
        return decision.reverseChargeDecision
    }

    // ── happy path ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - ALWAYS preference with a verified cross-border VAT-ID round-trips reverseCharge true derived from the standing preference`() {
        val tag = "RCALW1"
        val (senderId, recipientId, decision) = QuarkusTransaction.requiringNew().call {
            val s = sender(tag)
            val r = luRecipient(tag, ReverseChargePreference.ALWAYS, VatIdVerificationStatus.VERIFIED)
            em.persist(s); em.persist(r); em.flush()
            Triple(s.id!!, r.id!!, resolvedDecisionFor(s, r))
        }
        createdRecipientIds += recipientId
        assertEquals(true, decision, "ALWAYS resolves to an explicit true, exactly what InvoiceCreateDialog would pre-supply while invisible")

        val created = QuarkusTransaction.requiringNew().call { createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId, decision) }
        createdInvoiceIds += created.id

        QuarkusTransaction.requiringNew().run {
            assertEquals(true, em.find(Invoice::class.java, created.id)!!.reverseCharge, "ALWAYS + verified VAT-ID round-trips reverseCharge=true")
        }
    }

    // ── edge case 1: NEVER round-trips false ───────────────────────────────────────────────────

    @Test
    fun `NEVER preference round-trips reverseCharge false despite the cross-border VAT-ID mismatch`() {
        val tag = "RCNEV1"
        val (senderId, recipientId, decision) = QuarkusTransaction.requiringNew().call {
            val s = sender(tag)
            val r = luRecipient(tag, ReverseChargePreference.NEVER, VatIdVerificationStatus.VERIFIED)
            em.persist(s); em.persist(r); em.flush()
            Triple(s.id!!, r.id!!, resolvedDecisionFor(s, r))
        }
        createdRecipientIds += recipientId
        assertEquals(false, decision, "NEVER resolves to an explicit false regardless of the country mismatch")

        val created = QuarkusTransaction.requiringNew().call { createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId, decision) }
        createdInvoiceIds += created.id

        QuarkusTransaction.requiringNew().run {
            assertEquals(false, em.find(Invoice::class.java, created.id)!!.reverseCharge, "NEVER round-trips reverseCharge=false even though seller/recipient VAT countries differ")
        }
    }

    // ── edge case 2: ALWAYS + unverified VAT-ID blocks with the eligibility reason ─────────────

    @Test
    fun `ALWAYS preference with an unverified VAT-ID throws REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE`() {
        val tag = "RCALW2"
        val (senderId, recipientId, decision) = QuarkusTransaction.requiringNew().call {
            val s = sender(tag)
            val r = luRecipient(tag, ReverseChargePreference.ALWAYS, VatIdVerificationStatus.UNVERIFIED)
            em.persist(s); em.persist(r); em.flush()
            Triple(s.id!!, r.id!!, resolvedDecisionFor(s, r))
        }
        createdRecipientIds += recipientId
        assertEquals(true, decision, "ALWAYS still resolves to true — VAT-ID verification status is not this pure function's concern")

        // The office manager clicked nothing this time (ALWAYS auto-supplies "Yes"),
        // but an unverified VAT-ID still fails TaxRuleResolver's eligibility check the same way a
        // manual "Yes" would. The existing gate must block, not silently fall back to standard tax.
        val ex = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().call { createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId, decision) }
        }
        assertEquals(InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    // ── edge case 3: an explicit reverseChargeDecision argument still wins over ASK ────────────

    @Test
    fun `an explicit reverseChargeDecision argument still wins over an ASK-preference recipient`() {
        val tag = "RCASK1"
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val s = sender(tag)
            // ASK is the default preference — deliberately not overridden here, proving
            // create() never consults reverseChargePreference itself when the caller (a non-UI
            // fixture in this case) already supplies an explicit answer.
            val r = luRecipient(tag, ReverseChargePreference.ASK, VatIdVerificationStatus.VERIFIED)
            em.persist(s); em.persist(r); em.flush()
            listOf(s.id!!, r.id!!)
        }
        createdRecipientIds += recipientId

        val created = QuarkusTransaction.requiringNew().call {
            createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId, reverseChargeDecision = true)
        }
        createdInvoiceIds += created.id

        QuarkusTransaction.requiringNew().run {
            assertEquals(true, em.find(Invoice::class.java, created.id)!!.reverseCharge, "the explicit argument wins regardless of the recipient's ASK preference")
        }
    }

    // ── edge case 4: address, not VAT-ID, decides end to end ──────────────────────────────────

    @Test
    fun `ALWAYS preference is still blocked downstream when the seller already holds a matching registration for the recipient's real (address) country, even though the VAT-ID alone would have hidden it`() {
        val tag = "RCFIXH1"
        val (senderId, recipientId, decision) = QuarkusTransaction.requiringNew().call {
            val s = senderWithLuRegistration(tag)
            // vatId stays DE-format; only the billing address says LU. Before the address-based
            // fix reached this pure function, deriveSellerVatRegistrationCountry(recipientVatId, ...) would have
            // derived Country.DE from this VAT-ID (rejected outright, DE is never an "additional"
            // registration) and never noticed the seller's real LU registration at all.
            val r = luRecipientWithMismatchedVatId(tag, ReverseChargePreference.ALWAYS)
            em.persist(s); em.persist(r); em.flush()
            Triple(s.id!!, r.id!!, resolvedDecisionFor(s, r))
        }
        createdRecipientIds += recipientId
        assertEquals(true, decision, "ALWAYS still resolves to an explicit true from the pure function alone — the block below is InvoiceLifecycleService.create's own doing")

        // create() derives sellerVatRegistrationCountry from the same address-based signal —
        // for this recipient that resolves to LU, which makes reverseChargeEligibility()
        // return null (an active foreign seller VAT registration and Reverse Charge are mutually
        // exclusive), so the explicit "true" this dialog/fixture supplied is rejected
        // exactly as it should be — proving this country-not-VAT-ID correction propagates all
        // the way through this new reverse-charge creation path, not just the original two
        // SystemWorkflow*IT classes.
        val ex = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().call { createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId, decision) }
        }
        assertEquals(InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)

        QuarkusTransaction.requiringNew().run {
            val recipient = em.find(Recipient::class.java, recipientId)!!
            assertEquals(Country.LU, recipient.billingAddress?.country, "sanity: the recipient's real country is LU, its VAT-ID alone would have said DE")
        }
    }
}
