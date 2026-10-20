package de.littletrotter.e2e.einvoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.SchemedIdentifierDto
import de.littletrotter.shared.documents.DocumentDates
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.validation.CiiSchematronValidator
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * ST-7 — does the cancellation document (`InvoiceTypeCode.CORRECTED_INVOICE`, UNTDID 1001 `384` — PO
 * decision 1 (2026-09-02); this class was originally written against `381`/`CREDIT_NOTE`, the
 * type the cancellation flow stamped before that decision) actually satisfy ZUGFeRD 2.5.2 / EN 16931,
 * checked with a real validator run against a real, generated cancellation document rather than a
 * reading of the mapping code.
 *
 * Cancellation-384 migration (2026-09-02) note: this class's assertions were updated in source for
 * `384`/the negated-amounts-no-payment-terms shape (see [InvoiceCancellationService]'s own class KDoc
 * for the full sign-logic/BR-CO-25 reasoning), but the session that made that change could not get
 * a completed run of this class — the `kotlin-e2e-tests` module sits behind a full multi-module
 * reactor build that repeatedly hit environment-level compile corruption from another session's
 * concurrent `make test MODULE=all` runs racing target-directory wipes on the same working tree,
 * unrelated to this class's own content. Re-run this class (directly, or via `make test
 * MODULE=all IT_TESTS=ZugferdCreditNoteConformanceE2EIT`) once the working tree is quiet, before
 * treating the cancellation-384 spec's re-verification as closed.
 *
 * BT-3/BG-3/BT-25/BT-26 already have
 * thorough unit coverage on hand-built [InvoiceDto] fixtures
 * ([de.littletrotter.xpdf.impl.mustang.En16931TermsExportTest]), but none of it ever went through
 * [EmbeddedKositValidator], and none of it ever came from
 * [InvoiceCancellationService.createCreditNote] — the real path a cancellation takes since ST-3 (born directly
 * in `CANCELLED`, numbered at creation, no `DRAFT -> IN_REVIEW -> ISSUED` walk). This class closes
 * that gap for the Mustang route the product ships:
 *
 * - **Mustang** ([XmlExportServiceImpl]) — real, embedded KoSIT validation
 *   ([EmbeddedKositValidator], no Docker needed), covering both profiles
 *   [XmlExportServiceImpl.resolveProfile] can select (`XRechnung` when either party carries a
 *   vatId, `EN16931` when neither does) — **empirically, only `XRechnung` is reachable from a real
 *   cancellation**: [de.littletrotter.domain.masterdata.SenderProfile.validate] makes both `vatId` and
 *   `iban` hard preconditions for any invoice this system ever lets reach `ISSUED` (confirmed live —
 *   the "no vatId" fixture this class first tried threw `InvoiceValidationException` out of
 *   [InvoiceCancellationService.createCreditNote] itself, since a credit note re-validates like any other
 *   issued document — and, independently, [de.littletrotter.domain.invoice.ExportProfile
 *   .resolveExportProfileId] duplicates the exact same vatId-based decision inside kotlin-invoice
 *   and freezes it into `InvoiceMetaDto.profileId` at finalization, which
 *   [XmlExportServiceImpl.resolveProfile] then *prefers* over its own local heuristic — so even a
 *   DTO with the vatId stripped back out after the fact still resolves `XRechnung` unless
 *   `profileId` is cleared too). The `EN16931` branch and the hand-written `TypeCode 42`
 *   payment-means fallback
 *   ([de.littletrotter.xpdf.impl.mustang.MustangTransactionBuilder.getTradeSettlement]) are
 *   therefore dead code for any invoice created through this system's own domain layer today; they
 *   can only be reached by data older than that validation rule, or a future relaxation of it.
 *   The second Mustang test below still proves both paths stay KoSIT-conformant *if* reached — on a
 *   DTO derived from a real, generated cancellation document (not a hand-built fixture), with the two
 *   now-mandatory fields stripped back out after the fact, since there is no other way to construct
 *   this state honestly. Reported to the PO as a finding (ST-7), not silently worked around.
 *
 * Every fixture gives the original a service period roughly two months before "today" — ST-7
 * point 4's due-date/service-period-ordering case: the cancellation's own issue date (always today,
 * [InvoiceCancellationService.createCreditNote]) is deliberately well *after* the service period it
 * still names, since it cancels a service already rendered, not a future one. Neither profile
 * rejects that ordering; there is no EN 16931 rule requiring BT-2 to precede BT-73/BT-74.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ZugferdCreditNoteConformanceE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    companion object {
        /** Same idiom as [EInvoiceGenerationE2EIT] — the real, embedded KoSIT validator, shared
         *  across every test method since loading the XRechnung 3.0.2 scenario ZIP is
         *  comparatively expensive and it carries no per-test state. */
        private lateinit var kosit: EmbeddedKositValidator
        private lateinit var schematron: CiiSchematronValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
            schematron = CiiSchematronValidator().apply { init() }
        }
    }

    private val mustang = XmlExportServiceImpl(null)

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper().mapToDto(invoice)
    }

    /**
     * Builds an ISSUED original with one line item and a service period ending roughly two months
     * before "today" (see the class KDoc for why). The sender always carries a vatId and an IBAN
     * — [de.littletrotter.domain.masterdata.SenderProfile.validate] makes both mandatory for any
     * invoice reaching `ISSUED`, which is also what pins the Mustang route to the `XRechnung`
     * profile in practice (see the class KDoc).
     * [documentLanguage] defaults to the entity default (`"de"`); pass `"en"` to build
     * an English original for the cancellation-reference-language re-run below.
     * [taxCategory] defaults to the standard-rate `S` shape every other test in this
     * class uses. `"AE"` builds the full Reverse-Charge shape instead: it reuses the seeded
     * `EU_B2B_RC_SERVICE` rate rather than minting a second `AE` `tax_rate` row (a second row
     * would collide with `ItemTaxRateForeignSellerE2EIT`'s
     * `findAllTaxRates().find { it.categoryEnum == VatCategory.REVERSE_CHARGE }`, which asserts
     * exactly one such row exists), sets `Invoice.reverseCharge = true` (the header
     * decision leads, positions must follow — [de.littletrotter.domain.invoice
     * .validateReverseChargeItemConsistency] rejects an AE item on a `reverseCharge = false`
     * invoice), and gives the recipient the FR/BUSINESS/`VERIFIED` shape
     * [de.littletrotter.domain.compliance.assertReverseChargeRecipientEligibility] requires (a
     * German-B2B recipient is never Reverse-Charge-eligible) — without it the `ISSUED` transition
     * fails on a *different* gate right after the item-consistency one this fixes.
     */
    private fun buildSentOriginal(
        tag: String,
        documentLanguage: String = "de",
        taxCategory: String = "S",
    ): Long {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "ZUGFeRD Storno E2E GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                phone = "+49 30 1234567"
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "Storno Konform Kunde AG"
                email = Email.ofNullable("ap@$tag.example")
                routingId = "PO-$tag-STANDING"
                if (taxCategory == "AE") {
                    // The reverse-charge branch (TaxRuleResolver.resolve): EU-abroad, BUSINESS,
                    // a VERIFIED buyer VAT-id whose prefix matches the billing country.
                    vatId = VatId("FRXX223456789")
                    customerType = CustomerType.BUSINESS
                    vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
                } else {
                    vatId = VatId("DE222222222")
                }
            }
            val recipientCountry = if (taxCategory == "AE") Country.FR else Country.DE
            val recipientCity = if (taxCategory == "AE") "Paris" else "Hamburg"
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", recipientCity, recipientCountry, true))
            em.persist(sender)
            em.persist(recipient)

            // AE reuses the one seeded Reverse-Charge rate; every other category still
            // gets its own ad hoc rate the way this fixture always has (no shared-row risk there,
            // since only AE has a "categoryEnum == REVERSE_CHARGE" invariant elsewhere).
            val resolvedTaxRateId = if (taxCategory == "AE") {
                taxRateQueries.findAllTaxRates().first { it.code == "EU_B2B_RC_SERVICE" }.id!!
            } else {
                val taxRate = TaxRate().edit {
                    code = "${tag}_STD19"; category = taxCategory; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                    country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
                }
                em.persist(taxRate)
                em.flush()
                taxRate.id!!
            }
            Triple(sender.id!!, recipient.id!!, resolvedTaxRateId)
        }

        val originalId = QuarkusTransaction.requiringNew().call {
            if (taxCategory == "AE") {
                lifecycleService.create(senderId, recipientId, reverseChargeDecision = true).id!!
            } else {
                lifecycleService.create(senderId, recipientId).id!!
            }
        }

        val periodStart = E2eBusinessDay.today().minusMonths(2)
        val periodEnd = periodStart.plusDays(20)
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, originalId)!!
            invoice.edit { servicePeriodFrom = periodStart }
            invoice.edit { servicePeriodTo = periodEnd }
            invoice.edit { this.documentLanguage = documentLanguage }
            // This is the header decision, not a derivation from the item: without this, an AE
            // item on a reverseCharge=false invoice is rejected at the ISSUED transition
            // (REVERSE_CHARGE_ITEM_UNEXPECTED_AE).
            if (taxCategory == "AE") invoice.edit { reverseCharge = true }
        }

        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                originalId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Beratung",
                    quantity = BigDecimal("4"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("125.00"),
                    taxRateId = taxRateId,
                ),
            )
        }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.ISSUED) }
        return originalId
    }

    /** Cancels [originalId] through the real [InvoiceCancellationService] (ST-3: the credit note is
     *  born directly in `CANCELLED`, already numbered) and maps the resulting document to a DTO. */
    private fun cancellationAndMapToDto(originalId: Long, reason: String): Pair<Long, InvoiceDto> {
        val creditNoteId = cancellationService.createCreditNote(originalId, reason).id!!
        val dto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        return creditNoteId to dto
    }

    private fun findingsText(result: KositValidationResult) =
        result.details.joinToString("; ") { "${it.severity} ${it.ruleId}: ${it.message}" }

    // ─── Mustang route — both profiles the product ships ─────────────────────────────────────

    @Test
    fun `Mustang - XRechnung profile - a real cancellation document is KoSIT VALID and carries BT-3-BT-25-BT-26 correctly`() {
        val originalId = QuarkusTransaction.requiringNew().call { buildSentOriginal("SCX1") }
        val (originalNumber, originalDate) = QuarkusTransaction.requiringNew().call {
            val original = em.find(Invoice::class.java, originalId)!!
            original.invoiceNumber.value to original.invoiceDate
        }
        val (_, dto) = cancellationAndMapToDto(originalId, "Leistung storniert (ST-7 XRechnung)")

        // 384, not 381.
        assertEquals("384", dto.typeCode, "the storno document must carry BT-3 = 384 on the wire")
        assertEquals("XRechnung", mustang.resolveProfile(dto), "this fixture (both parties carry a vatId) must resolve XRechnung")

        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "Mustang/XRechnung storno document must be KoSIT VALID: ${findingsText(result)}")

        // BT-3 — 384 in the ExchangedDocument header, not the ordinary-invoice default 380.
        val exchanged = compact.substringAfter("<rsm:ExchangedDocument>").substringBefore("</rsm:ExchangedDocument>")
        assertTrue(exchanged.contains("<ram:TypeCode>384</ram:TypeCode>"), exchanged)

        // BG-3/BT-25/BT-26 — the preceding (cancelled) invoice number and its issue date, with the
        // qdt:DateTimeString/format=102 shape this one CII slot uses (everywhere else on the
        // document it is udt:, see En16931TermsExportTest's own comment on this).
        assertTrue(
            compact.contains("<ram:IssuerAssignedID>$originalNumber</ram:IssuerAssignedID>"),
            "BT-25 (preceding invoice number) missing: $compact",
        )
        assertTrue(
            compact.contains("<ram:FormattedIssueDateTime><qdt:DateTimeString format=\"102\">"),
            "BT-26 (preceding invoice date) missing or wrong shape: $compact",
        )

        // BR-DE-15 — BT-10 buyer reference (auto-filled from Recipient.routingId at finalization,
        // BuyerReferenceRequirement) survives references.copyFrom into the credit note.
        assertTrue(compact.contains("<ram:BuyerReference>PO-SCX1-STANDING</ram:BuyerReference>"), compact)

        // BR-DE-1/BG-16 — real bank details (IBAN configured for this fixture), not the TypeCode
        // 42 fallback.
        assertTrue(compact.contains("<ram:PayeePartyCreditorFinancialAccount>"), compact)

        // No payment terms on a cancellation: no due date, no payment-terms
        // group on the wire at all.
        assertNull(dto.dates.dueDate, "the storno document must carry no due date")
        assertFalse(compact.contains("<ram:SpecifiedTradePaymentTerms>"), "no payment-terms group may appear on a storno document: $compact")

        // The golden German assertion: BT-22 (ram:IncludedNote) carries the composed
        // German reference sentence unchanged, on a real KoSIT-VALID document.
        val expectedNote = "Stornierung der Rechnung $originalNumber vom " +
            DocumentDates.format(originalDate, "de") +
            ". Grund: Leistung storniert (ST-7 XRechnung)"
        assertTrue(
            compact.contains("<ram:IncludedNote><ram:Content>$expectedNote</ram:Content></ram:IncludedNote>"),
            "BT-22 must carry the composed German reference unchanged: $compact",
        )
    }

    /**
     * Re-run of the class's own KoSIT gate for an English original: BT-22 content changed
     * (the composed reference sentence, in English), so this is a mandatory real embedded KoSIT
     * re-run, not just a unit assertion on the composed string.
     */
    @Test
    fun `Mustang - XRechnung profile - an English original produces an English BT-22 and stays KoSIT VALID`() {
        val originalId = QuarkusTransaction.requiringNew().call { buildSentOriginal("SCX4", documentLanguage = "en") }
        val (originalNumber, originalDate) = QuarkusTransaction.requiringNew().call {
            val original = em.find(Invoice::class.java, originalId)!!
            original.invoiceNumber.value to original.invoiceDate
        }
        val (_, dto) = cancellationAndMapToDto(originalId, "Customer cancelled the order.")

        assertEquals("384", dto.typeCode)
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "Mustang/XRechnung English storno document must be KoSIT VALID: ${findingsText(result)}")

        val expectedNote = "Reversal of invoice $originalNumber dated " +
            DocumentDates.format(originalDate, "en") +
            ". Reason: Customer cancelled the order."
        assertTrue(
            compact.contains("<ram:IncludedNote><ram:Content>$expectedNote</ram:Content></ram:IncludedNote>"),
            "BT-22 must carry the composed English reference: $compact",
        )
    }

    /**
     * The mandatory real embedded KoSIT re-run for the tax
     * exemption note: the real, seeded `EU_B2B_RC_SERVICE` Reverse-Charge tax rule
     * (`V2__baseline_seed.sql` — `taxCategory = "AE"` in [buildSentOriginal] reuses that exact
     * row, never a second `AE` rate) on an English document, taken through the real
     * cancellation/Mustang export, not a hand-built XML string. BT-120 must carry the seeded rule's
     * own German exemption-reason text followed by " — " and the standard English
     * sentence, and the document must still be KoSIT VALID — if this ever turned INVALID the
     * composition would need to fall back to the
     * German original alone on the wire (BT-121 already carries the machine-readable code);
     * this run is exactly what would trigger that fallback.
     */
    @Test
    fun `Mustang - XRechnung profile - an English Reverse-Charge original composes BT-120 and stays KoSIT VALID`() {
        val originalId = QuarkusTransaction.requiringNew().call {
            buildSentOriginal("SCX5", documentLanguage = "en", taxCategory = "AE")
        }
        val (_, dto) = cancellationAndMapToDto(originalId, "Customer cancelled the order.")

        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "Mustang/XRechnung English Reverse-Charge document must be KoSIT VALID: ${findingsText(result)}")

        // BT-120 — the seeded EU_B2B_RC_SERVICE rule's own German text
        // (V2__baseline_seed.sql), then " — ", then the English standard sentence.
        val seededGermanExemptionReason =
            "Reverse Charge: Der Leistungsempfänger schuldet die Umsatzsteuer gemäß Art. 196 MwStSystRL / § 13b UStG."
        assertTrue(
            compact.contains(
                "<ram:ExemptionReason>$seededGermanExemptionReason — " +
                    "Reverse charge: the recipient is liable for VAT (Art. 196 VAT Directive).</ram:ExemptionReason>",
            ),
            "BT-120 must carry the composed German+English exemption reason: $compact",
        )
        // BT-121 — untouched, still the plain machine-readable code.
        assertTrue(compact.contains("<ram:ExemptionReasonCode>VATEX-EU-AE</ram:ExemptionReasonCode>"), compact)
    }

    /**
     * The `EN16931` branch and the hand-written `TypeCode 42` fallback (see the class KDoc for
     * why neither is reachable through this system's own domain layer today) still get proven
     * against a real KoSIT run — on a DTO taken from a genuinely generated cancellation document, with
     * only the two now-mandatory fields (`vatId`, IBAN) stripped back out to reconstruct the state
     * the mapping code was originally written to handle.
     */
    @Test
    fun `Mustang - EN16931 profile and TypeCode-42 fallback - reachable only via a stripped DTO, still KoSIT VALID`() {
        val originalId = QuarkusTransaction.requiringNew().call { buildSentOriginal("SCX2") }
        val (_, realDto) = cancellationAndMapToDto(originalId, "Leistung storniert (ST-7 EN16931/TypeCode-42)")
        val dto = realDto.copy(
            // BR-CO-26 still requires SOME seller identifier once vatId is gone — BT-29, BT-30 or
            // BT-31. A BT-29 identifier (scheme "0088"/GS1 GLN, ISO 6523 ICD — BR-CL-11 rejects a
            // scheme outside that list) keeps the seller identifiable without reintroducing the
            // vatId this test is about removing.
            senderProfile = realDto.senderProfile?.copy(
                vatId = null,
                iban = null,
                identifier = SchemedIdentifierDto(value = "4012345000009", schemeId = "0088"),
            ),
            recipient = realDto.recipient?.copy(vatId = null),
            // The export-profile decision (de.littletrotter.domain.invoice.ExportProfile.resolveExportProfileId) already
            // resolved and froze "XRechnung" into meta.profileId at finalization time, and
            // XmlExportServiceImpl.resolveProfile prefers that frozen value over re-deriving it —
            // by design, so the F4 gate and the real export can never disagree. Clearing it here is
            // what actually exercises the local vatId-based fallback this test is about.
            meta = realDto.meta.copy(profileId = null),
        )

        assertEquals("384", dto.typeCode)
        assertEquals("EN16931", mustang.resolveProfile(dto), "neither party carries a vatId in this stripped DTO — must resolve EN16931")

        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        val compact = Regex(">\\s+<").replace(xml, "><")

        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "Mustang/EN16931 storno document must be KoSIT VALID: ${findingsText(result)}")
        // The EN16931 profile is the one the CII Schematron validates in full (XRechnung is
        // covered by KoSIT only), so this is where the second rule set adds coverage.
        val schematronResult = schematron.validate(xml.toByteArray(Charsets.UTF_8))
        assertEquals(KositOutcome.VALID, schematronResult.outcome, "Mustang/EN16931 storno document must be CII Schematron VALID: ${schematronResult.findings}")

        // BR-DE-1/BG-16 — no IBAN in this DTO: MustangTransactionBuilder's hand-written TypeCode-42
        // fallback must still make the document conformant.
        assertTrue(compact.contains("<ram:SpecifiedTradeSettlementPaymentMeans><ram:TypeCode>42</ram:TypeCode></ram:SpecifiedTradeSettlementPaymentMeans>"), compact)
    }

    /**
     * ST-7 point 2 — the task spec assumed "the German rules (BR-DE) and the KoSIT rule set are
     * stricter about a credit note without a preceding-document reference" than EN 16931's own
     * schema (which leaves BG-3 optional). **That assumption was wrong for `381`, only partly right
     * for `384`, confirmed empirically both times**: at `381` (this test's original form) a real,
     * KoSIT-run document with no BG-3 came back fully `VALID`, zero findings. At `384` (this
     * migration), the schema/BR-CO layer still raises nothing — BG-3 stays structurally optional —
     * but **BR-DE-26 does specifically single out `384`**: "if BT-3 = 384, BG-3 should be present at
     * least once", surfaced as a non-blocking `WARNING`, never an `ERROR` (confirmed live: the
     * document is still `KoSIT VALID`, per this test's own title). This is a genuinely new, correct
     * piece of information the type-code migration surfaced — not a bug the migration introduced —
     * so the assertion below now accepts exactly that one expected `WARNING` instead of demanding
     * zero findings, rather than papering over it. The corrected finding on enforcement:
     * [InvoiceCancellationService]'s own `CancellationRejection.ORIGINAL_NUMBER_MISSING` guard remains the
     * **entire hard-blocking** enforcement mechanism for BT-25 in this system (BR-DE-26 is
     * advisory only) — not a belt-and-braces backstop for a validator rule, but the only thing that
     * actually stops a BG-3-less cancellation from being created. No code change follows from this (the
     * guard is already there, ST-3); this test exists so the corrected reasoning is pinned by a
     * real run rather than repeated as an assumption. Built by clearing the reference on a DTO
     * taken from a real cancellation document (`InvoiceCancellationService` itself never produces one without
     * BG-3, so this state cannot occur on the real path — the same "reconstructed, not organically
     * producible" situation as the previous test).
     *
     * The fixture and its assertions read `384`, not `381`;
     * unlike BG-3's plain schema-optionality (unaffected
     * by the type-code choice), the BR-DE-26 WARNING is itself a direct, expected consequence of
     * using 384 — 381 never triggered it.
     */
    @Test
    fun `Mustang - a 384 document without BG-3 stays KoSIT VALID - the ST-7 spec's BR-DE assumption was wrong`() {
        val originalId = QuarkusTransaction.requiringNew().call { buildSentOriginal("SCX3") }
        val (_, realDto) = cancellationAndMapToDto(originalId, "Leistung storniert (ST-7 BG-3-missing)")
        val dto = realDto.copy(references = realDto.references.copy(originalInvoiceNumber = null, originalInvoiceDate = null))

        assertEquals("384", dto.typeCode)
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        assertFalse(xml.contains("<ram:InvoiceReferencedDocument>"), "the fixture must genuinely omit BG-3 for this to test anything")

        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(
            KositOutcome.VALID, result.status,
            "empirically, no shipped rule blocks BG-3-less 384 documents — a real ruleset change " +
                "would need this test updated, not silently ignored: ${findingsText(result)}",
        )
        // BR-DE-26 — "a 384 document should carry BG-3 at least once" — is the one expected,
        // non-blocking finding here; anything else (or more than one finding) is a real regression.
        assertEquals(1, result.details.size, "expected exactly the one BR-DE-26 WARNING, not: ${findingsText(result)}")
        val finding = result.details.single()
        assertEquals("BR-DE-26", finding.ruleId, "unexpected finding: ${findingsText(result)}")
        assertEquals("WARNING", finding.severity, "BR-DE-26 must stay non-blocking: ${findingsText(result)}")
    }
}
