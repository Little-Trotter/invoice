package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.domain.compliance.B2bProfileRules
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
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
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.system.ComplianceBlockStageProfile
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * Executable proof that a "Nekar"-profile configuration is actually buildable through the real,
 * CDI-wired services against a real Postgres ([PostgresTestResource]), not just a paper design:
 * [B2bProfileLifecycleService.copy] from a plain baseline profile, then the Nekar-specific fields
 * layered on top, then the four runtime assertions this configuration must satisfy.
 *
 * **BT-19 is `MANDATORY`, not a `CONDITIONAL_IF_EMPTY` pair with BT-133** — this is a pragmatic
 * fallback. [Ep6ConfigRejectionTest
 * .NekarBt19Bt133CrossLevelXorPin] and [de.littletrotter.application.invoice.B2bProfileRuleSetTest]
 * probed the cross-level pair empirically: [B2bTerm.BT_133] is a pure
 * reference term (`binding == null` — the "BT-133 line fanout"
 * [de.littletrotter.xpdf.impl.Bt19HeaderInjector] performs is a post-processing *duplication* of
 * the captured BT-19 value, never an independently captured BT-133 value), so `BT_133` as
 * `CONDITIONAL_IF_EMPTY` is rejected at config time outright, and the reverse direction (`BT_19`
 * `CONDITIONAL_IF_EMPTY` on `BT_133`) is accepted but never actually resolvable — BT-133's
 * `hasValue` is `false` unconditionally, so the "if-empty" condition never closes and BT-19 ends up
 * required regardless. Declaring it plain `MANDATORY` here is the honest equivalent of that
 * degenerate behavior, not a downgrade.
 *
 * **BT-84 `MANDATORY`, `SELLER` placement, is documentation-only reinforcement** — a further,
 * related finding surfaced while building this profile: a `SELLER`-placed field is filtered out of
 * [B2bProfileRuleSet.evaluate] (placement scope: `INVOICE`/`BOTH`/`RECIPIENT` only) *and* out of
 * [B2bProfileFieldDefinitionService.missingMandatorySellerFields] (`binding == FREE` only — BT-84 is `BOUND` to
 * `SenderProfile.iban`). Neither B2B-specific mechanism ever actually checks it. The IBAN
 * requirement is enforced regardless, by the always-on, profile-independent
 * [de.littletrotter.domain.compliance.SellerDataRules.IBAN] completeness check (`CORE.SELLER_IBAN`)
 * every invoice already carries — proven below by the finding's `ruleId`, not by
 * [B2bProfileRules.MANDATORY_FIELD].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
class NekarProfileConfigIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var b2bProfileLifecycleService: B2bProfileLifecycleService

    @Inject
    lateinit var b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    @Inject
    lateinit var b2bProfileRuleSet: B2bProfileRuleSet

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    private data class NekarFixture(val profile: B2bProfile, val senderId: Long, val recipientId: Long, val taxRateId: Long)

    // ── Fixtures — same completeness idiom as B2bProfileCrudIT/B2bInvoiceE2EIT ────────

    private fun sender(prefix: String, withIban: Boolean = true): SenderProfile = aSenderProfile {
        name = "Nekar Sender $prefix"
        invoicePrefix = prefix
        registerCourt = ""
        email = Email.ofNullable("$prefix@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        phone = "+49 30 1234567"
        if (withIban) iban = Iban.ofNullable("DE02120300000000202051")
    }.also {
        it.addAddress(Address.forSender(it, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        em.persist(it)
    }

    private fun recipient(customerNumber: String, b2bProfile: B2bProfile? = null): Recipient = aRecipient {
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        name = "Nekar Recipient $customerNumber"
        email = Email.ofNullable("$customerNumber@buyer.example")
        vatId = VatId("DE987654321")
        // BR-DE-15 — InvoiceLifecycleService.transition enforces BT-10 on every finalization
        // attempt regardless of any B2B profile; the fixture needs one so a Nekar-specific
        // finding is what blocks (or doesn't), not this unrelated, always-on check.
        routingId = "PO-$customerNumber-STANDING"
        this.b2bProfile = b2bProfile
    }.also {
        it.addAddress(Address.forRecipient(it, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(it)
    }

    private fun taxRate(code: String): TaxRate = TaxRate().edit {
        this.code = code
        category = "S"
        name = "VAT 19"
        rate = TaxRatePercent(BigDecimal("19.00"))
        isDefault = false
    }.also { em.persist(it) }

    private fun itemCommand(taxRateId: Long, description: String = "Beratung") = InvoiceItemCommand(
        category = InvoiceItemCategory.SERVICE,
        description = description,
        quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.HOUR,
        unitPriceNet = Money.of("100.00"),
        taxRateId = taxRateId,
    )

    /**
     * Builds the profile per the coverage doc's section-4 table, starting from a plain baseline
     * profile and [B2bProfileLifecycleService.copy] (the real mechanism, not a hand-rolled clone) —
     * the same building block [de.littletrotter.ui.view.referencedata.B2bProfilesTab.copyProfile]
     * uses in the UI. Returns the copy with every Nekar field attached.
     */
    private fun buildNekarProfile(): B2bProfile {
        val baseline = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "DK-Baseline ${System.nanoTime()}" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = baseline; term = B2bTerm.BT_56
                businessDefinition = "Käufer-Kontaktperson (Baseline)"; requirement = B2bFieldRequirement.OPTIONAL
            },
        )

        val nekar = b2bProfileLifecycleService.copy(baseline.id!!)
        assertEquals(1, b2bProfileFieldDefinitionService.findFields(nekar.id!!).size, "the copy must carry the baseline field over before Nekar's own fields are added")

        // BT-84 — Hausbank-IBAN, MANDATORY, SELLER placement (SELLER_PARTY level is fixed to it).
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_84
                businessDefinition = "IBAN, vorrangig Hausbank"; requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.SELLER
            },
        )
        // BT-19 — Kostenstelle, MANDATORY (pragmatic fallback, see class KDoc).
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_19
                businessDefinition = "Buchungsreferenz Kostenstelle"; requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.INVOICE
            },
        )
        // BT-13 — Nekar-Bestellnummer, OPTIONAL, the trigger BT-155/BT-156 condition on.
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_13
                businessDefinition = "Nekar Bestellnummer"; requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.INVOICE
            },
        )
        // BT-155/BT-156 — CONDITIONAL on BT-13 (LINE level, forced to INVOICE placement).
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_155
                businessDefinition = "Nekar Artikelbezeichnung"; requirement = B2bFieldRequirement.CONDITIONAL
                conditionTerm = B2bTerm.BT_13
            },
        )
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_156
                businessDefinition = "Nekar Artikelnummer"; requirement = B2bFieldRequirement.CONDITIONAL
                conditionTerm = B2bTerm.BT_13
            },
        )
        // BT-58 — rechnung@nekar-supermarkt.de, MANDATORY, RECIPIENT placement (the value itself
        // is captured per-recipient below, not on the profile field).
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_58
                businessDefinition = "Interne Prozesszuordnung"; requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.RECIPIENT
            },
        )
        // BT-12/BT-18 — OPTIONAL, "sofern bei Bestellung mitgeteilt".
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_12
                businessDefinition = "Vertragsnummer"; requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.INVOICE
            },
        )
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                profile = nekar; term = B2bTerm.BT_18
                businessDefinition = "Innenauftrag"; requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.INVOICE
            },
        )
        return nekar
    }

    // ── 1. The profile validates/saves ───────────────────────────────────────────────────────

    @Test
    fun `the Nekar profile builds via copy and saveField without any rejection, all eight fields land correctly`() {
        val nekar = QuarkusTransaction.requiringNew().call { buildNekarProfile() }

        val fields = b2bProfileFieldDefinitionService.findFields(nekar.id!!).associateBy { it.term }
        assertEquals(9, fields.size, "1 copied baseline field + 8 Nekar fields (BT-84/19/13/155/156/58/12/18)")
        assertEquals(B2bFieldRequirement.MANDATORY, fields.getValue(B2bTerm.BT_84).requirement)
        assertEquals(B2bPlacement.SELLER, fields.getValue(B2bTerm.BT_84).placement)
        assertEquals(B2bFieldRequirement.MANDATORY, fields.getValue(B2bTerm.BT_19).requirement)
        assertEquals(B2bFieldRequirement.CONDITIONAL, fields.getValue(B2bTerm.BT_155).requirement)
        assertEquals(B2bTerm.BT_13, fields.getValue(B2bTerm.BT_155).conditionTerm)
        assertEquals(B2bFieldRequirement.CONDITIONAL, fields.getValue(B2bTerm.BT_156).requirement)
        assertEquals(B2bTerm.BT_13, fields.getValue(B2bTerm.BT_156).conditionTerm)
        assertEquals(B2bPlacement.RECIPIENT, fields.getValue(B2bTerm.BT_58).placement)
        assertEquals(B2bFieldRequirement.OPTIONAL, fields.getValue(B2bTerm.BT_12).requirement)
        assertEquals(B2bFieldRequirement.OPTIONAL, fields.getValue(B2bTerm.BT_18).requirement)
    }

    // ── 2. Assignment to a recipient derives onto a new invoice ─────────────────────────────────

    @Test
    fun `assigning the Nekar profile to a recipient derives it onto a newly created invoice`() {
        val (nekariId, senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val nekar = buildNekarProfile()
            val s = sender("DKA1")
            val r = recipient("DK-ASSIGN-0001", b2bProfile = nekar)
            Triple(nekar.id!!, s.id!!, r.id!!)
        }

        val invoice = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId) }

        assertEquals(nekariId, invoice.b2bProfile?.id, "the invoice adopts the recipient's standing Nekar profile on first assignment (AP-9 rule 1)")
    }

    // ── 3. Missing BT-84 blocks finalization ────────────────────────────────────────────────────

    @Test
    fun `missing BT-84 (Hausbank-IBAN) blocks finalization via the always-on CORE_SELLER_IBAN check, not the B2B rule set`() {
        val fixture = QuarkusTransaction.requiringNew().call {
            val d = buildNekarProfile()
            val s = sender("DKB1", withIban = false) // the seeded defect this test targets
            val r = recipient("DK-IBAN-0001", b2bProfile = d)
            val t = taxRate("DKB1_STD19")
            NekarFixture(d, s.id!!, r.id!!, t.id!!)
        }
        b2bFieldValueService.saveRecipientFieldValues(fixture.recipientId, fixture.profile.id!!, mapOf("BT-58" to "rechnung@nekar-supermarkt.de"))

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(fixture.senderId, fixture.recipientId).id!! }
        QuarkusTransaction.requiringNew().run { itemMutationService.addItem(invoiceId, itemCommand(fixture.taxRateId)) }
        // D-R1 (CORE.SERVICE_PERIOD_XOR) — not this test's concern, satisfied cheaply with BT-72.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 8, 1) } }
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-19" to "KST-NEKAR-1")) // BT-19 MANDATORY satisfied

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        }

        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, ex.reason)
        assertTrue(
            ex.findings.any { it.ruleId == "CORE.SELLER_IBAN" },
            "IBAN missing must block via the always-on completeness check: ${ex.findings}",
        )
        assertTrue(
            ex.findings.none { it.ruleId == B2bProfileRules.MANDATORY_FIELD },
            "confirms finding D-P1-adjacent: BT-84 is BOUND and SELLER-placed, so neither " +
                "B2bProfileRuleSet (placement filter) nor missingMandatorySellerFields (FREE-only " +
                "filter) ever evaluates it — the profile's own MANDATORY flag on BT-84 never " +
                "fires by itself: ${ex.findings}",
        )
    }

    // ── 4. The BT-13 → BT-155/BT-156 chain fires ────────────────────────────────────────────────

    @Test
    fun `BT-13 filled makes BT-155 and BT-156 mandatory on every line, matching the directive's Pflichtkette`() {
        val fixture = QuarkusTransaction.requiringNew().call {
            val d = buildNekarProfile()
            val s = sender("DKC1")
            val r = recipient("DK-CHAIN-0001", b2bProfile = d)
            val t = taxRate("DKC1_STD19")
            NekarFixture(d, s.id!!, r.id!!, t.id!!)
        }
        b2bFieldValueService.saveRecipientFieldValues(fixture.recipientId, fixture.profile.id!!, mapOf("BT-58" to "rechnung@nekar-supermarkt.de"))

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(fixture.senderId, fixture.recipientId).id!! }
        QuarkusTransaction.requiringNew().run { itemMutationService.addItem(invoiceId, itemCommand(fixture.taxRateId)) }
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-19" to "KST-NEKAR-2"))

        val withoutOrderNumber = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, invoiceId)!!)
        }
        assertTrue(
            withoutOrderNumber.none { it.businessTerms.firstOrNull() in setOf(B2bTerm.BT_155, B2bTerm.BT_156) },
            "without BT-13 the chain must not fire: $withoutOrderNumber",
        )

        // BT-13 is BOUND to Invoice.references.purchaseOrderReference.
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, invoiceId)!!.edit { references.purchaseOrderReference = "2000325012" }
        }

        val withOrderNumber = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, invoiceId)!!)
        }
        val chainFindings = withOrderNumber.filter { it.businessTerms.firstOrNull() in setOf(B2bTerm.BT_155, B2bTerm.BT_156) }
        assertEquals(2, chainFindings.size, "BT-13 filled must make both BT-155 and BT-156 required on the one line: $withOrderNumber")
        assertTrue(chainFindings.all { it.ruleId == B2bProfileRules.MANDATORY_FIELD })
        assertTrue(chainFindings.all { it.target.toString().contains("1") }, "the findings must hang off the position, not the header: $chainFindings")

        // Filling both closes the chain again.
        b2bFieldValueService.saveFieldValues(
            B2bFieldOwnerType.INVOICE_ITEM,
            QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.itemList.single().id!! },
            mapOf("BT-155" to "Nekar Artikel", "BT-156" to "243538"),
        )
        val closed = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, invoiceId)!!)
        }
        assertTrue(closed.none { it.businessTerms.firstOrNull() in setOf(B2bTerm.BT_155, B2bTerm.BT_156) }, "filling both closes the chain: $closed")
    }
}
