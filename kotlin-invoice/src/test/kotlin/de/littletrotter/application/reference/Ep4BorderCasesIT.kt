package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceHeaderUpdateService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.B2bProfileOptimisticLockTestHelper
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * The profile system's border-case catalog, the
 * IT half: cases that need a real Postgres (repository queries, DB-level constraints, JTA
 * transaction boundaries) but no XML/KoSIT generation — those live in `kotlin-e2e-tests`
 * (`Ep4BorderCasesE2EIT`). The pure/mocked-repository cases live in [Ep4BorderCasesTest].
 *
 * Follows [B2bProfileCrudIT]'s own two idioms side by side: `@TestTransaction` (rollback) for the
 * single-transaction cases, plain `@Test` + a `REQUIRES_NEW` CDI helper bean for the one case that
 * genuinely needs two independent, concurrently-committing transactions (mirrors
 * `InvoiceVersionConflictIT`'s own ARJUNA016053 reproduction for [Invoice]).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class Ep4BorderCasesIT {

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

    @Inject
    lateinit var lockHelper: B2bProfileOptimisticLockTestHelper

    // ── Fixtures — same shape as B2bProfileCrudIT's own (duplicated per this package's
    //    fixture-per-file convention, see NewFieldEndToEndE2EIT's KDoc for the same reasoning) ────

    private fun sender(prefix: String): SenderProfile = aSenderProfile {
        name = "EP-4 Sender $prefix"
        invoicePrefix = prefix
        registerCourt = ""
        email = Email.ofNullable("$prefix@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE02120300000000202051")
    }.also { em.persist(it) }

    private fun recipient(customerNumber: String, b2bProfile: B2bProfile? = null): Recipient = aRecipient {
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        name = "EP-4 Recipient $customerNumber"
        email = Email.ofNullable("$customerNumber@buyer.example")
        vatId = VatId("DE987654321")
        this.b2bProfile = b2bProfile
    }.also { em.persist(it) }

    private fun taxRate(code: String): TaxRate = TaxRate().edit {
        this.code = code
        category = "S"; name = "VAT 19"; rate = TaxRatePercent(BigDecimal("19.00")); isDefault = false
    }.also { em.persist(it) }

    private fun addValidItem(invoice: Invoice, taxRate: TaxRate) {
        invoice.addItem(
            InvoiceItem().edit {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = "Beratung"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
                taxRateRef = taxRate
                sortOrder = 0
            },
        )
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

    // ── Profile switch on a mid-edit draft: the recipient-driven profile re-derivation rule vs. entered values ─────────

    @Test
    @TestTransaction
    fun `mid-edit draft - a recipient CHANGE re-derives the invoice's profile, but the field value entered under the OLD profile is not deleted, only orphaned`() {
        val profileOld = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Switch Old" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { profile = profileOld; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle (alt)"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val profileNew = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Switch New" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { profile = profileNew; term = B2bTerm.BT_56; businessDefinition = "Kontakt (neu)"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val s = sender("EP4SW1")
        val recipientOld = recipient("EP4-SW-OLD", b2bProfile = profileOld)
        val recipientNew = recipient("EP4-SW-NEW", b2bProfile = profileNew)
        val rate = taxRate("EP4SW1_STD19")
        em.flush()

        val invoice = lifecycleService.create(s.id!!, recipientOld.id!!)
        addValidItem(invoice, rate)
        assertEquals(profileOld.id, invoice.b2bProfile?.id)

        // The user is mid-edit: BT-19 is entered while profileOld is still the active one.
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!, mapOf("BT-19" to "KST-ALT-001"))
        assertEquals("KST-ALT-001", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!)["BT-19"])

        // The recipient CHANGES — the profile is re-derived from the new recipient.
        val changed = headerUpdateService.updateHeader(headerCommand(invoice.id!!, recipientId = recipientNew.id, senderProfileId = s.id, b2bProfileId = null))
        assertEquals(profileNew.id, changed.b2bProfile?.id, "AP-9 rule 1: a recipient change re-derives the profile")

        // The BT-19 value survives as a plain, orphaned b2b_field_value row — nothing in
        // updateHeader/saveFieldValues ever cleans up values of a profile no longer assigned.
        assertEquals(
            "KST-ALT-001", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!)["BT-19"],
            "the previously entered value must not be silently deleted by the profile switch",
        )
        // ...but it is now invisible: profileNew never defines BT-19, so the header section for
        // this invoice would never render (or re-submit) it again.
        assertTrue(b2bProfileFieldDefinitionService.findInvoiceHeaderFields(profileNew.id!!).none { it.term == B2bTerm.BT_19 })
    }

    @Test
    @TestTransaction
    fun `mid-edit draft - an UNCHANGED recipient applies the entry pane's own b2bProfileId override, and previously entered values still survive the switch`() {
        val profileOld = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Switch2 Old" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { profile = profileOld; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle (alt)"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val profileManual = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Switch2 Manual" })
        val s = sender("EP4SW2")
        val r = recipient("EP4-SW2-0001", b2bProfile = profileOld)
        val rate = taxRate("EP4SW2_STD19")
        em.flush()

        val invoice = lifecycleService.create(s.id!!, r.id!!)
        addValidItem(invoice, rate)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!, mapOf("BT-19" to "KST-ALT-002"))

        // Recipient stays the SAME id, only the command's own b2bProfileId changes — the rule
        // says this manual override wins over re-derivation.
        val overridden = headerUpdateService.updateHeader(headerCommand(invoice.id!!, recipientId = r.id, senderProfileId = s.id, b2bProfileId = profileManual.id))
        assertEquals(profileManual.id, overridden.b2bProfile?.id)

        assertEquals(
            "KST-ALT-002", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!)["BT-19"],
            "an unchanged-recipient manual override must not clear values entered under the previous profile either",
        )
    }

    // ── Spec case 2a — delete a profile that's a recipient's standing default ─────────────────────

    @Test
    @TestTransaction
    fun `deleteProfile is blocked while ANY recipient still holds it as their standing default, naming every one of them`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Delete InUse" })
        recipient("EP4-DEL-0001", b2bProfile = profile)
        recipient("EP4-DEL-0002", b2bProfile = profile)
        em.flush()

        val ex = assertThrows(InvalidParameterException::class.java) { b2bProfileLifecycleService.deleteProfile(profile.id!!) }
        assertTrue(ex.message?.contains("EP-4 Recipient EP4-DEL-0001") == true, ex.message)
        assertTrue(ex.message?.contains("EP-4 Recipient EP4-DEL-0002") == true, ex.message)
        assertNotNull(b2bProfileLifecycleService.findProfileById(profile.id!!), "a blocked delete must not remove the profile")
    }

    // ── Spec case 2b — finding EP4-F1b: delete while an open draft uses it via a manual override ──

    @Test
    @TestTransaction
    fun `finding EP4-F1b (fixed) - deleteProfile's guard now also checks an invoice's own manual override, rejecting with a readable error instead of a raw FK violation`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Delete Override" })
        val s = sender("EP4DELOV")
        val r = recipient("EP4-DELOV-0001") // no standing default at all
        val rate = taxRate("EP4DELOV_STD19")
        em.flush()

        val invoice = lifecycleService.create(s.id!!, r.id!!)
        addValidItem(invoice, rate)
        headerUpdateService.updateHeader(headerCommand(invoice.id!!, recipientId = r.id, senderProfileId = s.id, b2bProfileId = profile.id))
        em.flush(); em.clear()

        // The RECIPIENT guard's own query still finds nothing here: no RECIPIENT points to this
        // profile as a standing default — only this one invoice's own b2b_profile_id column does
        // (also FK-guarded, V1__baseline_schema.sql: "b2b_profile_id BIGINT REFERENCES b2b_profile
        // (id)", no ON DELETE clause -> NO ACTION). The NEW invoice-usage check is what catches it.
        assertTrue(b2bProfileUsageService.usageNames(profile.id!!).isEmpty(), "sanity: no recipient guard fires here")

        val ex = assertThrows(InvalidParameterException::class.java) { b2bProfileLifecycleService.deleteProfile(profile.id!!) }
        assertTrue(
            ex.message?.contains("individuelle Zuordnung") == true,
            "EP4-F1b fixed: the invoice-usage guard must name itself distinctly from the recipient-standing-default guard: ${ex.message}",
        )
        assertNotNull(b2bProfileLifecycleService.findProfileById(profile.id!!), "a blocked delete must not remove the profile")
    }

    // ── Spec case 6a — concurrency: two sessions editing the same profile ──────────────────────────

    @Test
    fun `concurrency - two sessions editing the same profile simultaneously - exactly one commit wins, the other hits the optimistic-lock guard`() {
        val profileId = lockHelper.createProfile("EP4-Concurrency")

        val loadedLatch = CountDownLatch(2)
        val startSignal = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val f1 = executor.submit(Callable {
            runCatching { lockHelper.loadAndRename(profileId, "Session A's description", loadedLatch, startSignal) }.exceptionOrNull()
        })
        val f2 = executor.submit(Callable {
            runCatching { lockHelper.loadAndRename(profileId, "Session B's description", loadedLatch, startSignal) }.exceptionOrNull()
        })

        assertTrue(loadedLatch.await(10, TimeUnit.SECONDS), "both sessions must load the entity within timeout")
        startSignal.countDown()

        val failures = listOf(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS)).filterNotNull()
        executor.shutdown()

        assertEquals(1, failures.size, "exactly one of the two concurrent saves must fail; the other must succeed: $failures")
        val failure = failures.single()
        val hasOptimisticLock = generateSequence(failure) { it.cause }.any {
            it is jakarta.persistence.OptimisticLockException || it is org.hibernate.StaleObjectStateException
        }
        assertTrue(hasOptimisticLock, "expected an OptimisticLockException/StaleObjectStateException in the cause chain, got: $failure")
    }

    // ── Spec case 6b — "catalog edit while an invoice entry pane is open" ──────────────────────────

    @Test
    @TestTransaction
    fun `catalog edit while a pane is open - removing a field from the profile does not stop a stale, already-rendered submission from persisting an orphaned value`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-PaneRace" })
        val field = b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val s = sender("EP4PANE")
        val r = recipient("EP4-PANE-0001", b2bProfile = profile)
        val rate = taxRate("EP4PANE_STD19")
        em.flush()
        val invoice = lifecycleService.create(s.id!!, r.id!!)
        addValidItem(invoice, rate)

        // "The pane is open": the entry pane has already rendered the BT-19 widget for this
        // invoice's profile field. Concurrently, another session (or tab) edits the CATALOG side
        // of the profile and removes that very field.
        b2bProfileFieldDefinitionService.deleteField(field.id!!)
        assertTrue(b2bProfileFieldDefinitionService.findFields(profile.id!!).none { it.term == B2bTerm.BT_19 }, "sanity: the field is really gone from the profile")

        // The pane still submits its stale widget's value — saveFieldValues never checks whether
        // the term is still part of any profile field at all, so it saves anyway.
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!, mapOf("BT-19" to "KST-STALE-PANE"))

        assertEquals(
            "KST-STALE-PANE", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, invoice.id!!)["BT-19"],
            "a stale pane submission for a since-removed field still persists, orphaned",
        )
    }

    // ── Spec case 7 — deactivated (not deleted) catalog fields ──────────────────────────────────────

    @Test
    @TestTransaction
    fun `finding EP4-F2 (fixed) - deactivating a profile keeps its SELLER-placed fields visible for a recipient still assigned to it, same as HEADER-RECIPIENT-LINE`() {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "EP4-Deactivate"; active = true })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_58; businessDefinition = "Käufer-E-Mail"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.RECIPIENT },
        )
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_56; businessDefinition = "Anbieter-Kontakt"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.SELLER },
        )
        val s = sender("EP4DEACT")
        val r = recipient("EP4-DEACT-0001", b2bProfile = profile)
        val rate = taxRate("EP4DEACT_STD19")
        em.flush()

        // A "historical" invoice: created, item added, and finalization-agnostic — already using
        // the still-active profile, standing in for an existing/historical beleg.
        val invoice = lifecycleService.create(s.id!!, r.id!!)
        addValidItem(invoice, rate)
        assertEquals(profile.id, invoice.b2bProfile?.id)
        assertTrue(b2bProfileFieldDefinitionService.findSellerFieldsUnion().any { group -> group.field.term == B2bTerm.BT_56 }, "sanity: SELLER field visible while the profile is active")

        // Deactivate the profile — nothing about THIS invoice or its recipient changes.
        val toDeactivate = b2bProfileLifecycleService.findProfileById(profile.id!!)!!
        toDeactivate.edit { active = false }
        b2bProfileLifecycleService.saveProfile(toDeactivate)
        em.flush(); em.clear()

        // HEADER (BT-19) and RECIPIENT (BT-58) fields are looked up directly by profileId
        // (findInvoiceHeaderFields/findRecipientFields never filter on B2bProfile.active) — they
        // keep rendering for this already-assigned invoice exactly as before deactivation.
        assertTrue(b2bProfileFieldDefinitionService.findInvoiceHeaderFields(profile.id!!).any { it.term == B2bTerm.BT_19 }, "HEADER field must survive deactivation for an already-assigned invoice")
        assertTrue(b2bProfileFieldDefinitionService.findRecipientFields(profile.id!!).any { it.term == B2bTerm.BT_58 }, "RECIPIENT field must survive deactivation too")

        // The SELLER-placed field stays in findSellerFieldsUnion: the
        // recipient created above is still assigned this now-inactive profile as its standing
        // default, so isProfileReferenced keeps it in the union even though findAllActive() alone
        // would have dropped it the instant it deactivated. "Hidden at creation but preserved on
        // historical invoices" now holds for the SELLER placement too, consistent with
        // HEADER/RECIPIENT/LINE above.
        assertTrue(
            b2bProfileFieldDefinitionService.findSellerFieldsUnion().any { group -> group.field.term == B2bTerm.BT_56 },
            "EP4-F2 (fixed): a SELLER-placed field must survive deactivation for a profile still referenced by a recipient/invoice",
        )
    }
}
