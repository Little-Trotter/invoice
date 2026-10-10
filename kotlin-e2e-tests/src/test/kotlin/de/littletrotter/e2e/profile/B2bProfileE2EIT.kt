package de.littletrotter.e2e.profile

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.application.reference.B2bProfileUsageService
import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.application.invoice.InvoiceLifecycleService

import de.littletrotter.domain.compliance.B2bProfileRules
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
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
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermLevel
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The B2B-profile E2E suite: nine scenarios, each through the real
 * application services at the facade level (no UI driver), mirroring the proven pattern
 * `B2bInvoiceE2EIT` (kotlin-invoice) already established — real [InvoiceLifecycleService]/
 * the B2bProfile*Service family (lifecycle/field-definition/field-value/usage)/
 * [B2bProfileRuleSet], persisted against a real PostgreSQL
 * ([PostgresTestResource]), fixtures built by direct [EntityManager] persistence (the same
 * shortcut `B2bInvoiceE2EIT.persistScenario` takes over `InvoiceLifecycleService.create()` — this
 * suite is not about creation, it is about the profile/rule/finalization strand once an invoice
 * exists). [ComplianceBlockStageProfile] applies the same `littletrotter.compliance-rules.stage
 * =BLOCK` override `B2bInvoiceE2EIT` needs — without it a compliance finding is only logged
 * (`OBSERVE`), never blocks `InvoiceLifecycleService.transition`.
 *
 * Happy paths (1-3) and border cases (4-9) each get their own `tag` prefix
 * (invoicePrefix/customerNumber/taxRate code) — the shared Postgres container is not reset between
 * `@Test` methods, so distinct tags are what keep the scenarios from colliding, exactly like
 * `B2bInvoiceE2EIT`'s own "B2BE"/"B2BC"/"B2BM" tags.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
