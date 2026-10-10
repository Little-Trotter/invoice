package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bFieldValueOrigin
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * fix-einvoice-guided-profile-party-values — [B2bFieldValueService.resolvedGuidedFieldValues] (the
 * chain the guided entry-pane step 02 renders from) and the §3 write-back rule
 * ([B2bFieldValueService.saveFieldValues]'s null-deletes contract, driven the same way
 * [de.littletrotter.ui.component.B2bProfileFieldsSection.currentValues] drives it in production)
 * against a real Postgres (Testcontainers, [PostgresTestResource]): DB-touching behavior is not
 * "done" on the mocked [B2bProfileServiceTest] unit tests alone — this exercises the real
 * `b2b_field_value`/`recipient_b2b_field_value` repositories and the real CDI-wired
 * [InvoiceLifecycleService], not fakes.
 *
 * `@TestTransaction` (rollback), not `@Transactional` (commit) — same reasoning as
 * [B2bProfileCrudIT]'s KDoc: every test writes and reads inside its own rolled-back transaction.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class B2bProfileGuidedFieldValuesIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var b2bProfileLifecycleService: B2bProfileLifecycleService

    @Inject
    lateinit var b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    @Inject
    lateinit var b2bProfileUsageService: B2bProfileUsageService

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    /** Same fixture shape as [B2bProfileCrudIT.sender] — `registerCourt`/`email`/`taxNumber`/
     *  `vatId`/`iban` all set so `Invoice.validate()` never trips on this class's fixtures. */
    private fun sender(prefix: String): SenderProfile = SenderProfile().apply {
        name = "Guided Sender $prefix"
        invoicePrefix = prefix
        registerCourt = ""
        email = Email.ofNullable("$prefix@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE02120300000000202051")
    }.also { em.persist(it) }

    private fun recipient(customerNumber: String, b2bProfile: B2bProfile? = null): Recipient = Recipient().apply {
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        name = "Guided Recipient $customerNumber"
        email = Email.ofNullable("$customerNumber@buyer.example")
        vatId = VatId("DE987654321")
        this.b2bProfile = b2bProfile
    }.also { em.persist(it) }

    private fun sellerField(profile: B2bProfile): B2bProfileField = b2bProfileFieldDefinitionService.saveField(
        B2bProfileField().apply {
            this.profile = profile
            term = B2bTerm.BT_58
            placement = B2bPlacement.SELLER
            requirement = B2bFieldRequirement.OPTIONAL
            businessDefinition = "Anbieter-Kontakt-E-Mail"
        },
    )

    private fun recipientField(profile: B2bProfile): B2bProfileField = b2bProfileFieldDefinitionService.saveField(
        B2bProfileField().apply {
            this.profile = profile
            term = B2bTerm.BT_58
            placement = B2bPlacement.RECIPIENT
            requirement = B2bFieldRequirement.OPTIONAL
            businessDefinition = "Kunden-Kontakt-E-Mail"
        },
    )

    @Test
    @TestTransaction
    fun `resolvedGuidedFieldValues resolves a SELLER-placed field from the seller's own stored value`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "Guided-IT A" })
        sellerField(profile)
        val s = sender("GDA1")
        val r = recipient("GD-IT-0001", b2bProfile = profile)
        em.flush()
        val invoice = lifecycleService.create(s.id!!, r.id!!)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, s.id!!, mapOf("BT-58" to "anbieter@example.com"))
        em.flush(); em.clear()

        val resolved = b2bFieldValueService.resolvedGuidedFieldValues(invoice.id!!)

        assertEquals("anbieter@example.com", resolved.getValue("BT-58").value)
        assertEquals(B2bFieldValueOrigin.PARTY, resolved.getValue("BT-58").origin)
    }

    @Test
    @TestTransaction
    fun `an invoice-level override wins over the seller's stored value and reverts cleanly once written back to null`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "Guided-IT B" })
        sellerField(profile)
        val s = sender("GDB1")
        val r = recipient("GD-IT-0002", b2bProfile = profile)
        em.flush()
        val invoice = lifecycleService.create(s.id!!, r.id!!)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, s.id!!, mapOf("BT-58" to "anbieter@example.com"))
        em.flush(); em.clear()

        // §3 — an untouched inherited value must never materialize as an invoice row: the
        // write-back rule (B2bProfileFieldsSection.currentValues) passes null for a widget still at
        // its inherited value, and saveFieldValues already deletes the row on null/blank.
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!, mapOf("BT-58" to null))
        em.flush(); em.clear()
        assertEquals(
            "anbieter@example.com",
            b2bFieldValueService.resolvedGuidedFieldValues(invoice.id!!).getValue("BT-58").value,
            "no spurious row - the value still resolves via the party tier",
        )

        // A genuine override IS written as an invoice row and wins over the party tier.
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!, mapOf("BT-58" to "rechnung-spezifisch@example.com"))
        em.flush(); em.clear()
        val overridden = b2bFieldValueService.resolvedGuidedFieldValues(invoice.id!!).getValue("BT-58")
        assertEquals("rechnung-spezifisch@example.com", overridden.value)
        assertEquals(B2bFieldValueOrigin.INVOICE, overridden.origin)

        // Edited back to the inherited value — the write-back rule maps that back to `null` — the
        // row is deleted and resolution falls through to the seller's own value again.
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!, mapOf("BT-58" to null))
        em.flush(); em.clear()
        val revertedToParty = b2bFieldValueService.resolvedGuidedFieldValues(invoice.id!!).getValue("BT-58")
        assertEquals("anbieter@example.com", revertedToParty.value)
        assertEquals(B2bFieldValueOrigin.PARTY, revertedToParty.origin)
    }

    @Test
    @TestTransaction
    fun `a RECIPIENT-placed field resolves through the recipient's own stored value against real Postgres`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "Guided-IT C" })
        recipientField(profile)
        val s = sender("GDC1")
        val r = recipient("GD-IT-0003", b2bProfile = profile)
        em.flush()
        val invoice = lifecycleService.create(s.id!!, r.id!!)
        b2bFieldValueService.saveRecipientFieldValues(r.id!!, profile.id!!, mapOf("BT-58" to "kunde@example.com"))
        em.flush(); em.clear()

        val resolved = b2bFieldValueService.resolvedGuidedFieldValues(invoice.id!!)

        assertEquals("kunde@example.com", resolved.getValue("BT-58").value)
        assertEquals(B2bFieldValueOrigin.PARTY, resolved.getValue("BT-58").origin)
    }

    @Test
    @TestTransaction
    fun `resolvedGuidedFieldValues falls back to the profile default against real Postgres when nothing is stored`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "Guided-IT D" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_58
                placement = B2bPlacement.SELLER
                requirement = B2bFieldRequirement.OPTIONAL
                businessDefinition = "Anbieter-Kontakt-E-Mail"
                defaultValue = "standard@example.com"
            },
        )
        val s = sender("GDD1")
        val r = recipient("GD-IT-0004", b2bProfile = profile)
        em.flush()
        val invoice = lifecycleService.create(s.id!!, r.id!!)
        em.flush(); em.clear()

        val resolved = b2bFieldValueService.resolvedGuidedFieldValues(invoice.id!!)

        assertEquals("standard@example.com", resolved.getValue("BT-58").value)
        assertEquals(B2bFieldValueOrigin.DEFAULT, resolved.getValue("BT-58").origin)
    }
}
