package de.littletrotter.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.domain.compliance.B2bProfileRules
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.InvoiceDto
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.impl.Headers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/** B2B flow E2E: profile creation to finalized invoice over full stack (real PostgreSQL,
 *  CDI-wired services) to InvoiceDto on the wire. Same test resource/setup as InvoiceCancellationE2EIT,
 *  different business case (B2B profile instead of cancellation). Steps: create profile, set fields,
 *  create vendor+article with B2B preset, capture invoice, finalize (blocks on missing mandatory
 *  field), fill field, trigger conditional field, finalize success. Countercase: no profile
 *  (community behavior). Conditional field on BT-19 (not BT-13, which is optional after
 *  BT-10/BT-13 split in V004/V906). */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
class B2bInvoiceE2EIT {

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
    lateinit var b2bProfileRuleSet: B2bProfileRuleSet

    @Inject
    lateinit var articleQueries: ArticleQueryPort

    private val mapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `a B2B seller runs from profile setup to a finalized invoice`() {
        // ── 1. Create profile ────────────────────────────────────────────────
        val profileId = b2bProfileLifecycleService.saveProfile(
            B2bProfile().edit {
                name = "E2E-B2B-Profil"
                description = "task12-b2b/T7 — E2E"
            },
        ).id!!

        // BT-72 is BOUND to `deliveryDate`: the profile only expresses whether it's mandatory,
        // it does not create a second input field.
        addField(profileId, "BT-72", B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, sortOrder = 1)
        // BT-56 is FREE and placed on the seller — the mandatory field used below.
        addField(profileId, "BT-56", B2bFieldRequirement.MANDATORY, B2bPlacement.SELLER, sortOrder = 2)
        // BT-19 is FREE at the header and triggers the conditional line field.
        addField(profileId, "BT-19", B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE, sortOrder = 3)
        // BT-155 is LINE-level: placement is forced to INVOICE server-side even when the caller
        // requests SELLER.
        val lineFieldId = addField(
            profileId, "BT-155", B2bFieldRequirement.CONDITIONAL, B2bPlacement.SELLER,
            conditionBtCode = "BT-19", sortOrder = 4,
        )
        assertEquals(
            B2bPlacement.INVOICE,
            b2bProfileFieldDefinitionService.findFields(profileId).single { it.id == lineFieldId }.placement,
            "ein LINE-Term kann nie am Anbieter erscheinen — die Platzierung wird erzwungen, nicht nur geprüft",
        )

        // ── 1b. Add another field ─────────────────────────────────────────────
        addField(profileId, "BT-18", B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE, sortOrder = 5)
        assertEquals(5, b2bProfileFieldDefinitionService.findFields(profileId).size, "das ergänzte Feld steht im Profil")
        assertTrue(
            b2bProfileFieldDefinitionService.findSelectableCatalogEntries(profileId).none { it.officialCode == "BT-18" },
            "ein bereits belegter Term ist nicht ein zweites Mal wählbar",
        )

        // ── 2. Create sender, assign profile, fill mandatory field ────────────
        val ids = QuarkusTransaction.requiringNew().call { persistScenario("B2BE", profileId) }

        assertEquals(
            listOf("Kontaktstelle des Käufers"),
            b2bProfileFieldDefinitionService.missingMandatorySellerFields(profileId, emptyMap()),
            "das MANDATORY-Feld am Anbieter fehlt und wird mit der anwenderdefinierten Bezeichnung gemeldet",
        )
        b2bFieldValueService.saveFieldValues(
            B2bFieldOwnerType.SENDER_PROFILE, ids.senderId, mapOf("BT-56" to "Einkauf, Frau Meier"),
        )
        assertTrue(
            b2bProfileFieldDefinitionService.missingMandatorySellerFields(
                profileId, b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, ids.senderId),
            ).isEmpty(),
            "nach dem Füllen ist der Anbieter speicherbar",
        )

