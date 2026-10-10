package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.domain.exception.RecipientNotFoundException
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Invoice from a customer row — [CreateInvoiceUseCase.createInvoiceWithRecipient] end to end, the path both
 * [de.littletrotter.ui.view.invoice.InvoiceCreateDialog] call sites (the "Neue Rechnung" toolbar
 * dialog and the customer table's per-row action) ultimately drive. Covers what
 * [InvoiceHeaderPartyDefaultsE2EIT] does not: that one only exercises `updateInvoiceHeader` on an
 * already-created invoice, never `createInvoiceWithRecipient` itself, and never its two
 * not-found border cases.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceCreateForCustomerE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var createInvoice: CreateInvoiceUseCase

    @Inject
    lateinit var b2bProfileQueries: B2bProfileQueryPort

    @Inject
    lateinit var manageB2bProfile: ManageB2bProfileUseCase

    @Inject
    lateinit var b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort

    @Inject
    lateinit var manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase

    @Inject
    lateinit var b2bFieldValueQueries: B2bFieldValueQueryPort

    @Inject
    lateinit var manageB2bFieldValue: ManageB2bFieldValueUseCase

    // This file's older tests carry no cleanup (unique tags keep them from colliding); the newer
    // tests below track their own rows and clean up, same idiom as RecipientSearchE2EIT.
    // Order matters: a Recipient FK-references its B2bProfile (recipient_b2b_profile_id_fkey), so
    // recipients must be removed before their profile, not just invoices before profiles.
    private val createdInvoiceIds = mutableListOf<Long>()
    private val createdRecipientIds = mutableListOf<Long>()
    private val createdProfileIds = mutableListOf<Long>()

    @AfterEach
    fun cleanupExD() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            createdRecipientIds.forEach { em.find(Recipient::class.java, it)?.let(em::remove) }
            createdProfileIds.forEach { em.find(B2bProfile::class.java, it)?.let(em::remove) }
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

    private fun recipient(tag: String): Recipient {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenweg 2", "20095", "Hamburg", Country.DE, true))
        return recipient
    }

    @Test
    fun `creating an invoice for an existing recipient carries that recipient's data onto the new invoice`() {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = sender("ICF1")
            val rec = recipient("ICF1")
            em.persist(sender); em.persist(rec)
            em.flush()
            listOf(sender.id!!, rec.id!!)
        }

        val created = QuarkusTransaction.requiringNew().call {
            createInvoice.createInvoiceWithRecipient(senderId, recipientId)
        }

        assertEquals("ICF1 Empfänger AG", created.recipient?.name)
        assertEquals("ICF1-100", created.recipient?.customerNumber)
        assertEquals("DE222222222", created.recipient?.vatId)
        assertEquals("ICF1 Sender GmbH", created.sender?.name)
    }

    @Test
    fun `creating an invoice for an unknown recipient id raises RecipientNotFoundException`() {
        val senderId = QuarkusTransaction.requiringNew().call {
            val sender = sender("ICF2")
            em.persist(sender)
            em.flush()
            sender.id!!
        }

        assertThrows(RecipientNotFoundException::class.java) {
            QuarkusTransaction.requiringNew().call {
                createInvoice.createInvoiceWithRecipient(senderId, -1L)
            }
        }
    }

    @Test
    fun `creating an invoice for an unknown seller id raises SenderProfileNotFoundException`() {
        val recipientId = QuarkusTransaction.requiringNew().call {
            val rec = recipient("ICF3")
            em.persist(rec)
            em.flush()
            rec.id!!
        }

        assertThrows(SenderProfileNotFoundException::class.java) {
            QuarkusTransaction.requiringNew().call {
                createInvoice.createInvoiceWithRecipient(-1L, recipientId)
            }
        }
    }

    // ─── LU customer with standing B2B profile (happy) ────────────────────────────────────────

    /**
     * A customer with an LU address, a standing B2B profile and a per-customer RECIPIENT value combines correctly at `createInvoiceWithRecipient`: LU registration preselected, the profile applied, the RECIPIENT value reachable from the header scope, `invoiceDate == today`.
     *
     * This used to pin a production bug: `InvoiceLifecycleService.create` computed
     * `dueDate = LocalDate.now().plusDays(paymentTermsDays.toLong())` using
     * [Invoice.paymentTermsDays]'s class-default (14) BEFORE `invoice.paymentTerm` was even
     * assigned a few lines later, so `dueDate` disagreed with the resolved [PaymentTerm.dueInDays]
     * from the moment of creation. Fixed by deriving `dueDate` from the already-resolved
     * `paymentTerm` (falling back to `paymentTermsDays` only when no term resolves at all). This
     * fixture now asserts the corrected behavior: the sender's own default payment term is "30
     * Tage netto" (`dueInDays = 30`), and the created invoice's `dueDate` is `today + 30`,
     * consistent with `invoice.paymentTerm` from the start.
     */
    @Test
    fun `happy - LU registration preselects, the standing profile applies, the RECIPIENT value reaches the header scope, and invoiceDate is today`() {
        val tag = "EXD1"
        val (senderId, recipientId, profileId) = QuarkusTransaction.requiringNew().call {
            val net30 = PaymentTerm().apply { setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "$tag 30 Tage netto"); dueInDays = 30 }
            em.persist(net30)

            val s = sender(tag).apply { defaultPaymentTerm = net30 }
            s.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU99998888"))), java.time.LocalDate.now())

            val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
            manageB2bFieldDefinition.saveB2bProfileField(
                B2bProfileFieldDto(
                    profileId = profile.id, term = B2bTerm.BT_18, businessDefinition = "Vorgangskennung",
                    requirement = B2bFieldRequirement.OPTIONAL, placement = B2bPlacement.RECIPIENT,
                ),
            )

            val r = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100"); name = "$tag Michels S.A."
                email = Email.ofNullable("ap@$tag.example"); vatId = VatId("LU21416127")
                customerType = CustomerType.BUSINESS; routingId = "PO-$tag-1"
                b2bProfile = em.find(B2bProfile::class.java, profile.id)
            }
            r.addAddress(Address.forRecipient(r, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
            em.persist(s); em.persist(r); em.flush()

            manageB2bFieldValue.saveB2bRecipientFieldValues(r.id!!, profile.id!!, mapOf("BT-18" to "RCPT-EXD1"))
            listOf(s.id!!, r.id!!, profile.id!!)
        }
        createdProfileIds += profileId
        createdRecipientIds += recipientId

        val created = QuarkusTransaction.requiringNew().call { createInvoice.createInvoiceWithRecipient(senderId, recipientId) }
        createdInvoiceIds += created.id

        assertEquals("LU", created.sellerVatRegistrationCountry, "R-1 preselects the seller's LU registration from the recipient's LU billing country")
        assertEquals(LocalDate.now(), created.invoiceDate, "invoiceDate is always today at creation")

        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, created.id)!!
            assertEquals(profileId, invoice.b2bProfile?.id, "AP-9 rule 1 — the standing B2B profile applies at creation")
            assertEquals(30, invoice.paymentTerm?.dueInDays, "the sender's own default payment term (30 days) is what gets attached")
            assertEquals(LocalDate.now().plusDays(30), invoice.dueDate, "finding-fixes (2026-09-03): dueDate is now derived from the resolved payment term's own dueInDays")
        }

        val recipientValues = QuarkusTransaction.requiringNew().call {
            b2bFieldDefinitionQueries.findB2bRecipientFields(profileId).map { it.term } to b2bFieldValueQueries.findB2bRecipientFieldValues(recipientId, profileId)
        }
        assertEquals(listOf(B2bTerm.BT_18), recipientValues.first, "the RECIPIENT-placed field is on offer at the recipient scope")
        assertEquals("RCPT-EXD1", recipientValues.second["BT-18"], "the per-customer RECIPIENT value is reachable from that same scope")
    }

    // ─── Deactivated standing profile still applies (border) ─────────────────────────────────

    /**
     * Creating an invoice for a customer whose standing profile was deactivated AFTER assignment still succeeds.
     *
     * **Reality-vs-spec pin:** the expectation that a deactivated profile degrades to "no B2B
     * sections offered" does not hold — there IS no degradation. `InvoiceLifecycleService.create`
     * reads `recipient.b2bProfile` as a plain entity reference with no `active` check at all, and
     * none of [B2bFieldDefinitionQueryPort.findB2bInvoiceHeaderFields]/`findB2bInvoiceItemFields`/
     * `findB2bRecipientFields`/`findB2bSellerFields` (nor the underlying
     * `B2bProfileFieldDefinitionService.findFields`) filter on [B2bProfile.active] either — that
     * flag only gates the profile PICKER ([B2bProfileQueryPort.findAllActiveB2bProfiles], for choosing
     * a NEW profile going forward) and the seller-form union
     * (`B2bProfileFieldDefinitionService.findSellerFieldsUnion`'s "already in use" carve-out). A
     * new invoice for a customer whose already-assigned profile has since been deactivated
     * inherits that profile fully, fields and all — asserted below as the (non-)degradation it is.
     */
    @Test
    fun `border - creating an invoice after the customer's standing profile was deactivated still succeeds with that profile's fields fully intact`() {
        val tag = "EXD2"
        val (senderId, recipientId, profileId) = QuarkusTransaction.requiringNew().call {
            val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
            manageB2bFieldDefinition.saveB2bProfileField(
                B2bProfileFieldDto(
                    profileId = profile.id, term = B2bTerm.BT_12, businessDefinition = "Vertragsnummer",
                    requirement = B2bFieldRequirement.OPTIONAL, placement = B2bPlacement.INVOICE,
                ),
            )
            val s = sender(tag)
            val r = recipient(tag).apply { b2bProfile = em.find(B2bProfile::class.java, profile.id) }
            em.persist(s); em.persist(r); em.flush()

            // Deactivate AFTER the standing assignment, before the invoice is ever created.
            manageB2bProfile.saveB2bProfile(profile.copy(active = false))

            listOf(s.id!!, r.id!!, profile.id!!)
        }
        createdProfileIds += profileId
        createdRecipientIds += recipientId
        assertFalse(b2bProfileQueries.findAllActiveB2bProfiles().any { it.id == profileId }, "fixture sanity: the profile really is inactive")

        val created = QuarkusTransaction.requiringNew().call { createInvoice.createInvoiceWithRecipient(senderId, recipientId) }
        createdInvoiceIds += created.id

        QuarkusTransaction.requiringNew().run {
            assertEquals(profileId, em.find(Invoice::class.java, created.id)!!.b2bProfile?.id, "creation succeeds and still attaches the (inactive) standing profile")
        }
        assertEquals(
            listOf(B2bTerm.BT_12),
            b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(profileId).map { it.term },
            "reality pin (see this test's own KDoc): the deactivated profile's fields are still fully offered, not degraded to none",
        )
    }
}
