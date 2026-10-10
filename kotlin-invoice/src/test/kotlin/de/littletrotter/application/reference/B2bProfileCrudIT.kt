package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceHeaderUpdateService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * The standard surface of B2B profile management ([B2bProfileLifecycleService]/[B2bProfileFieldDefinitionService]) against a real
 * Postgres (Testcontainers, [PostgresTestResource]): CRUD on [B2bProfile], field assignment on
 * [B2bProfileField], and the three-way contract (derive / manual override / recipient
 * change overwrites) exercised through the real CDI-wired [InvoiceLifecycleService], not a fake.
 *
 * `@TestTransaction` (rollback), not `@Transactional` (commit) — same reasoning as
 * `PrintJobOverviewQueryServiceIT`'s KDoc: every test here writes and reads inside its own rolled
 * back transaction, so fixtures never need a unique-prefix cleanup dance across test runs.
 *
 * The same sequence is already covered exhaustively at the unit level in
 * `InvoiceLifecycleServiceTest` (fakes, fast). This class re-proves the same contract through the
 * real Panache repositories and the real `InvoiceLifecycleSupport.b2bProfileRepo` wiring — a unit
 * test with fakes cannot catch a broken repository query or a missing bean.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class B2bProfileCrudIT {

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

    @Inject
    lateinit var headerUpdateService: InvoiceHeaderUpdateService

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    /** Fully valid on purpose (email/taxNumber/vatId/iban set): some tests below drive
     *  [InvoiceLifecycleService.updateHeader], which calls `Invoice.validate()` -> `sender.validate()`.
     *  `registerCourt` is a NOT-NULL column (`V007`) with an empty-string DB default that Hibernate
     *  never actually applies — it sends `NULL` explicitly for an unset Kotlin property, which the
     *  NOT NULL constraint then rejects. Every [SenderProfile] persisted anywhere in this class must
     *  set it, not only the ones exercising a full invoice validation. `invoicePrefix` carries a
     *  unique index (`uq_sender_profile_invoice_prefix`) and a `@Size(max = 10)` bean validation
     *  constraint — callers pass a short, per-test-unique code. */
    private fun sender(prefix: String): SenderProfile = SenderProfile().apply {
        name = "EP-1 Sender $prefix"
        invoicePrefix = prefix
        registerCourt = ""
        email = Email.ofNullable("$prefix@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE02120300000000202051")
    }.also { em.persist(it) }

    /** `customerNumber` carries a unique index (`uq_recipient_customer_number`), so callers pass a
     *  per-test-unique value. `vatId`/`email` set for the same `Invoice.validate()` reason as
     *  [sender] — `Recipient.customerType` stays `null` here, which takes `Recipient.validate()`'s
     *  stricter branch and requires a non-blank `vatId`. */
    private fun recipient(customerNumber: String, b2bProfile: B2bProfile? = null): Recipient = Recipient().apply {
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        name = "EP-1 Recipient $customerNumber"
        email = Email.ofNullable("$customerNumber@buyer.example")
        vatId = VatId("DE987654321")
        this.b2bProfile = b2bProfile
    }.also { em.persist(it) }

    private fun taxRate(code: String): TaxRate = TaxRate().apply {
        this.code = code
        category = "S"
        name = "VAT 19"
        rate = TaxRatePercent(BigDecimal("19.00"))
        isDefault = false
    }.also { em.persist(it) }

    /** Attaches one valid, finalization-agnostic item to [invoiceId] so that a subsequent
     *  `updateHeader` call's `Invoice.validate()` (which requires `items.isNotEmpty()`) succeeds. */
    private fun addValidItem(invoice: de.littletrotter.domain.invoice.Invoice, taxRate: TaxRate) {
        val item = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = "Beratung"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.HOUR
            unitPriceNet = Money.of("100.00")
            taxRateRef = taxRate
            sortOrder = 0
        }
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.flush()
    }

    private fun headerCommand(invoiceId: Long, recipientId: Long?, senderProfileId: Long?, b2bProfileId: Long?) =
        UpdateInvoiceHeaderCommand(
            invoiceId = invoiceId,
            expectedVersion = null,
            invoiceDate = null,
            servicePeriodFrom = null,
            servicePeriodTo = null,
            dueDate = null,
            freeText = null,
            buyerReference = null,
            orderReference = null,
            orderReferenceDate = null,
            currency = Currency.EUR,
            recipientId = recipientId,
            senderProfileId = senderProfileId,
            paymentTermId = null,
            b2bProfileId = b2bProfileId,
        )

    // ── 1. CRUD ───────────────────────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `saveProfile persists a new profile, active by default`() {
        val saved = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD Alpha" })

        assertTrue(saved.id != null, "a saved profile must have an assigned id")
        assertTrue(saved.active)
        em.flush(); em.clear()
        val reloaded = b2bProfileLifecycleService.findProfileById(saved.id!!)
        assertEquals("EP1-CRUD Alpha", reloaded?.name)
    }

    @Test
    @TestTransaction
    fun `saveProfile rejects a second profile with the same name`() {
        b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD Duplicate" })

        assertThrows(InvalidParameterException::class.java) {
            b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD Duplicate" })
        }
    }

    @Test
    @TestTransaction
    fun `rename updates the persisted name of the same row, not a new one`() {
        val saved = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD Before" })
        val id = saved.id!!
        em.flush(); em.clear()

        val toRename = b2bProfileLifecycleService.findProfileById(id)!!
        toRename.name = "EP1-CRUD After"
        b2bProfileLifecycleService.saveProfile(toRename)
        em.flush(); em.clear()

        val reloaded = b2bProfileLifecycleService.findProfileById(id)!!
        assertEquals(id, reloaded.id)
        assertEquals("EP1-CRUD After", reloaded.name)
    }

    @Test
    @TestTransaction
    fun `deactivate flips active to false and excludes the profile from findAllActive, but findById still returns it`() {
        val saved = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD Deactivate" })
        val id = saved.id!!
        em.flush(); em.clear()

        val toDeactivate = b2bProfileLifecycleService.findProfileById(id)!!
        toDeactivate.active = false
        b2bProfileLifecycleService.saveProfile(toDeactivate)
        em.flush(); em.clear()

        assertFalse(b2bProfileLifecycleService.findAllActiveProfiles().any { it.id == id })
        val stillFindable = b2bProfileLifecycleService.findProfileById(id)
        assertTrue(stillFindable != null && !stillFindable.active)
    }

    @Test
    @TestTransaction
    fun `delete removes an unused profile and cascades its fields`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD Delete" })
        val field = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_19
                businessDefinition = "Buchungsreferenz"
                requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.INVOICE
            },
        )
        val fieldId = field.id!!
        em.flush(); em.clear()

        b2bProfileLifecycleService.deleteProfile(profile.id!!)
        em.flush(); em.clear()

        assertNull(b2bProfileLifecycleService.findProfileById(profile.id!!))
        assertNull(em.find(B2bProfileField::class.java, fieldId), "orphanRemoval must cascade-delete the profile's fields")
    }

    @Test
    @TestTransaction
    fun `deleteProfile is blocked while a recipient still holds it as their standing default, naming that recipient`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-CRUD InUse" })
        recipient("EP1-CRUD-0001", b2bProfile = profile)
        em.flush(); em.clear()

        val ex = assertThrows(InvalidParameterException::class.java) {
            b2bProfileLifecycleService.deleteProfile(profile.id!!)
        }
        assertTrue(ex.message?.contains("EP-1 Recipient EP1-CRUD-0001") == true, "the guard must name the blocking customer: ${ex.message}")
        assertTrue(b2bProfileLifecycleService.findProfileById(profile.id!!) != null, "a blocked delete must not remove the profile")
    }

    // ── 2. Field assignment ──────────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `saveField attaches a FREE HEADER catalog term, round-tripping enum ordinals correctly`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-Field Attach" })

        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_19 // seeded HEADER/TEXT/FREE (V903)
                businessDefinition = "Buchungsreferenz des Käufers"
                requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.INVOICE
            },
        )
        em.flush(); em.clear()

        val fields = b2bProfileFieldDefinitionService.findFields(profile.id!!)
        assertEquals(1, fields.size)
        assertEquals("BT-19", fields[0].term?.officialCode)
        assertEquals(B2bFieldRequirement.MANDATORY, fields[0].requirement)
        assertEquals(B2bPlacement.INVOICE, fields[0].placement)
    }

    @Test
    @TestTransaction
    fun `saveField rejects a second field with the same bt_code on one profile`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-Field Dup" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_19
                businessDefinition = "Erste Zuordnung"; requirement = B2bFieldRequirement.OPTIONAL
            },
        )

        assertThrows(InvalidParameterException::class.java) {
            b2bProfileFieldDefinitionService.saveField(
                B2bProfileField().apply {
                    this.profile = profile; term = B2bTerm.BT_19
                    businessDefinition = "Zweite Zuordnung"; requirement = B2bFieldRequirement.OPTIONAL
                },
            )
        }
    }

    @Test
    @TestTransaction
    fun `saveField forces a LINE-level term to INVOICE placement regardless of the requested placement`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-Field LineForce" })

        val saved = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_155 // seeded LINE/TEXT/FREE (V903)
                businessDefinition = "Verkäufer-Artikelnummer"
                requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.SELLER // requested — must be silently overridden
            },
        )

        assertEquals(
            B2bPlacement.INVOICE, saved.placement,
            "a LINE-level term is fixed to INVOICE placement by saveField, not just validated against it",
        )
    }

    @Test
    @TestTransaction
    fun `deleteField removes only the targeted field, its sibling remains`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-Field Delete" })
        val toDelete = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_19
                businessDefinition = "Wird gelöscht"; requirement = B2bFieldRequirement.OPTIONAL
            },
        )
        val toKeep = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_56
                businessDefinition = "Bleibt bestehen"; requirement = B2bFieldRequirement.OPTIONAL
            },
        )

        b2bProfileFieldDefinitionService.deleteField(toDelete.id!!)
        em.flush(); em.clear()

        val remaining = b2bProfileFieldDefinitionService.findFields(profile.id!!)
        assertEquals(listOf(toKeep.id), remaining.map { it.id })
    }

    @Test
    @TestTransaction
    fun `findFields orders by sortOrder then bt_code, so editing sortOrder reorders the list`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-Field Reorder" })
        val first = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_19
                businessDefinition = "Erstes"; requirement = B2bFieldRequirement.OPTIONAL; sortOrder = 10
            },
        )
        val second = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_56
                businessDefinition = "Zweites"; requirement = B2bFieldRequirement.OPTIONAL; sortOrder = 20
            },
        )
        em.flush(); em.clear()
        assertEquals(listOf(first.id, second.id), b2bProfileFieldDefinitionService.findFields(profile.id!!).map { it.id })

        // reorder: swap sortOrder values
        val reloadedFirst = b2bProfileFieldDefinitionService.findFields(profile.id!!).first { it.id == first.id }
        val reloadedSecond = b2bProfileFieldDefinitionService.findFields(profile.id!!).first { it.id == second.id }
        reloadedFirst.sortOrder = 30
        reloadedSecond.sortOrder = 5
        b2bProfileFieldDefinitionService.saveField(reloadedFirst)
        b2bProfileFieldDefinitionService.saveField(reloadedSecond)
        em.flush(); em.clear()

        assertEquals(listOf(second.id, first.id), b2bProfileFieldDefinitionService.findFields(profile.id!!).map { it.id }, "the list must follow the new sortOrder")
    }

    // ── 3./4. Recipient assignment, and profile defaults on recipients ─────────────────────────

    @Test
    @TestTransaction
    fun `create derives the invoice's B2B profile from the recipient's standing default`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-AP9 Default" })
        val s = sender("EP1A1")
        val r = recipient("EP1-AP9-0001", b2bProfile = profile)
        em.flush()

        val invoice = lifecycleService.create(s.id!!, r.id!!)

        assertEquals(profile.id, invoice.b2bProfile?.id, "the invoice adopts the customer's standing default on first assignment")
    }

    @Test
    @TestTransaction
    fun `create leaves the invoice's B2B profile null when the recipient has none`() {
        val s = sender("EP1A2")
        val r = recipient("EP1-AP9-0002") // no b2bProfile
        em.flush()

        val invoice = lifecycleService.create(s.id!!, r.id!!)

        assertNull(invoice.b2bProfile)
    }

    @Test
    @TestTransaction
    fun `three-way contract - manual override survives an unchanged recipient, and a recipient change overwrites it`() {
        val profileA = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-AP9 A" })
        val profileB = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-AP9 B" })
        val profileManual = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-AP9 Manuell" })
        val s = sender("EP1A3")
        val recipientA = recipient("EP1-AP9-0003A", b2bProfile = profileA)
        val recipientB = recipient("EP1-AP9-0003B", b2bProfile = profileB)
        val rate = taxRate("EP1_AP9_STD19")
        em.flush()

        // 1. Derivation on first assignment.
        val invoice = lifecycleService.create(s.id!!, recipientA.id!!)
        addValidItem(invoice, rate)
        assertEquals(profileA.id, invoice.b2bProfile?.id)

        // 2. Manual override while the recipient stays the same.
        val overridden = headerUpdateService.updateHeader(
            headerCommand(invoice.id!!, recipientId = recipientA.id, senderProfileId = s.id, b2bProfileId = profileManual.id),
        )
        assertEquals(profileManual.id, overridden.b2bProfile?.id, "a manual override must take effect while the recipient is unchanged")

        // 3. A recipient change re-derives and overwrites the just-set override.
        val changed = headerUpdateService.updateHeader(
            headerCommand(overridden.id!!, recipientId = recipientB.id, senderProfileId = s.id, b2bProfileId = profileManual.id),
        )
        assertEquals(
            profileB.id, changed.b2bProfile?.id,
            "a recipient change must overwrite even a just-set manual override — otherwise the old customer's catalog would silently survive",
        )
    }

    @Test
    @TestTransaction
    fun `updateHeader clears the B2B profile back to null when b2bProfileId is null`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().apply { name = "EP1-AP9 Clear" })
        val s = sender("EP1A4")
        val r = recipient("EP1-AP9-0004", b2bProfile = profile)
        val rate = taxRate("EP1_CLR_STD19")
        em.flush()

        val invoice = lifecycleService.create(s.id!!, r.id!!)
        addValidItem(invoice, rate)
        assertEquals(profile.id, invoice.b2bProfile?.id)

        val cleared = headerUpdateService.updateHeader(
            headerCommand(invoice.id!!, recipientId = r.id, senderProfileId = s.id, b2bProfileId = null),
        )

        assertNull(cleared.b2bProfile, "b2bProfileId = null must clear an override back to no profile")
    }
}