        // ── 3./4. Copy article preset values onto the line item ───────────────
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.ARTICLE, ids.articleId, mapOf("BT-155" to "ART-4711"))
        b2bFieldValueService.copyLineTemplateValuesToInvoiceItem(ids.articleId, ids.itemId, ids.invoiceId)

        assertEquals(
            "ART-4711",
            b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE_ITEM, ids.itemId)["BT-155"],
            "die Vorbelegung des Artikels steht an der Position",
        )

        // Changing price and description leaves the tax rate and article template untouched:
        // the value was copied onto the line item, not referenced.
        QuarkusTransaction.requiringNew().run {
            val item = em.find(InvoiceItem::class.java, ids.itemId)!!
            item.edit { description = "Beratung (Sonderkonditionen)" }
            item.edit { unitPriceNet = Money.of("120.00") }
            item.invoice!!.recalculateTotals()
        }
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, ids.itemId, mapOf("BT-155" to "POS-0815"))

        QuarkusTransaction.requiringNew().run {
            val item = em.find(InvoiceItem::class.java, ids.itemId)!!
            assertEquals(ids.taxRateId, item.taxRateRef?.id, "die MWST-Bindung der Position ändert sich dadurch nicht")
        }
        assertEquals(
            "ART-4711",
            b2bFieldValueService.findFieldValues(B2bFieldOwnerType.ARTICLE, ids.articleId)["BT-155"],
            "die Artikelvorlage bleibt unverändert, wenn die Position ihren Wert ändert",
        )

        // ── 5. Finalization fails on the missing mandatory field ──────────────
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.IN_REVIEW) }

        val firstBlock = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.ISSUED) }
        }
        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, firstBlock.reason)
        assertEquals(
            1, firstBlock.findings.count { it.ruleId == B2bProfileRules.MANDATORY_FIELD },
            "genau ein B2B-Befund: BT-72 (Lieferdatum) ist pflichtig und leer",
        )
        assertTrue(
            firstBlock.findings.none { it.location != null },
            "der Befund hängt am Kopf, nicht an einer Position",
        )
        QuarkusTransaction.requiringNew().run {
            assertEquals(
                InvoiceStatus.IN_REVIEW, em.find(Invoice::class.java, ids.invoiceId)!!.status,
                "ein blockierter Versuch schreibt nichts fest",
            )
        }

        // ── 6. Fill the mandatory field, trigger the conditional field ────────
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, ids.invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 1, 20) }
        }
        // BT-19 gets a value — this makes BT-155 mandatory on every line item …
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, ids.invoiceId, mapOf("BT-19" to "KST-4711"))
        // … and the second line item has none (only the first came from the article).
        val secondBlock = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.ISSUED) }
        }
        val positionFindings = secondBlock.findings.filter { it.ruleId == B2bProfileRules.MANDATORY_FIELD }
        assertEquals(1, positionFindings.size, "BT-72 ist gefüllt, offen ist nur noch das bedingte Positionsfeld")
        assertEquals("position:2", positionFindings.single().location, "der Befund benennt die betroffene Position")

        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, ids.secondItemId, mapOf("BT-155" to "POS-0816"))

        // ── 7. Finalization succeeds; the values are in the export payload ────
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.ISSUED) }

        val dto = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, ids.invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status)
            assertTrue(b2bProfileRuleSet.evaluate(invoice).isEmpty(), "kein offener B2B-Befund mehr")
            publishAndCapture(invoice)
        }

        // InvoiceDtoMapper must read B2B field values by every owner type, not just
        // INVOICE: a SENDER_PROFILE-placed value like BT-56 has to reach the DTO alongside
        // invoice-level values like BT-19.
        assertEquals(
            setOf("BT-19" to "KST-4711", "BT-56" to "Einkauf, Frau Meier"),
            dto.b2bValues.map { it.term.officialCode to it.value }.toSet(),
            "der erfasste Kopfwert und der Anbieter-Wert stehen auf dem Draht",
        )
        assertEquals(
            setOf("POS-0815", "POS-0816"),
            dto.items.flatMap { item -> item.b2bValues.map { it.value } }.toSet(),
            "die Positionswerte stehen je Position auf dem Draht",
        )
    }

    /**
     * The counter-case: same invoice, same path, but without a B2B profile on the sender. It
     * proves the whole B2B strand is a no-op for an open-source installation — no finding, no
     * extra mandatory value, an unchanged payload. Without it, "B2B blocks correctly" would be
     * indistinguishable from "something always blocks."
     */
    @Test
    fun `without a B2B profile the invoice finalizes exactly as before`() {
        val ids = QuarkusTransaction.requiringNew().call { persistScenario("B2BC", b2bProfileId = null) }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.ISSUED) }

        val dto = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, ids.invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "ohne Profil steht der Festschreibung nichts im Weg")
            assertTrue(
                b2bProfileRuleSet.evaluate(invoice).isEmpty(),
                "ohne zugeordnetes Profil kommt der Regelsatz gar nicht erst an die Repositories",
            )
            // The delivery date is missing here too — without a profile, BT-72 is simply not mandatory.
            assertEquals(null, invoice.deliveryDate)
            publishAndCapture(invoice)
        }

        assertTrue(dto.b2bValues.isEmpty(), "der Kopf trägt keine B2B-Werte")
        assertTrue(dto.items.all { it.b2bValues.isEmpty() }, "die Positionen tragen keine B2B-Werte")
    }

    /**
     * The mapping → dialog → `b2b_field_value` → CII payload chain, without new
     * export code: a per-(article, recipient) [ArticleRecipientRef] mapping exists in master
     * data; the position is built exactly the way the item dialog's article-selection listener
     * would build it for a *new* position (buyer article name as [InvoiceItem.description],
     * buyer article number persisted under BT-156 via [de.littletrotter.application.reference
     * .B2bFieldValueService.saveFieldValues] — the same call [de.littletrotter.ui.view.invoice
     * .persistBuyerItemNumber] makes) — no B2B profile involved, since BT-156 is always
     * enterable regardless of one. The finalized invoice's export payload must carry both:
     * BT-153 as the item description, BT-156 as its `b2b_field_value`.
     */
    @Test
    fun `a mapped recipient's buyer article number and name reach BT-156BT-153 in the export payload`() {
        val ids = QuarkusTransaction.requiringNew().call { persistMappingScenario("B2BM") }

        QuarkusTransaction.requiringNew().run {
            val mapping = articleQueries.findArticleRecipientRef(ids.articleId, ids.recipientId)
            assertNotNull(mapping, "the NEKAR-T11 mapping must be resolvable for this (article, recipient) pair")
            val item = em.find(InvoiceItem::class.java, ids.itemId)!!
            item.edit { description = mapping!!.buyerArticleName!! }
            item.invoice!!.recalculateTotals()
        }
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, ids.itemId, mapOf("BT-156" to "243538"))

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(ids.invoiceId, InvoiceStatus.ISSUED) }

        val dto = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, ids.invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "no B2B profile involved — nothing blocks finalization")
            publishAndCapture(invoice)
        }

        val item = dto.items.single { it.positionId == "1" }
        assertEquals("Nekar-Bezeichnung", item.description, "BT-153 carries the buyer's own article designation")
        assertEquals(
            listOf("BT-156" to "243538"),
            item.b2bValues.map { it.term.officialCode to it.value },
            "BT-156 carries the buyer's own article number",
        )
    }

    // ─── Fixtures ────────────────────────────────────────────────────────────

    private fun addField(
        profileId: Long,
        btCode: String,
        requirement: B2bFieldRequirement,
        placement: B2bPlacement,
        sortOrder: Int,
        conditionBtCode: String? = null,
    ): Long = b2bProfileFieldDefinitionService.saveField(
        B2bProfileField().edit {
            profile = b2bProfileLifecycleService.findProfileById(profileId)
            term = de.littletrotter.shared.b2b.B2bTerm.fromOfficialCode(btCode)
            businessDefinition = businessDefinitionFor(btCode)
            this.requirement = requirement
            this.conditionTerm = conditionBtCode?.let { de.littletrotter.shared.b2b.B2bTerm.fromOfficialCode(it) }
            this.placement = placement
            this.sortOrder = sortOrder
        },
    ).id!!

    private fun businessDefinitionFor(btCode: String) = when (btCode) {
        "BT-72" -> "Lieferdatum"
        "BT-56" -> "Kontaktstelle des Käufers"
        "BT-19" -> "Buchungsreferenz"
        "BT-155" -> "Artikelnummer des Verkäufers"
        else -> "Belegobjekt-Kennung"
    }

    /** The technical keys of a built scenario — everything the test method needs afterward,
     *  outside the transaction. */
    private data class Scenario(
        val senderId: Long,
        val invoiceId: Long,
        val itemId: Long,
        val secondItemId: Long,
        val articleId: Long,
        val taxRateId: Long,
    )

    /**
     * A finalizable invoice with complete master data on both sides: with
     * `littletrotter.compliance-rules.stage=BLOCK`, **every** ERROR finding blocks, not just the
     * B2B finding under test here — a missing sender address would throw the same way and make
     * the test meaningless.
     *
     * [tag] keeps the unique columns (prefix, customer number, tax rate code) apart per test
     * method; the tables are not empty between methods.
     */
    private fun persistScenario(tag: String, b2bProfileId: Long?): Scenario {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "B2B Anbieter GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@b2b.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        sender.addAddress(
            Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true),
        )
        // The B2B profile assignment lives on the customer relationship, not the seller.
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Acme GmbH"
            email = Email.ofNullable("ap@acme.example")
            vatId = VatId("DE987654321")
            iban = Iban.ofNullable("DE02500105170137075030")
            b2bProfile = b2bProfileId?.let { em.getReference(B2bProfile::class.java, it) }
        }
        recipient.addAddress(
            Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true),
        )
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"
            category = "S"
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        em.persist(taxRate)

        val article = anArticle {
            name = "Beratung"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.HOUR
            defaultQuantity = BigDecimal("8")
            defaultUnitPriceNet = Money.of("100.00")
            defaultTaxRateRef = taxRate
        }
        em.persist(article)

        val invoice = Invoice().edit {
            senderProfile = sender
            this.recipient = recipient
            // This test constructs the aggregate directly via JPA, bypassing
            // InvoiceLifecycleService.create()'s derivation; set explicitly here to mirror what
            // that derivation would do (recipient.b2bProfile -> invoice.b2bProfile).
            this.b2bProfile = recipient.b2bProfile
            // Assigned directly rather than drawn at the transition: the numbering sequence is
            // not what this test checks, and a drawn number would make the scenario depend on an
            // unrelated strand.
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            references.buyerReference = "PO-$tag-1"
            // The service-period rule (CORE.SERVICE_PERIOD_XOR, BLOCK stage via ComplianceBlockStageProfile): the
            // "without a B2B profile" counter-scenario explicitly asserts deliveryDate stays null
            // (BT-72 is a B2B-profile-conditional field here), so the header service period
            // (BT-73/74) is the alternative that satisfies the rule without touching that field.
            servicePeriodFrom = LocalDate.of(2026, 1, 1)
            servicePeriodTo = LocalDate.of(2026, 1, 31)
        }
        invoice.addItem(
            InvoiceItem().edit {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = article.name
                quantity = article.defaultQuantity
                unit = article.defaultUnit
                unitPriceNet = article.defaultUnitPriceNet
                taxRateRef = taxRate
                sortOrder = 0
            },
        )
        invoice.addItem(
            InvoiceItem().edit {
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

        return Scenario(
            senderId = sender.id!!,
            invoiceId = invoice.id!!,
            itemId = invoice.itemList.first { it.positionId == "1" }.id!!,
            secondItemId = invoice.itemList.first { it.positionId == "2" }.id!!,
            articleId = article.id!!,
            taxRateId = taxRate.id!!,
        )
    }

    /** The technical keys of a mapping scenario. */
    private data class MappingScenario(val recipientId: Long, val invoiceId: Long, val itemId: Long, val articleId: Long)

    /**
     * A minimal, finalizable invoice for a recipient that carries a per-article
     * mapping ("243538" / "Nekar-Bezeichnung"), no B2B profile involved (unlike [persistScenario],
     * whose whole point is the profile-gated strand). One position, built with the article's own
     * defaults — the test itself overwrites description/BT-156 the way the item dialog would once
     * the position has an id, mirroring how [persistScenario] separates "position is added" from
     * "template values are copied onto it".
     */
    private fun persistMappingScenario(tag: String): MappingScenario {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "Mapping Anbieter GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@mapping.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        sender.addAddress(
            Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true),
        )
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Nekar Supermarkt"
            email = Email.ofNullable("ap@nekar.example")
            vatId = VatId("DE987654321")
            iban = Iban.ofNullable("DE02500105170137075030")
        }
        recipient.addAddress(
            Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true),
        )
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"
            category = "S"
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        em.persist(taxRate)

        val article = anArticle {
            name = "Beratung"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.HOUR
            defaultQuantity = BigDecimal("8")
            defaultUnitPriceNet = Money.of("100.00")
            defaultTaxRateRef = taxRate
        }
        em.persist(article)
        em.persist(ArticleRecipientRef.of(article, recipient, "243538", "Nekar-Bezeichnung"))

        val invoice = Invoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            references.buyerReference = "PO-$tag-1"
            // The service-period rule (CORE.SERVICE_PERIOD_XOR, BLOCK stage via ComplianceBlockStageProfile): the
            // "without a B2B profile" counter-scenario explicitly asserts deliveryDate stays null
            // (BT-72 is a B2B-profile-conditional field here), so the header service period
            // (BT-73/74) is the alternative that satisfies the rule without touching that field.
            servicePeriodFrom = LocalDate.of(2026, 1, 1)
            servicePeriodTo = LocalDate.of(2026, 1, 31)
        }
        invoice.addItem(
            InvoiceItem().edit {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = article.name
                quantity = article.defaultQuantity
                unit = article.defaultUnit
                unitPriceNet = article.defaultUnitPriceNet
                taxRateRef = taxRate
                sortOrder = 0
                articleRef = article
            },
        )
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()

        return MappingScenario(
            recipientId = recipient.id!!,
            invoiceId = invoice.id!!,
            itemId = invoice.itemList.first { it.positionId == "1" }.id!!,
            articleId = article.id!!,
        )
    }

    /** The payload `kotlin-xpdf` reads off the wire — not a reconstruction but the actual result
     *  of [DocumentJobPublisher.publish], using the real [B2bFieldValueService] so the captured
     *  B2B values are actually looked up. */
    private fun publishAndCapture(invoice: Invoice): InvoiceDto {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService))
            .publish(invoice, 1L)

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val root = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8))
        val dto = mapper.readValue<InvoiceDto>(root.path("payload").path("invoice").toString())
        assertNotNull(dto.invoiceNumber, "der Payload trägt die Rechnung, nicht nur ein Gerüst")
        return dto
    }
}

/**
 * Stage BLOCK for **rule evaluation** (`littletrotter.compliance-rules.stage`), not for the
 * validation gate: this is what makes a B2B finding actually block finalization instead of
 * merely being logged. Declared top-level for the same reason as `BlockStageProfile` in
 * `FinalizationGateIT` — ArchUnit's test engine trips over nested Kotlin `object`s without a
 * public constructor.
 */
class ComplianceBlockStageProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("littletrotter.compliance-rules.stage" to "BLOCK")
}
