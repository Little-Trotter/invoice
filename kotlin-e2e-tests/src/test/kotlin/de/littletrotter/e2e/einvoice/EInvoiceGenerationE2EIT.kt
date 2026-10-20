package de.littletrotter.e2e.einvoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceReverseChargeService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.xpdf.validation.CiiSchematronValidator
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

/**
 * E-Rechnung Ende-zu-Ende: for each of the four representative invoices the task spec
 * enumerates (domestic standard-rate, EU reverse charge, an LU seller-registration case, a
 * per-diem/travel mix), finalizes it through the real [InvoiceLifecycleService] and generates its
 * e-invoice XML via the in-process Mustang generator ([XmlExportServiceImpl]). The XML is checked
 * against KoSIT and the CII Schematron (EN16931/EXTENDED) as two independent rule sets; the hybrid
 * PDF/A-3 invariant is checked on the exported PDF.
 *
 * Every invoice is built via direct [EntityManager] persistence (the established pattern in this
 * suite, see `B2bProfileE2EIT`/kotlin-invoice's `B2bInvoiceE2EIT`), with the two scenarios that
 * exercise real business logic (the reverse-charge toggle, the per-diem entry rebuild) driven
 * through the real [InvoiceLifecycleService] methods that own that logic
 * (`setReverseChargeDecision`, `addItem`) rather than being faked at the entity level.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class EInvoiceGenerationE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var reverseChargeService: InvoiceReverseChargeService

    @Inject
    lateinit var tripService: de.littletrotter.application.invoice.TripService

    // finding-fixes (2026-08-31) — every fixture below carries a fixed 30-day invoiceDate/dueDate
    // gap (2026-02-10 -> 2026-03-12). InvoiceLifecycleService.transition now stamps invoiceDate to
    // "today" at ISSUED and Invoice.recomputeDueDateForSend preserves that same 30-day gap (no
    // payment term is set on these directly-EM-persisted fixtures, so the "preserved delta" branch
    // applies, not the payment-term-snapshot one) — so the expected BT-9 due date is relative to
    // the real clock, not the fixture's own literal 2026-03-12 draft value.
    private val expectedDueDateIso102 = E2eBusinessDay.today().plusDays(30).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    companion object {
        /** The real, embedded KoSIT validator (org.kosit:validator) — no CDI container needed
         *  outside a Quarkus test (it only needs its own `@PostConstruct init()` called once);
         *  shared across every test method in this class since loading the XRechnung 3.0.2
         *  scenario ZIP is comparatively expensive and it carries no per-test state. */
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

    private fun mustangXml(dto: InvoiceDto): String = String(mustang.generate(dto), Charsets.UTF_8)

    // ─── One shared assertion entry point per generated XML (the "no drift" helper,
    //     applied identically regardless of which engine produced the bytes) ───────────────────

    /** Everything a representative case expects on the wire, independent of which engine
     *  generated the XML. */
    private data class CaseExpectation(
        val label: String,
        val sellerVatId: String,
        val buyerName: String,
        val buyerVatId: String?,
        val buyerCountry: String,
        val buyerPostcode: String,
        val buyerCity: String,
        val buyerReference: String,
        val dueDateIso102: String,
        val netTotal: BigDecimal,
        val vatTotal: BigDecimal,
        val grossTotal: BigDecimal,
        val taxBreakdown: List<EInvoiceXml.TaxBreakdownLine>,
    )

    /** Asserts the EN-16931 core on [xml] against [exp]: totals (BT-106..BT-112,
     *  BT-115), the per-category tax breakdown (BT-116..BT-121), payment due date, seller VAT-id
     *  (BT-31, the [de.littletrotter.shared.SellerVatIdResolutionKt] contract) and buyer fields. */
    private fun assertCore(xml: String, exp: CaseExpectation) {
        val l = exp.label

        // BT-31 — seller VAT-id, via resolveActiveSellerVatId (SellerVatIdResolution contract).
        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", exp.sellerVatId, "$l/BT-31",
        )

        // Buyer fields.
        EInvoiceXml.assertText(xml, "//ram:BuyerTradeParty/ram:Name", exp.buyerName, "$l/BT-44")
        if (exp.buyerVatId != null) {
            EInvoiceXml.assertText(
                xml, "//ram:BuyerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", exp.buyerVatId, "$l/BT-48",
            )
        } else {
            EInvoiceXml.assertAbsent(xml, "//ram:BuyerTradeParty/ram:SpecifiedTaxRegistration", "$l/BT-48")
        }
        EInvoiceXml.assertText(xml, "//ram:BuyerTradeParty/ram:PostalTradeAddress/ram:CountryID", exp.buyerCountry, "$l/BT-55")
        EInvoiceXml.assertText(xml, "//ram:BuyerTradeParty/ram:PostalTradeAddress/ram:PostcodeCode", exp.buyerPostcode, "$l/BT-53")
        EInvoiceXml.assertText(xml, "//ram:BuyerTradeParty/ram:PostalTradeAddress/ram:CityName", exp.buyerCity, "$l/BT-52")

        // BT-10 — buyer reference.
        EInvoiceXml.assertText(xml, "//ram:ApplicableHeaderTradeAgreement/ram:BuyerReference", exp.buyerReference, "$l/BT-10")

        // Payment terms / BT-9 due date.
        EInvoiceXml.assertText(
            xml, "//ram:SpecifiedTradePaymentTerms/ram:DueDateDateTime/udt:DateTimeString", exp.dueDateIso102, "$l/BT-9",
        )

        // Totals — BT-106/BT-109/BT-110/BT-112/BT-115.
        val summationPath = "//ram:SpecifiedTradeSettlementHeaderMonetarySummation"
        EInvoiceXml.assertAmount(xml, "$summationPath/ram:LineTotalAmount", exp.netTotal, "$l/BT-106")
        EInvoiceXml.assertAmount(xml, "$summationPath/ram:TaxBasisTotalAmount", exp.netTotal, "$l/BT-109")
        EInvoiceXml.assertAmount(xml, "$summationPath/ram:TaxTotalAmount", exp.vatTotal, "$l/BT-110")
        EInvoiceXml.assertAmount(xml, "$summationPath/ram:GrandTotalAmount", exp.grossTotal, "$l/BT-112")
        EInvoiceXml.assertAmount(xml, "$summationPath/ram:DuePayableAmount", exp.grossTotal, "$l/BT-115")
        // BT-107/BT-108 (document-level allowance/charge totals) — none of the four representative
        // cases carries an allowance or a charge; Mustang always emits the element (0.00) rather
        // than omitting it, so the assertion is "present and zero", not "absent". BT-111 (VAT
        // total in accounting currency) is a dual-currency field; this system is EUR-only end to
        // end (see kotlin-invoice's InvoiceItemUnit KDoc), so at most one TaxTotalAmount is ever
        // emitted — a second one would be BT-111 and must never appear.
        if (EInvoiceXml.exists(xml, "$summationPath/ram:AllowanceTotalAmount")) {
            EInvoiceXml.assertAmount(xml, "$summationPath/ram:AllowanceTotalAmount", BigDecimal.ZERO, "$l/BT-107")
        }
        if (EInvoiceXml.exists(xml, "$summationPath/ram:ChargeTotalAmount")) {
            EInvoiceXml.assertAmount(xml, "$summationPath/ram:ChargeTotalAmount", BigDecimal.ZERO, "$l/BT-108")
        }
        EInvoiceXml.assertAbsent(xml, "$summationPath/ram:TaxTotalAmount[@currencyID][2]", "$l/BT-111")

        // BT-116..BT-121 — one breakdown line per VAT category actually present.
        val actual = EInvoiceXml.taxBreakdown(xml).associateBy { it.categoryCode }
        assertEquals(exp.taxBreakdown.map { it.categoryCode }.toSet(), actual.keys, "$l: tax breakdown categories")
        exp.taxBreakdown.forEach { expectedLine ->
            val actualLine = actual.getValue(expectedLine.categoryCode)
            assertEquals(0, expectedLine.basisAmount.compareTo(actualLine.basisAmount), "$l/BT-116 (${expectedLine.categoryCode})")
            assertEquals(0, expectedLine.calculatedAmount.compareTo(actualLine.calculatedAmount), "$l/BT-117 (${expectedLine.categoryCode})")
            assertEquals(0, expectedLine.ratePercent.compareTo(actualLine.ratePercent), "$l/BT-119 (${expectedLine.categoryCode})")
            assertEquals(expectedLine.exemptionReasonCode, actualLine.exemptionReasonCode, "$l/BT-121 (${expectedLine.categoryCode})")
            if (expectedLine.exemptionReasonText != null) {
                assertEquals(expectedLine.exemptionReasonText, actualLine.exemptionReasonText, "$l/BT-120 (${expectedLine.categoryCode})")
            }
        }
    }

    private fun assertKositValid(xml: String, label: String) {
        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(
            KositOutcome.VALID, result.status,
            "$label: KoSIT must be VALID, was ${result.status} — ${result.details}",
        )
        val schematronResult = schematron.validate(xml.toByteArray(Charsets.UTF_8))
        assertEquals(
            KositOutcome.VALID, schematronResult.outcome,
            "$label: CII Schematron must be VALID, was ${schematronResult.outcome} — ${schematronResult.findings}",
        )
    }

    private fun assertIdempotent(xmlA: String, xmlB: String, label: String) {
        assertTrue(
            EInvoiceXml.semanticallyEqual(xmlA, xmlB),
            "$label: regenerating the same invoice must be semantically identical (timestamps excluded by contract; " +
                "none of the fields either export route emits are wall-clock-derived — verified empirically here)",
        )
    }

    // ─── Scenario 1 — domestic standard-rate (S, 19 %) ───────────────────────────────────────

    private fun buildDomesticServiceInvoice(tag: String, note: String? = null): Pair<Long, CaseExpectation> {
        val sender = aSenderProfile {
            name = "Little Trotter Consulting GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Musterkunde AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE987654321")
            routingId = "04011000-1234512345-06"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = anInvoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
            // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
            // suite's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply. Also
            // satisfies the § 14 Abs. 4 Nr. 6 UStG send-date guard (invoiceDate is stamped to
            // "today" at ISSUED — same-day service is always sendable).
            deliveryDate = E2eBusinessDay.today()
            references.buyerReference = "PO-$tag-1"
            freeText = note
        }
        invoice.addItem(
            anInvoiceItem().edit {
                positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Beratung"
                quantity = BigDecimal("3"); unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("150.00")
                sortOrder = 0
            }.also { it.applyTaxRule(taxRate) },
        )
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val net = Money.of("450.00").amount
        val vat = Money.of("450.00").percentage(BigDecimal("19.00")).amount
        val gross = net + vat

        return invoice.id!! to CaseExpectation(
            label = "domestic-S",
            sellerVatId = "DE123456789",
            buyerName = "Musterkunde AG",
            buyerVatId = "DE987654321",
            buyerCountry = "DE",
            buyerPostcode = "20095",
            buyerCity = "Hamburg",
            buyerReference = "PO-$tag-1",
            dueDateIso102 = expectedDueDateIso102,
            netTotal = net,
            vatTotal = vat,
            grossTotal = gross,
            taxBreakdown = listOf(
                EInvoiceXml.TaxBreakdownLine("S", net, vat, BigDecimal("19.00"), null, null),
            ),
        )
    }

    // ─── Scenario 2 — EU reverse charge (AE, 0 %) ────────────────────────────────────────────────
    //
    // RG3-F1 (fixed, PO decision 2026-08-30, option a) — InvoiceLifecycleService
    // .setReverseChargeDecision no longer touches invoice.invoiceFormat (that automatic
    // INTERNATIONAL default collided with chk_invoice_format_requires_seller_vat_registration for
    // every one of RC's own supported target countries, since RC eligibility requires
    // sellerVatRegistrationCountry == null while that CHECK demanded the opposite whenever
    // invoice_format = 1). With the coupling removed, the real production toggle can now drive
    // this fixture directly instead of hand-simulating its effect: the item starts under a
    // placeholder standard rate (mirroring what addItem would resolve to before RC is ever
    // considered) and setReverseChargeDecision itself applies the real, seeded EU_B2B_RC_SERVICE
    // rule and flips Invoice.reverseCharge.
    private fun buildReverseChargeInvoice(tag: String): Pair<Long, CaseExpectation> {
        val sender = aSenderProfile {
            name = "Little Trotter Consulting GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Dupont Conseil S.A.R.L."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("FR12345678901")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 1", "75001", "Paris", Country.FR, true))
        em.persist(sender); em.persist(recipient)

        // Placeholder pre-toggle rate — what a freshly added SERVICE position would carry before
        // Reverse Charge is ever turned on (addItem's own PRODUCT/SERVICE branch would resolve a
        // German-default rate here); setReverseChargeDecision below is what actually replaces it.
        val placeholderRate = TaxRate().edit {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(placeholderRate)

        val invoice = anInvoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
            // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
            // suite's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply. Also
            // satisfies the § 14 Abs. 4 Nr. 6 UStG send-date guard (invoiceDate is stamped to
            // "today" at ISSUED — same-day service is always sendable).
            deliveryDate = E2eBusinessDay.today()
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            anInvoiceItem().edit {
                positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Beratung"
                quantity = BigDecimal("5"); unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("100.00")
                sortOrder = 0
            }.also { it.applyTaxRule(placeholderRate) },
        )
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        val invoiceId = invoice.id!!

        // The real production path: resolves Invoice.reverseChargeEligibility() itself (the FR
        // business recipient with a VERIFIED VAT-id qualifies), applies the seeded
        // EU_B2B_RC_SERVICE rule to every item, and sets Invoice.reverseCharge = true — no longer
        // colliding with chk_invoice_format_requires_seller_vat_registration since invoiceFormat
        // is left alone (stays GERMAN, which the CHECK always allows regardless of country).
        reverseChargeService.setReverseChargeDecision(invoiceId, invoice.version, true)
        em.flush()

        val net = Money.of("500.00").amount
        val vat = BigDecimal("0.00")
        val gross = net

        return invoiceId to CaseExpectation(
            label = "reverse-charge",
            sellerVatId = "DE123456789",
            buyerName = "Dupont Conseil S.A.R.L.",
            buyerVatId = "FR12345678901",
            buyerCountry = "FR",
            buyerPostcode = "75001",
            buyerCity = "Paris",
            buyerReference = "PO-$tag-1",
            dueDateIso102 = expectedDueDateIso102,
            netTotal = net,
            vatTotal = vat,
            grossTotal = gross,
            taxBreakdown = listOf(
                EInvoiceXml.TaxBreakdownLine(
                    "AE", net, vat, BigDecimal("0.00"), "VATEX-EU-AE",
                    "Reverse Charge: Der Leistungsempfänger schuldet die Umsatzsteuer gemäß Art. 196 MwStSystRL / § 13b UStG.",
                ),
            ),
        )
    }

    // ─── Scenario 3 — LU seller-registration case (BT-31 via SellerVatIdResolution) ─────────────

    private fun buildLuRegistrationInvoice(tag: String): Pair<Long, CaseExpectation> {
        val sender = aSenderProfile {
            name = "Little Trotter Consulting GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        sender.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), E2eBusinessDay.today())
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Michels Luxembourg S.A."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("LU21416127")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_LU_STD_17"; category = "S"; name = "TVA 17 %"; rate = TaxRatePercent(BigDecimal("17.00"))
            country = Country.LU; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = anInvoice().edit {
            senderProfile = sender
            this.recipient = recipient
            sellerVatRegistrationCountry = Country.LU
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
            // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
            // suite's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply. Also
            // satisfies the § 14 Abs. 4 Nr. 6 UStG send-date guard (invoiceDate is stamped to
            // "today" at ISSUED — same-day service is always sendable).
            deliveryDate = E2eBusinessDay.today()
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            anInvoiceItem().edit {
                positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Consulting"
                quantity = BigDecimal("4"); unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("90.00")
                sortOrder = 0
            }.also { it.applyTaxRule(taxRate) },
        )
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val net = Money.of("360.00").amount
        val vat = Money.of("360.00").percentage(BigDecimal("17.00")).amount
        val gross = net + vat

        return invoice.id!! to CaseExpectation(
            label = "lu-registration",
            sellerVatId = "LU12345678",
            buyerName = "Michels Luxembourg S.A.",
            buyerVatId = "LU21416127",
            buyerCountry = "LU",
            buyerPostcode = "1000",
            buyerCity = "Luxembourg",
            buyerReference = "PO-$tag-1",
            dueDateIso102 = expectedDueDateIso102,
            netTotal = net,
            vatTotal = vat,
            grossTotal = gross,
            taxBreakdown = listOf(
                EInvoiceXml.TaxBreakdownLine("S", net, vat, BigDecimal("17.00"), null, null),
            ),
        )
    }

    // ─── Scenario 4 — per-diem/travel mix, via the real InvoiceLifecycleService.addItem paths ───

    private data class PerDiemFixture(val invoiceId: Long, val perDiemRateId: Long, val expectation: CaseExpectation)

    private fun buildPerDiemTravelMixInvoice(tag: String): PerDiemFixture {
        val sender = aSenderProfile {
            name = "Little Trotter Consulting GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Musterkunde AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE987654321")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        val perDiemRate = PerDiemRate().edit {
            country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
            inlandRules = true; isActive = true; source = "BMF-Schreiben 2026 (E2E-Fixture)"
            validFrom = LocalDate.of(2024, 1, 1)
        }
        em.persist(taxRate); em.persist(perDiemRate); em.flush()

        val invoice = anInvoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
            // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
            // suite's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply. Also
            // satisfies the § 14 Abs. 4 Nr. 6 UStG send-date guard (invoiceDate is stamped to
            // "today" at ISSUED — same-day service is always sendable).
            deliveryDate = E2eBusinessDay.today()
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            anInvoiceItem().edit {
                positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Beratung vor Ort"
                quantity = BigDecimal("2"); unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("120.00")
                sortOrder = 0
            }.also { it.applyTaxRule(taxRate) },
        )
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        val invoiceId = invoice.id!!

        // NOTE: the two TRIP positions are added by the caller (see `addTravelAndPerDiemItems`),
        // AFTER this method's own transaction has committed — for the same reason
        // `buildReverseChargeInvoice` defers its own service call: a nested
        // QuarkusTransaction.requiringNew() here could not see this not-yet-committed invoice.

        // net = 240.00 (service) + 36.00 (mileage TripExpense: 120 km x 0.30 EUR) + 42.00
        // (per-diem days: 28 + 14) = 318.00. A single real TRIP position carries both
        // the mileage expense (a TripExpense child row, kind MILEAGE) and the per-diem days in the
        // same trip (see `addTravelAndPerDiemItems`, driven through TripService.addTrip) —
        // InvoiceItem.calculateAmounts() derives the position's net as `tripFixedNetPrice ?:
        // calculatedTripNet()` (Σ tripExpenses + Σ perDiemEntries), which is 36.00 + 42.00 = 78.00
        // for this one trip.
        val net = BigDecimal("318.00")
        val vat = Money.of(net).percentage(BigDecimal("19.00")).amount
        val gross = net + vat

        return PerDiemFixture(
            invoiceId = invoiceId,
            perDiemRateId = perDiemRate.id!!,
            expectation = CaseExpectation(
                label = "per-diem-travel-mix",
                sellerVatId = "DE123456789",
                buyerName = "Musterkunde AG",
                buyerVatId = "DE987654321",
                buyerCountry = "DE",
                buyerPostcode = "20095",
                buyerCity = "Hamburg",
                buyerReference = "PO-$tag-1",
                dueDateIso102 = expectedDueDateIso102,
                netTotal = net,
                vatTotal = vat,
                grossTotal = gross,
                taxBreakdown = listOf(
                    EInvoiceXml.TaxBreakdownLine("S", net, vat, BigDecimal("19.00"), null, null),
                ),
            ),
        )
    }

    // ─── Fixture-to-DTO plumbing ──────────────────────────────────────────────────────────────

    /** A single real business trip: the mileage line is a [TripExpenseCommand] (kind
     *  MILEAGE, a real `TripExpense` child row) and the per-diem days live inside the SAME trip,
     *  not a second TRIP position — one [TripCommand] carries both, driven through the real
     *  [de.littletrotter.application.invoice.TripService.addTrip] so inheritance/entry-building
     *  logic runs for real, not re-implemented at the entity level. Its own top-level transaction,
     *  after the fixture's own persistence transaction (which already added the main SERVICE
     *  position) has committed. */
    private fun addTravelAndPerDiemItems(invoiceId: Long, perDiemRateId: Long) {
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                de.littletrotter.application.invoice.TripCommand(
                    title = "Dienstreise Hamburg",
                    expenses = listOf(
                        de.littletrotter.application.invoice.TripExpenseCommand(
                            kind = de.littletrotter.domain.invoice.TripExpenseKind.MILEAGE,
                            description = "Kilometergeld (120 km à 0,30 EUR)",
                            quantity = BigDecimal("120"),
                            unit = InvoiceItemUnit.KILOMETER,
                            unitPriceNet = Money.of("0.30"),
                            sortOrder = 1,
                        ),
                    ),
                    perDiemDays = listOf(
                        de.littletrotter.application.invoice.PerDiemEntryCommand(
                            date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false,
                            breakfast = false, lunch = false, dinner = false,
                            description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId,
                        ),
                        de.littletrotter.application.invoice.PerDiemEntryCommand(
                            date = LocalDate.of(2026, 2, 9), isArrivalDeparture = true,
                            breakfast = false, lunch = false, dinner = false,
                            description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId,
                        ),
                    ),
                ),
            )
        }
    }

    private fun finalizeAndMapToDto(invoiceId: Long): InvoiceDto {
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return QuarkusTransaction.requiringNew().call<InvoiceDto> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "the fixture must actually finalize for this suite to mean anything")
            InvoiceDtoMapper().mapToDto(invoice)
        }
    }

    // ─── Hybrid PDF/A-3 invariant — implemented once here, via the Mustang route ─────────

    private fun minimalRenderedInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E RG-3 base invoice page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** Checks whether [pdfBytes] carries an embedded factur-x.xml/xrechnung.xml attachment — the
     *  hybrid invariant, same detection [de.littletrotter.xpdf.impl.PdfExportServiceImplTest]
     *  already uses. */
    private fun pdfHasEmbeddedXmlFile(pdfBytes: ByteArray): Boolean = try {
        Loader.loadPDF(pdfBytes).use { doc ->
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles
            val fileNames = embeddedFiles?.names
            fileNames != null && (fileNames.contains("factur-x.xml") || fileNames.contains("xrechnung.xml"))
        }
    } catch (ex: Exception) {
        false
    }

    /** Drives the real [PdfExportServiceImpl.performExport] (Mustang route) with a stubbed render
     *  step only (per the task's own allowance: "render step may be stubbed only where the PDF
     *  bytes themselves are not asserted" — the *visible* layout is never checked here, only the
     *  embedded-XML attachment structure ZUGFeRDExporterFromA3 builds around it) and returns the
     *  final, stored PDF bytes. */
    private fun exportHybridPdf(dto: InvoiceDto): ByteArray {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalRenderedInvoicePage())
        val store = InMemoryDocumentStore()

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mustang,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = object : de.littletrotter.shared.KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = kosit.validate(xml, timeoutMs)
            },
        )
        service.performExport(dto, "e2e-rg3-${dto.invoiceNumber.value}")
        return store.objects.entries.single { it.key.endsWith(".pdf") }.value
    }

    // ─── Test matrix — 4 representative cases, exhaustively (no sampling) ───────────

    @Test
    fun `domestic S invoice - Mustang engine - EN-16931 core, KoSIT, idempotence and PDF-A-3 hybrid invariant`() {
        val (id, exp) = QuarkusTransaction.requiringNew().call<Pair<Long, CaseExpectation>> { buildDomesticServiceInvoice("DSM") }
        val dto = finalizeAndMapToDto(id)

        val xml1 = mustangXml(dto)
        assertCore(xml1, exp)
        assertKositValid(xml1, "${exp.label}/Mustang")
        assertIdempotent(xml1, mustangXml(dto), "${exp.label}/Mustang")
        assertTrue(pdfHasEmbeddedXmlFile(exportHybridPdf(dto)), "${exp.label}/Mustang: hybrid PDF/A-3 must carry the embedded XML attachment")
    }

    // T2 — BT-22 carries the plain rendering of the free text: no ** / ++ / ~~ delimiters, list prefixes kept.
    @Test
    fun `free text with markup - Mustang engine - BT-22 note is the plain rendering`() {
        val freeText = "**Wichtig** ++unterstrichen++ ~~alt~~\n1. Eins\n    - Unterpunkt"
        val (id, _) = QuarkusTransaction.requiringNew().call<Pair<Long, CaseExpectation>> { buildDomesticServiceInvoice("FTM", freeText) }
        val dto = finalizeAndMapToDto(id)

        val notes = EInvoiceXml.textList(mustangXml(dto), "//ram:IncludedNote/ram:Content")

        assertEquals(listOf("Wichtig unterstrichen alt\n1. Eins\n    - Unterpunkt"), notes)
    }

    @Test
    fun `reverse charge invoice - Mustang engine - EN-16931 core, KoSIT, idempotence and PDF-A-3 hybrid invariant`() {
        val (id, exp) = QuarkusTransaction.requiringNew().call<Pair<Long, CaseExpectation>> { buildReverseChargeInvoice("RCM") }
        val dto = finalizeAndMapToDto(id)

        val xml1 = mustangXml(dto)
        assertCore(xml1, exp)
        assertKositValid(xml1, "${exp.label}/Mustang")
        assertIdempotent(xml1, mustangXml(dto), "${exp.label}/Mustang")
        assertTrue(pdfHasEmbeddedXmlFile(exportHybridPdf(dto)), "${exp.label}/Mustang: hybrid PDF/A-3 must carry the embedded XML attachment")
    }

    @Test
    fun `LU registration invoice - Mustang engine - EN-16931 core, KoSIT, idempotence and PDF-A-3 hybrid invariant`() {
        val (id, exp) = QuarkusTransaction.requiringNew().call<Pair<Long, CaseExpectation>> { buildLuRegistrationInvoice("LUM") }
        val dto = finalizeAndMapToDto(id)

        val xml1 = mustangXml(dto)
        assertCore(xml1, exp)
        assertKositValid(xml1, "${exp.label}/Mustang")
        assertIdempotent(xml1, mustangXml(dto), "${exp.label}/Mustang")
        assertTrue(pdfHasEmbeddedXmlFile(exportHybridPdf(dto)), "${exp.label}/Mustang: hybrid PDF/A-3 must carry the embedded XML attachment")
    }

    @Test
    fun `per-diem travel mix invoice - Mustang engine - EN-16931 core, KoSIT, idempotence and PDF-A-3 hybrid invariant`() {
        val fixture = QuarkusTransaction.requiringNew().call<PerDiemFixture> { buildPerDiemTravelMixInvoice("PDM") }
        addTravelAndPerDiemItems(fixture.invoiceId, fixture.perDiemRateId)
        val (id, exp) = fixture.invoiceId to fixture.expectation
        val dto = finalizeAndMapToDto(id)

        val xml1 = mustangXml(dto)
        assertCore(xml1, exp)
        assertKositValid(xml1, "${exp.label}/Mustang")
        assertIdempotent(xml1, mustangXml(dto), "${exp.label}/Mustang")
        assertTrue(pdfHasEmbeddedXmlFile(exportHybridPdf(dto)), "${exp.label}/Mustang: hybrid PDF/A-3 must carry the embedded XML attachment")
    }
}