class B2bProfileE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

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

    // ── 1. Happy path — BOUND terms (one header, one party, one line), all data present ────────

    @Test
    fun `a profile with BOUND terms at header, party and line level passes with zero findings and finalizes`() {
        val profileId = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S1-Profil"; description = "Scenario 1" },
        ).id!!
        // BT-11 (HEADER, BOUND -> Invoice.references.projectNumber), BT-84 (SELLER_PARTY, BOUND ->
        // SenderProfile.iban), BT-129 (LINE, BOUND -> InvoiceItem.quantity) — every one of them
        // already carries a value in the fixture below, so the rule set should find nothing.
        // SELLER_PARTY is only ever legal at SELLER
        // placement (B2bProfileField.assertPlacementCompatibleWithLevel), so BT-84 moves from
        // INVOICE to SELLER here. This also means B2bProfileRuleSet.evaluate (scoped to
        // INVOICE/RECIPIENT placements, per its own class KDoc) no longer even considers
        // BT-84 — a BOUND SELLER-placed field's mandatory-ness is checked separately, at
        // finalization, by FinalizationPolicy.enforceMandatorySellerFields via
        // B2bProfileFieldDefinitionService.missingMandatorySellerFields (which today only flags FREE fields, so a
        // BOUND one like BT-84 never blocks there either) — the sender's IBAN being populated
        // below is no longer load-bearing for this scenario, kept anyway as realistic fixture data.
        addField(profileId, B2bTerm.BT_11, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, 1)
        addField(profileId, B2bTerm.BT_84, B2bFieldRequirement.MANDATORY, B2bPlacement.SELLER, 2)
        addField(profileId, B2bTerm.BT_129, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, 3)

        val ids = QuarkusTransaction.requiringNew().call { persistFixture("S1", profileId) }
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, ids.invoiceId)!!.references.projectNumber = "PRJ-S1-001"
        }

        val findings = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertTrue(findings.isEmpty(), "every BOUND term already carries a value: $findings")

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.SENT) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.SENT, em.find(Invoice::class.java, ids.invoiceId)!!.status)
        }
    }

    // ── 2. Happy path — autoFilled MANDATORY term passes without the user entering anything ────

    @Test
    fun `a profile with an autoFilled MANDATORY term passes without the user entering anything`() {
        assertTrue(B2bTerm.BT_2.autoFilled, "BT-2 (invoice issue date) must be the autoFilled term this scenario exercises")
        val profileId = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S2-Profil"; description = "Scenario 2" },
        ).id!!
        addField(profileId, B2bTerm.BT_2, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, 1)

        // No extra user action anywhere below — invoiceDate is set exactly the way every fixture
        // sets it (persistFixture), never touched again.
        val ids = QuarkusTransaction.requiringNew().call { persistFixture("S2", profileId) }

        val findings = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertTrue(findings.isEmpty(), "the autoFilled BT-2 must already satisfy its own MANDATORY rule: $findings")

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.SENT) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.SENT, em.find(Invoice::class.java, ids.invoiceId)!!.status)
        }
    }

    // ── 3. Happy path — FREE term, RECIPIENT placement, resolved through the profile default ───

    @Test
    fun `a FREE RECIPIENT-placed term with no invoice or recipient value resolves through the profile default`() {
        val profile = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S3-Profil"; description = "Scenario 3" },
        )
        val profileId = profile.id!!
        // BT-58 (buyer contact email, FREE, HEADER) with a profile default and no per-invoice or
        // per-recipient value anywhere — B2bProfileRuleSet.hasFreeValue's resolution chain
        // (invoice value > recipient value > profile default value) must fall all the way through
        // to the default and still report present.
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_58
                businessDefinition = "Kontakt-E-Mail (Standard)"
                requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.RECIPIENT
                defaultValue = "einkauf@s3-recipient.example"
            },
        )

        val ids = QuarkusTransaction.requiringNew().call { persistFixture("S3", profileId) }

        val findings = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertTrue(findings.isEmpty(), "the profile default must satisfy the RECIPIENT-chain resolution: $findings")

        val resolved = QuarkusTransaction.requiringNew().call {
            b2bFieldValueService.resolvedRecipientFieldValues(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertEquals("einkauf@s3-recipient.example", resolved["BT-58"], "the resolved value must be the profile default itself")
    }

    // ── 4. Border case — MANDATORY BOUND term missing blocks; filling it unblocks (re-evaluated) ─

    @Test
    fun `a missing MANDATORY BOUND term blocks finalization, filling it unblocks the same invoice`() {
        val profileId = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S4-Profil"; description = "Scenario 4" },
        ).id!!
        // BT-11 (HEADER, BOUND -> references.projectNumber) is left unset by persistFixture.
        addField(profileId, B2bTerm.BT_11, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, 1)

        val ids = QuarkusTransaction.requiringNew().call { persistFixture("S4", profileId) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.IN_REVIEW) }

        val blocked = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.SENT) }
        }
        assertEquals(1, blocked.findings.count { it.ruleId == B2bProfileRules.MANDATORY_FIELD }, "BT-11 must be the sole B2B finding")
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.IN_REVIEW, em.find(Invoice::class.java, ids.invoiceId)!!.status, "a blocked attempt persists nothing")
        }

        // Fill the datum, re-evaluate the SAME invoice — the finding must be gone and finalization
        // must now succeed.
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, ids.invoiceId)!!.references.projectNumber = "PRJ-S4-001"
        }
        val findingsAfterFill = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertTrue(findingsAfterFill.isEmpty(), "filling BT-11 must clear the finding: $findingsAfterFill")

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.SENT) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.SENT, em.find(Invoice::class.java, ids.invoiceId)!!.status)
        }
    }

    // ── 5. Border case — CONDITIONAL rule: trigger empty -> no finding; trigger filled -> required ─

    @Test
    fun `a CONDITIONAL field is only required once its trigger term has a value`() {
        val profile = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S5-Profil"; description = "Scenario 5" },
        )
        val profileId = profile.id!!
        // BT-19 (accounting reference, FREE, HEADER) is the trigger; BT-58 (buyer contact email,
        // FREE, HEADER) becomes mandatory only once BT-19 carries a value.
        addField(profileId, B2bTerm.BT_19, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE, 1)
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_58
                businessDefinition = "Käufer-Kontakt-E-Mail"
                requirement = B2bFieldRequirement.CONDITIONAL
                conditionTerm = B2bTerm.BT_19
                placement = B2bPlacement.INVOICE
                sortOrder = 2
            },
        )

        val ids = QuarkusTransaction.requiringNew().call { persistFixture("S5", profileId) }

        val findingsBeforeTrigger = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertTrue(findingsBeforeTrigger.isEmpty(), "BT-19 empty: BT-58 must not be required yet: $findingsBeforeTrigger")

        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, ids.invoiceId, mapOf("BT-19" to "KST-S5"))

        val findingsAfterTrigger = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        assertEquals(1, findingsAfterTrigger.count { it.ruleId == B2bProfileRules.MANDATORY_FIELD }, "BT-19 filled: BT-58 must now be required")
    }

    // ── 6. Border case — LINE-level term: one compliant / one violating item on the same invoice ─

    @Test
    fun `a LINE-level MANDATORY term finding targets exactly the violating position`() {
        val profileId = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S6-Profil"; description = "Scenario 6" },
        ).id!!
        addField(profileId, B2bTerm.BT_155, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, 1)

        val ids = QuarkusTransaction.requiringNew().call { persistFixture("S6", profileId) }
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, ids.itemId, mapOf("BT-155" to "ART-S6-1"))
        // itemId2 deliberately left without a BT-155 value.

        val findings = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, ids.invoiceId)!!)
        }
        val lineFindings = findings.filter { it.ruleId == B2bProfileRules.MANDATORY_FIELD }
        assertEquals(1, lineFindings.size, "exactly one position must be missing BT-155")
        assertEquals(
            FindingTarget.Position("2"),
            lineFindings.single().target,
            "the finding must target the violating position, not the compliant one",
        )
    }

    // ── 7. Border case — saveField rejection: MANDATORY on an unbindable pure-standard term ─────

    @Test
    fun `saveField rejects MANDATORY on a pure-reference term with a clear German error, nothing persisted`() {
        val profile = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S7-Profil"; description = "Scenario 7" },
        )
        val profileId = profile.id!!
        assertTrue(B2bTerm.BT_16.catalogEligible, "BT-16 must be selectable (BTE-7: pure reference terms are catalog-eligible)")
        assertNull(B2bTerm.BT_16.binding, "BT-16 must have no binding — that is exactly what makes MANDATORY unenforceable")

        val ex = assertThrows(InvalidParameterException::class.java) {
            b2bProfileFieldDefinitionService.saveField(
                B2bProfileField().apply {
                    this.profile = profile
                    term = B2bTerm.BT_16
                    businessDefinition = "Versandanzeige-Referenz"
                    requirement = B2bFieldRequirement.MANDATORY
                    placement = B2bPlacement.INVOICE
                },
            )
        }
        assertTrue(ex.message!!.contains("BT-16"), "the German error message must name the offending term: ${ex.message}")
        assertTrue(ex.message!!.contains("Optional"), "the message must point at the only allowed requirement: ${ex.message}")
        assertTrue(b2bProfileFieldDefinitionService.findFields(profileId).isEmpty(), "the rejected field must not have been persisted")
    }

    // ── 8. Border case — every catalog term used: selectable list empty; deleting one frees it ──

    @Test
    fun `a profile using every catalog term has no selectable entries left, deleting one frees it again`() {
        val profile = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S8-Profil"; description = "Scenario 8" },
        )
        val profileId = profile.id!!
        val allCatalogTerms = B2bTerm.entries.filter { it.catalogEligible }

        allCatalogTerms.forEach { term ->
            b2bProfileFieldDefinitionService.saveField(
                B2bProfileField().apply {
                    this.profile = profile
                    this.term = term
                    businessDefinition = term.officialCode
                    requirement = B2bFieldRequirement.OPTIONAL
                    // SELLER_PARTY is only ever legal
                    // at SELLER placement; every other level still accepts INVOICE (LINE
                    // coerces to it regardless, BUYER_PARTY/HEADER already accept it directly).
                    placement = if (term.level == B2bTermLevel.SELLER_PARTY) B2bPlacement.SELLER else B2bPlacement.INVOICE
                },
            )
        }
        assertEquals(allCatalogTerms.size, b2bProfileFieldDefinitionService.findFields(profileId).size, "every catalog term must have been saved")

        val selectableWhenFull = b2bProfileFieldDefinitionService.findSelectableCatalogEntries(profileId)
        assertTrue(selectableWhenFull.isEmpty(), "the \"alle Terme belegt\" contract: nothing left to pick")

        val freedField = b2bProfileFieldDefinitionService.findFields(profileId).first { it.term == B2bTerm.BT_19 }
        b2bProfileFieldDefinitionService.deleteField(freedField.id!!)

        val selectableAfterDelete = b2bProfileFieldDefinitionService.findSelectableCatalogEntries(profileId)
        assertEquals(listOf(B2bTerm.BT_19), selectableAfterDelete, "deleting BT-19's field must make exactly it selectable again")

        // Adding it back (an unused-term field) must still work after the delete.
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().apply {
                this.profile = profile
                term = B2bTerm.BT_19
                businessDefinition = "Buchungsreferenz (erneut)"
                requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.INVOICE
            },
        )
        assertTrue(b2bProfileFieldDefinitionService.findSelectableCatalogEntries(profileId).isEmpty(), "re-adding BT-19 must exhaust the catalog again")
    }

    // ── 9. Border case — B2bPlacement.BOTH removed, a placement is now exclusive ──

    @Test
    fun `a HEADER FREE term placed SELLER is scoped exclusively to the seller form, never the invoice header - BOTH's removal contract`() {
        // BOTH (Anbieter+Rechnung) used to make a single field show at both panes; it was removed
        // because no real profile ever used it — the one real
        // seller-side field (BT-84 IBAN) has always been plain SELLER. This pins the replacement
        // contract: SELLER placement is exclusive, same as INVOICE/RECIPIENT are for each other.
        val profile = b2bProfileLifecycleService.saveProfile(
            B2bProfile().apply { name = "S9-Profil"; description = "Scenario 9" },
        )
        val profileId = profile.id!!
        addField(profileId, B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL, B2bPlacement.SELLER, 1)

        assertEquals(listOf(B2bTerm.BT_56), b2bProfileFieldDefinitionService.findSellerFields(profileId).map { it.term })
        assertTrue(b2bProfileFieldDefinitionService.findInvoiceHeaderFields(profileId).isEmpty(), "a SELLER-only placement must never surface at the invoice header")
        assertTrue(b2bProfileFieldDefinitionService.findRecipientFields(profileId).isEmpty(), "a SELLER-only placement must never surface at the customer form")
    }

    // ─── Aufbau ──────────────────────────────────────────────────────────────

    private fun addField(
        profileId: Long,
        term: B2bTerm,
        requirement: B2bFieldRequirement,
        placement: B2bPlacement,
        sortOrder: Int,
    ): Long = b2bProfileFieldDefinitionService.saveField(
        B2bProfileField().apply {
            profile = b2bProfileLifecycleService.findProfileById(profileId)
            this.term = term
            businessDefinition = term.officialCode
            this.requirement = requirement
            this.placement = placement
            this.sortOrder = sortOrder
        },
    ).id!!

    private data class Fixture(val senderId: Long, val invoiceId: Long, val itemId: Long, val itemId2: Long, val recipientId: Long)

    /**
     * A finalizable invoice with complete master data on both sides — same shape
     * `B2bInvoiceE2EIT.persistScenario` already established, kept minimal (no B2B profile
     * assignment on the recipient itself; the invoice's own [Invoice.b2bProfile] is set directly,
     * mirroring what `InvoiceLifecycleService.create()`'s derivation would do). Two line items so
     * scenario 6 has a compliant/violating pair without a second fixture.
     */
    private fun persistFixture(tag: String, b2bProfileId: Long?): Fixture {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "E2E Sender $tag GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "E2E Recipient $tag AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"
            category = "S"
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            senderProfile = sender
            this.recipient = recipient
            this.b2bProfile = b2bProfileId?.let { em.getReference(B2bProfile::class.java, it) }
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // D-R1 (CORE.SERVICE_PERIOD_XOR, BLOCK stage via ComplianceBlockStageProfile): not
            // what this suite's B2B-profile scenarios exercise; satisfy it cheaply with BT-72.
            deliveryDate = LocalDate.of(2026, 2, 10)
            // BR-DE-15 — InvoiceLifecycleService.transition enforces BT-10 on every finalization
            // attempt regardless of any B2B profile; the fixture must carry one so a scenario's own
            // B2B finding is what blocks (or doesn't), not this unrelated, always-on check.
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            InvoiceItem().apply {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = "Beratung"
                quantity = BigDecimal("8")
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
                taxRateRef = taxRate
                sortOrder = 0
            },
        )
        invoice.addItem(
            InvoiceItem().apply {
                positionId = "2"
                category = InvoiceItemCategory.SERVICE
                description = "Reisezeit"
                quantity = BigDecimal("2")
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("60.00")
                taxRateRef = taxRate
                sortOrder = 1
            },
        )
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()

        return Fixture(
            senderId = sender.id!!,
            invoiceId = invoice.id!!,
            itemId = invoice.itemList.first { it.positionId == "1" }.id!!,
            itemId2 = invoice.itemList.first { it.positionId == "2" }.id!!,
            recipientId = recipient.id!!,
        )
    }
}

/**
 * Same `littletrotter.compliance-rules.stage=BLOCK` override `B2bInvoiceE2EIT`
 * (kotlin-invoice) needs, redeclared here: this module cannot depend on kotlin-invoice's
 * test-scoped `ComplianceBlockStageProfile` (no test-jar dependency), and a `QuarkusTestProfile`
 * must be a standalone top-level class (ArchUnit's test engine stumbles on nested Kotlin
 * `object`s without a public constructor — the same reason the kotlin-invoice original is
 * top-level too). Without `BLOCK`, a compliance finding is only logged (`OBSERVE`, the safe
 * default), never actually blocks `InvoiceLifecycleService.transition`.
 */
class ComplianceBlockStageProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("littletrotter.compliance-rules.stage" to "BLOCK")
}
