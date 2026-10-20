package de.littletrotter.e2e.monkey.profile

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.application.reference.B2bProfileUsageService
import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.anInvoice

import de.littletrotter.domain.compliance.ComplianceEvaluator
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.BuyerReferenceMissingException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
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
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.monkey.support.HostileStrings
import de.littletrotter.e2e.monkey.support.MonkeyRun
import de.littletrotter.e2e.profile.ComplianceBlockStageProfile
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermLevel
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The profile monkey: a bounded, seeded random walk interleaving B2B catalog/profile
 * operations (add/delete/reactivate fields, change requirement/placement, switch a recipient's
 * standing profile default) with invoice drafting/finalization against a real PostgreSQL
 * ([PostgresTestResource], this module's own fixture).
 * [ComplianceBlockStageProfile] (this module's own `B2bProfileE2EIT` override, reused as-is now
 * that both live in kotlin-e2e-tests) switches on `littletrotter.compliance-rules.stage=BLOCK` —
 * without it a B2B compliance finding only ever gets logged, never blocks, and "finalized
 * invoices always pass with 0 ERRORs" would be true by construction rather than by anything this
 * walk actually exercised.
 *
 * Run bounded/seeded via `-Dmonkey.seed=<n> -Dmonkey.steps=<n>`; `make monkey SUITE=profile
 * SEED=n` wraps exactly this invocation (root Makefile's `monkey` target).
 *
 * Tagged `monkey` and excluded from the default failsafe run — see kotlin-e2e-tests/pom.xml's
 * `failsafe.excludedGroups`. Named `ProfileMonkeyE2EIT` for the same reason
 * `ConcurrentCrudStressE2EIT` is: this module's own structural guard,
 * [de.littletrotter.e2e.E2eModuleStructureTest], requires every `@Test`-bearing file here to end
 * in `E2EIT`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
@Tag("monkey")
class ProfileMonkeyE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var b2bProfileLifecycleService: B2bProfileLifecycleService

    @Inject
    lateinit var b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    @Inject
    lateinit var b2bProfileUsageService: B2bProfileUsageService

    @Inject
    lateinit var b2bProfileRuleSet: B2bProfileRuleSet

    private data class Fixture(val senderId: Long, val recipientIds: List<Long>, val taxRateId: Long)

    private fun buildFixture(tag: String): Fixture {
        val sender = aSenderProfile {
            name = "Monkey Sender $tag GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Affenweg 1", "10115", "Berlin", Country.DE, true))
        em.persist(sender)

        val recipientIds = (1..2).map { n ->
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-20$n")
                name = "Monkey Recipient $tag-$n AG"
                email = Email.ofNullable("ap$n@$tag.example")
                vatId = VatId("DE333333333")
                routingId = "04011000-$tag$n-07"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Bananenallee $n", "20095", "Hamburg", Country.DE, true))
            em.persist(recipient)
            recipient.id!!
        }

        val taxRate = TaxRate().edit {
            code = "${tag}_MONKEY_STD"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)
        em.flush()
        return Fixture(sender.id!!, recipientIds, taxRate.id!!)
    }

    /** The placement(s) [level] actually allows (mirrors
     *  `B2bProfileField.assertPlacementCompatibleWithLevel` exactly — same split the UI's own
     *  `B2bFieldEditorPanel.syncPlacementForTerm` narrows the picker to, which is the only way a
     *  real anwender can reach this state): LINE/BUYER_PARTY-level terms only ever accept
     *  INVOICE; SELLER_PARTY-level terms only ever accept SELLER (this level exists only for the
     *  seller's own mandatory self-service fields, not a freely placeable term, so it is *not*
     *  grouped with HEADER); HEADER-level terms accept any of the four. */
    private fun allowedPlacements(level: B2bTermLevel): List<B2bPlacement> = when (level) {
        B2bTermLevel.HEADER -> B2bPlacement.entries
        B2bTermLevel.SELLER_PARTY -> listOf(B2bPlacement.SELLER)
        B2bTermLevel.LINE, B2bTermLevel.BUYER_PARTY -> listOf(B2bPlacement.INVOICE)
    }

    /** A placement [level] does accept — used to generate mostly-valid combinations. */
    private fun compatiblePlacement(level: B2bTermLevel, random: kotlin.random.Random): B2bPlacement =
        allowedPlacements(level).random(random)

    /** A placement [level] never accepts — used for the walk's own deliberate-corruption path
     *  ("legal and illegal" combinations). HEADER-level terms accept every placement, so there is
     *  no incompatible one for them; `null` tells the caller to fall back to a compatible pick
     *  instead of fabricating a combination the real domain invariant would never reject (which
     *  would silently defeat the "deliberately corrupted" bookkeeping: this
     *  helper used to hardcode [B2bPlacement.SELLER] as "the" incompatible placement, which
     *  stopped being true once SELLER_PARTY-level terms also came to only accept SELLER). */
    private fun incompatiblePlacement(level: B2bTermLevel, random: kotlin.random.Random): B2bPlacement? {
        val disallowed = B2bPlacement.entries - allowedPlacements(level).toSet()
        return disallowed.randomOrNull(random)
    }

    // ─── The random walk ──────────────────────────────────────────────────────────────────────

    @Test
    fun `random profile-catalog walk interleaved with invoice drafting holds the B2B compliance invariant after every step`() {
        val run = MonkeyRun("profile-monkey")
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { buildFixture("PM") }

        val profileIds = mutableListOf<Long>()
        val invoiceIds = mutableListOf<Long>()

        repeat(run.steps) { i ->
            run.step(i, "action=${i % 7}") {
                when (i % 7) {
                    // 0 — create a fresh (initially empty) profile.
                    0 -> {
                        val name = "Monkey-Profil ${run.random.nextInt(1_000_000)}"
                        val id = QuarkusTransaction.requiringNew().call<Long> {
                            b2bProfileLifecycleService.saveProfile(B2bProfile().edit { this.name = name; description = "monkey" }).id!!
                        }
                        profileIds += id
                        run.log("created profile $id ('$name')")
                    }

                    // 1 — add a random catalog field, requirement, and (mostly-)compatible placement.
                    1 -> {
                        val profileId = profileIds.randomOrNull(run.random) ?: return@step
                        val term = QuarkusTransaction.requiringNew().call<B2bTerm?> {
                            b2bProfileFieldDefinitionService.findSelectableCatalogEntries(profileId).filter { it.binding != null }.randomOrNull(run.random)
                        } ?: return@step
                        // See incompatiblePlacement's KDoc: a HEADER-level term has no
                        // incompatible placement to pick, so `chosenIncompatible` can come back
                        // null even when the coin flip asked for one — deliberatelyIncompatible
                        // tracks what was actually used, not just what was attempted.
                        val chosenIncompatible = if (run.random.nextInt(6) == 0) incompatiblePlacement(term.level, run.random) else null
                        val placement = chosenIncompatible ?: compatiblePlacement(term.level, run.random)
                        val deliberatelyIncompatible = chosenIncompatible != null
                        val requirement = B2bFieldRequirement.entries.filter { it != B2bFieldRequirement.CONDITIONAL && it != B2bFieldRequirement.CONDITIONAL_IF_EMPTY }
                            .random(run.random)
                        // HostileStrings' own pool deliberately includes blank/whitespace-only
                        // samples (a legitimate edge case to feed a free-text field); captured
                        // once so the catch block below can tell "field.validate() correctly
                        // rejected an accidentally-blank businessDefinition" apart from a genuine
                        // unexpected rejection, instead of re-drawing (and desyncing the walk's
                        // own random-call bookkeeping for this step) or re-deriving blankness from
                        // HostileStrings internals here.
                        val businessDefinition = if (run.random.nextInt(5) == 0) HostileStrings.random(run.random) else "monkey field"
                        try {
                            QuarkusTransaction.requiringNew().run {
                                b2bProfileFieldDefinitionService.saveField(
                                    B2bProfileField().edit {
                                        profile = em.getReference(B2bProfile::class.java, profileId)
                                        this.term = term
                                        this.requirement = requirement
                                        this.placement = placement
                                        this.businessDefinition = businessDefinition
                                    },
                                )
                            }
                            run.log("added field ${term.officialCode} (req=$requirement, placement=$placement) to profile $profileId")
                        } catch (ex: InvalidParameterException) {
                            assertTrue(
                                deliberatelyIncompatible || requirement != B2bFieldRequirement.OPTIONAL || businessDefinition.isBlank(),
                                "an unexpected rejection for a combination the walk did not deliberately corrupt: $ex",
                            )
                            run.log("saveField correctly rejected (${ex.message})")
                        }
                    }

                    // 2 — delete a random field from a random profile.
                    2 -> {
                        val profileId = profileIds.randomOrNull(run.random) ?: return@step
                        val fieldId = QuarkusTransaction.requiringNew().call<Long?> {
                            b2bProfileFieldDefinitionService.findFields(profileId).randomOrNull(run.random)?.id
                        } ?: return@step
                        QuarkusTransaction.requiringNew().run { b2bProfileFieldDefinitionService.deleteField(fieldId) }
                        run.log("deleted field $fieldId from profile $profileId")
                    }

                    // 3 — change requirement/placement of an existing field.
                    3 -> {
                        val profileId = profileIds.randomOrNull(run.random) ?: return@step
                        try {
                            QuarkusTransaction.requiringNew().run {
                                val field = b2bProfileFieldDefinitionService.findFields(profileId).randomOrNull(run.random) ?: return@run
                                val term = requireNotNull(field.term)
                                field.edit {
                                    requirement = B2bFieldRequirement.entries
                                        .filter { it != B2bFieldRequirement.CONDITIONAL && it != B2bFieldRequirement.CONDITIONAL_IF_EMPTY }
                                        .random(run.random)
                                }
                                field.edit { placement = compatiblePlacement(term.level, run.random) }
                                b2bProfileFieldDefinitionService.saveField(field)
                            }
                            run.log("changed requirement/placement on profile $profileId")
                        } catch (ex: InvalidParameterException) {
                            run.log("requirement/placement change correctly rejected (${ex.message})")
                        }
                    }

                    // 4 — reactivate/deactivate a profile (the profile-
                    // level equivalent this catalog exposes: `B2bProfile.active`).
                    4 -> {
                        val profileId = profileIds.randomOrNull(run.random) ?: return@step
                        QuarkusTransaction.requiringNew().run {
                            val profile = em.find(B2bProfile::class.java, profileId) ?: return@run
                            profile.edit { active = !profile.active }
                            b2bProfileLifecycleService.saveProfile(profile)
                        }
                        run.log("toggled active flag on profile $profileId")
                    }

                    // 5 — switch a recipient's standing profile default, then create/draft an
                    // invoice for it and add one item, interleaved with invoice drafting.
                    5 -> {
                        val recipientId = fixture.recipientIds[run.random.nextInt(fixture.recipientIds.size)]
                        val profileId = profileIds.randomOrNull(run.random)
                        QuarkusTransaction.requiringNew().run {
                            val recipient = em.find(Recipient::class.java, recipientId)!!
                            recipient.edit { b2bProfile = profileId?.let { em.getReference(B2bProfile::class.java, it) } }
                        }
                        run.log("recipient $recipientId standing profile default -> $profileId")

                        val invoiceId = QuarkusTransaction.requiringNew().call<Long> {
                            lifecycleService.create(fixture.senderId, recipientId).id!!
                        }
                        try {
                            QuarkusTransaction.requiringNew().run {
                                itemMutationService.addItem(
                                    invoiceId,
                                    InvoiceItemCommand(
                                        category = InvoiceItemCategory.SERVICE,
                                        description = "monkey service",
                                        quantity = BigDecimal("2"),
                                        unit = InvoiceItemUnit.HOUR,
                                        unitPriceNet = Money.of("80.00"),
                                        taxRateId = fixture.taxRateId,
                                    ),
                                )
                            }
                            invoiceIds += invoiceId
                            run.log("drafted invoice $invoiceId for recipient $recipientId (profile $profileId)")
                        } catch (ex: InvoiceImmutableException) {
                            run.log("addItem rejected unexpectedly for a fresh draft: $ex")
                            throw ex
                        }
                        assertB2bRuleSetNeverThrows(invoiceId)
                    }

                    // 6 — attempt to finalize a random open invoice; under BLOCK stage this must
                    // fail loudly (not silently succeed) whenever an unmet MANDATORY field exists,
                    // and must leave 0 ERROR compliance findings behind whenever it *does* succeed.
                    6 -> {
                        val invoiceId = invoiceIds.randomOrNull(run.random) ?: return@step
                        val status = QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, invoiceId)!!.status }
                        if (status != InvoiceStatus.DRAFT) return@step
                        try {
                            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
                            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
                            run.log("finalized invoice $invoiceId")
                            assertFinalizedInvoiceHasZeroComplianceErrors(invoiceId)
                        } catch (ex: BuyerReferenceMissingException) {
                            run.log("finalize correctly rejected (missing BT-10)")
                        } catch (ex: RuntimeException) {
                            // BLOCK-stage compliance/gate rejections surface as unchecked domain
                            // exceptions from InvoiceFinalizationBlockedException's own family —
                            // an expected, legal rejection under this profile's BLOCK override.
                            run.log("finalize correctly rejected under BLOCK stage: ${ex::class.simpleName} ${ex.message}")
                        }
                    }
                }
            }
        }

        invoiceIds.distinct().forEach { assertB2bRuleSetNeverThrows(it) }
        println("[profile-monkey] finished ${run.steps} steps — ${profileIds.size} profiles, ${invoiceIds.size} invoices, invariant held throughout")
    }

    /** Invariant — "compliance evaluation never throws unexpectedly": evaluating an
     *  arbitrary invoice's B2B findings must always return a (possibly non-empty) list, never
     *  throw, regardless of how mangled the profile/field/value state the random walk built. */
    private fun assertB2bRuleSetNeverThrows(invoiceId: Long) {
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId) ?: return@run
            b2bProfileRuleSet.evaluate(invoice)
        }
    }

    /** Invariant — "finalized invoices always pass ComplianceEvaluator with 0 ERRORs":
     *  combines the default rule sets with [B2bProfileRuleSet] exactly like
     *  `FinalizationPolicy.enforceComplianceRules` does for the real BLOCK-stage gate this
     *  profile activates — a finalized invoice must never carry an ERROR-severity finding once
     *  it got there. */
    private fun assertFinalizedInvoiceHasZeroComplianceErrors(invoiceId: Long) {
        QuarkusTransaction.requiringNew().run {
            val invoice = requireNotNull(em.find(Invoice::class.java, invoiceId))
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "must actually be finalized for this check to mean anything")
            val findings = ComplianceEvaluator.evaluate(invoice, E2eBusinessDay.today()) + b2bProfileRuleSet.evaluate(invoice)
            val errors = findings.filter { it.severity == ComplianceSeverity.ERROR }
            assertTrue(errors.isEmpty(), "an ISSUED invoice must never carry an ERROR-severity compliance finding: $errors")
        }
    }

    // ─── Regression pin — SEED=19833422976000 STEPS=150 step 22 (profile-monkey-seller-party-
    // placement) ─────────────────────────────────────────────────────────────────────────────

    /**
     * Pins the step-22 finding (`make monkey SUITE=profile SEED=19833422976000 STEPS=150`):
     * `compatiblePlacement` used to group [B2bTermLevel.SELLER_PARTY] with [B2bTermLevel.HEADER]
     * (any of the four placements "compatible"), which stopped matching
     * `B2bProfileField.assertPlacementCompatibleWithLevel` once SELLER_PARTY-level terms were
     * fixed to [B2bPlacement.SELLER] only. Root cause: defect
     * class 3 — the monkey generator building a call the real UI
     * (`B2bFieldEditorPanel.syncPlacementForTerm`) can never construct, not a wrong domain
     * invariant (kotlin-invoice's `Ep6FieldCombinationMatrixTest`/`Ep6ConfigRejectionTest`
     * already pin SELLER_PARTY-only-SELLER as intentional) and not a UI gap (that same UI method
     * already narrows the placement picker to SELLER for a SELLER_PARTY term). Deterministic —
     * exhaustive over every [B2bTermLevel] rather than relying on a lucky seed to hit the one
     * previously-mishandled level.
     */
    @Test
    fun `compatiblePlacement never offers a placement the domain invariant would reject`() {
        val random = kotlin.random.Random(19833422976000L)
        B2bTermLevel.entries.forEach { level ->
            repeat(50) {
                val placement = compatiblePlacement(level, random)
                try {
                    B2bProfileField.assertPlacementCompatibleWithLevel(placement, level)
                } catch (ex: InvalidParameterException) {
                    throw AssertionError(
                        "compatiblePlacement($level) offered $placement, which the domain invariant rejects: $ex",
                    )
                }
            }
        }
    }

    /** The exact combination the step-22 finding needed fixed: a [B2bTermLevel.SELLER_PARTY]
     *  term is only ever offered [B2bPlacement.SELLER] now — this is what silently changed out
     *  from under the old `compatiblePlacement` (it used to return any of the four). */
    @Test
    fun `allowedPlacements pins the SELLER_PARTY-only-SELLER combination from the step-22 finding`() {
        assertEquals(listOf(B2bPlacement.SELLER), allowedPlacements(B2bTermLevel.SELLER_PARTY))
    }

    /** [incompatiblePlacement]'s counterpart pin: it must only ever hand back a placement the
     *  domain invariant genuinely rejects (never accidentally the one legal placement for the
     *  level), and — since [B2bTermLevel.HEADER] accepts all four — `null` for HEADER only. */
    @Test
    fun `incompatiblePlacement never returns a placement the domain invariant would accept, and is null only for HEADER`() {
        B2bTermLevel.entries.forEach { level ->
            val random = kotlin.random.Random(1L)
            repeat(20) {
                val incompatible = incompatiblePlacement(level, random)
                if (level == B2bTermLevel.HEADER) {
                    assertEquals(null, incompatible)
                } else {
                    assertTrue(incompatible != null, "expected an incompatible placement for $level")
                    assertThrows(InvalidParameterException::class.java) {
                        B2bProfileField.assertPlacementCompatibleWithLevel(incompatible!!, level)
                    }
                }
            }
        }
    }

    // ─── Probe defect ──────────────────────────────────────────────────────

    /**
     * Acceptance: "demonstrably catches a seeded artificial defect". Never touches
     * production code — instead this bypasses [InvoiceLifecycleService.transition]'s own BLOCK
     * gate entirely (direct [EntityManager] persistence, the same "what if it slipped through"
     * shortcut `RG3-F1` already uses in kotlin-e2e-tests) to build an ISSUED invoice whose
     * assigned profile has an unmet MANDATORY field — the exact defect shape the walk's own
     * [assertFinalizedInvoiceHasZeroComplianceErrors] checks for after every finalize. That
     * invariant must — and does — report an ERROR for it, proving the monkey would have caught
     * this had the real gate ever let it through.
     */
    @Test
    fun `probe defect - a finalized invoice with an unmet MANDATORY B2B field is caught by the same invariant the walk runs after every finalize`() {
        val invoiceId = QuarkusTransaction.requiringNew().call<Long> {
            val sender = aSenderProfile {
                name = "Probe Sender GmbH"; invoicePrefix = "PROBE-"; registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@probe.example"); taxNumber = TaxNumber("12/345/67890"); vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE02120300000000202051")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Affenweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("PROBE-1"); name = "Probe Recipient AG"; email = Email.ofNullable("ap@probe.example")
                vatId = VatId("DE222222222"); routingId = "04011000-PROBE-06"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Bananenallee 1", "20095", "Hamburg", Country.DE, true))
            val profile = B2bProfile().edit { name = "Probe-Profil ${System.nanoTime()}"; description = "probe" }
            em.persist(sender); em.persist(recipient); em.persist(profile); em.flush()
            // BT-11 (project reference), MANDATORY, HEADER placement — deliberately never filled
            // on the invoice below (the seeded defect: production's own gate would refuse to
            // finalize this; this probe skips straight past that gate to see if the read-side
            // invariant still notices).
            em.persist(
                B2bProfileField().edit {
                    this.profile = profile; term = B2bTerm.BT_11; requirement = B2bFieldRequirement.MANDATORY
                    placement = B2bPlacement.INVOICE
                },
            )
            recipient.edit { b2bProfile = profile }

            val taxRate = TaxRate().edit {
                code = "PROBE_STD"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
            }
            em.persist(taxRate)

            val invoice = anInvoice(status = InvoiceStatus.ISSUED).edit {
                senderProfile = sender; this.recipient = recipient; this.b2bProfile = profile
                invoiceDate = LocalDate.of(2026, 2, 10); dueDate = LocalDate.of(2026, 3, 12)
                references.buyerReference = "PO-PROBE-1"
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("PROBE-2026-0001")
            }
            invoice.addInitialItems(
                listOf(
                    anInvoiceItem().edit {
                        positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "probe"
                        quantity = BigDecimal.ONE; unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("100.00")
                        sortOrder = 0
                    }.also { it.applyTaxRule(taxRate) },
                ),
            )
            em.persist(invoice); em.flush()
            invoice.id!!
        }

        // QuarkusTransaction wraps whatever the block throws (here, the AssertionError below) in
        // its own QuarkusTransactionException on rollback — walk the cause chain rather than
        // assert on the outermost type, same idiom as InvoiceMonkeyE2EIT's concurrency check.
        val outer = assertThrows(Throwable::class.java) { assertFinalizedInvoiceHasZeroComplianceErrors(invoiceId) }
        val assertionError = generateSequence(outer) { it.cause }.filterIsInstance<AssertionError>().firstOrNull()
        assertTrue(assertionError != null, "expected an AssertionError somewhere in the cause chain of: $outer")
        assertTrue(
            assertionError?.message?.contains("ERROR-severity") == true,
            "expected the profile monkey's own compliance-error invariant message, got: ${assertionError?.message}",
        )
    }
}

private fun <T> List<T>.randomOrNull(random: kotlin.random.Random): T? = if (isEmpty()) null else this[random.nextInt(size)]
