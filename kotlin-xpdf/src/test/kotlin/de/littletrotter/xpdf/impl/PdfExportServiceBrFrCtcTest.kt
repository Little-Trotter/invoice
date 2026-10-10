package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.FixtureBaseRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.facturx.FacturXValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The `BR_FR_CTC` stage's wiring into
 * `performFacturXExport`: gating (only a French recipient, decision 7/8), that it never appears
 * for Mustang, and the `FACTURX-FR-RULES-NOT-RUN` caveat transition
 * ("once BR_FR_CTC actually ran in the same job, the caveat is dropped").
 *
 * Uses the real, default-constructed [de.littletrotter.facturx.BrFrCtcValidator] (not
 * a mock) fed the real fixture CII documents under
 * `src/test/resources/facturx/br-fr-ctc/` — the same fixtures `BrFrCtcValidatorTest` (now in
 * kotlin-facturx) exercises directly — via a fake [FacturXEngine] whose `generateXml()` returns
 * those bytes, so this class proves the *wiring*, not a re-proof of the rule set itself.
 *
 * XPR-3 — `golden-fr-cii.xml`/`violating-fr-cii.xml` are deliberately duplicated here (this
 * module's own `src/test/resources/facturx/br-fr-ctc/`) rather than shared with kotlin-facturx's
 * copy: this class stays in kotlin-xpdf (it tests [PdfExportServiceImpl]'s own wiring, not the
 * Factur-X client library), so its test resources cannot reach across the module boundary into
 * kotlin-facturx's test-resources, which are private to that module's own test classpath.
 * `clean-fr-cii.xml` stayed kotlin-facturx-only — this class never reads it.
 */
class PdfExportServiceBrFrCtcTest {

    private fun fixtureBytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("facturx/br-fr-ctc/$name")) {
            "test fixture not found: $name"
        }.use { it.readBytes() }

    private fun invoiceWithRecipientCountry(country: String?, number: String = "INV-BRFRCTC-1"): InvoiceDto =
        InvoiceDto(
            invoiceNumber = InvoiceNumberDto(number),
            items = emptyList(),
            recipient = PartyDto(name = "Buyer", country = country),
        )

    private fun invoiceWithRecipientAndSellerCountry(recipientCountry: String?, sellerCountry: String?, number: String = "INV-BRFRCTC-1"): InvoiceDto =
        InvoiceDto(
            invoiceNumber = InvoiceNumberDto(number),
            items = emptyList(),
            recipient = PartyDto(name = "Buyer", country = recipientCountry),
            senderProfile = PartyDto(name = "Seller", country = sellerCountry),
        )

    private fun makeKositPort(): KositValidatorPort {
        val port: KositValidatorPort = mock()
        whenever(port.validate(any(), any())).thenReturn(KositValidationResult(status = KositOutcome.VALID, summary = "kosit"))
        return port
    }

    private fun makeStore(): DocumentStore {
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        return store
    }

    private fun makeReportRenderer(): BasePdfRendererPort = FixtureBaseRenderer()

    /** The FACTURX-FR-RULES-NOT-RUN caveat as HttpFacturXEngineClient/FacturXValidationResponseMapper
     *  actually shapes it — used here to prove PdfExportServiceImpl drops it exactly when
     *  BR_FR_CTC ran, not to re-test the mapper itself (FacturXValidationResponseMapperTest already
     *  does that). */
    private fun frRulesNotRunCaveat() = ValidationMessage(
        severity = "warning",
        message = "The engine's BR-FR-CTC layer only runs for a French seller.",
        ruleId = "FACTURX-FR-RULES-NOT-RUN",
        stage = "FACTURX",
        validator = "facturx-engine",
    )

    private class FakeFacturXEngine(
        private val xmlOutcome: FacturXEngineOutcome<ByteArray>,
        private val validation: FacturXValidation,
    ) : FacturXEngine {
        override fun health(): Boolean = true
        override fun generateXml(invoice: InvoiceDto): FacturXEngineOutcome<ByteArray> = xmlOutcome
        override fun convert(basePdf: ByteArray, invoice: InvoiceDto): FacturXEngineOutcome<ByteArray> =
            FacturXEngineOutcome.Success(basePdf)
        override fun validate(pdf: ByteArray, invoice: InvoiceDto): FacturXValidation = validation
    }

    private fun stagesOf(outcome: FacturXExportOutcome): List<de.littletrotter.shared.ValidationStageResult> {
        assertTrue(outcome is FacturXExportOutcome.Success, "expected Success, got: $outcome")
        val payload = KositReportPayload.decode((outcome as FacturXExportOutcome.Success).result.kositReport)
        assertNotNull(payload)
        return payload!!.validationRun!!.stages
    }

    // ── gating: French recipient → BR_FR_CTC present ──────────────────────────────────────────

    @Test
    fun `French recipient produces a BR_FR_CTC stage and drops the FACTURX-FR-RULES-NOT-RUN caveat`() {
        val engine = FakeFacturXEngine(
            xmlOutcome = FacturXEngineOutcome.Success(fixtureBytes("violating-fr-cii.xml")),
            validation = FacturXValidation(KositOutcome.VALID, listOf(frRulesNotRunCaveat())),
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = makeStore(),
            kositValidator = makeKositPort(),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(invoiceWithRecipientCountry("FR"), "job-brfrctc-fr")
        val stages = stagesOf(outcome)

        val brFrCtcStage = stages.firstOrNull { it.stage == "BR_FR_CTC" }
        assertNotNull(brFrCtcStage, "a French recipient must produce a BR_FR_CTC stage")
        assertFalse(brFrCtcStage!!.skipped)
        assertEquals(KositOutcome.VALID, brFrCtcStage.outcome, "advisory stage must never be worse than VALID")
        // The violating fixture's two non-suppressed findings must reach the actual report.
        assertEquals(setOf("BR-FR-16_BT-152", "BR-FR-16_BT-119"), brFrCtcStage.findings.map { it.ruleId }.toSet())
        brFrCtcStage.findings.forEach { assertEquals("warning", it.severity) }

        // The described transition: the caveat is redundant once BR_FR_CTC actually ran.
        val facturxStage = stages.first { it.stage == "FACTURX" }
        assertTrue(
            facturxStage.findings.none { it.ruleId == "FACTURX-FR-RULES-NOT-RUN" },
            "FACTURX-FR-RULES-NOT-RUN must be dropped once BR_FR_CTC ran in the same job",
        )
    }

    @Test
    fun `golden FR document for a French recipient produces an empty BR_FR_CTC stage, not an absent one`() {
        val engine = FakeFacturXEngine(
            xmlOutcome = FacturXEngineOutcome.Success(fixtureBytes("golden-fr-cii.xml")),
            validation = FacturXValidation(KositOutcome.VALID, emptyList()),
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = makeStore(),
            kositValidator = makeKositPort(),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(invoiceWithRecipientCountry("FR"), "job-brfrctc-golden")
        val stages = stagesOf(outcome)

        val brFrCtcStage = stages.firstOrNull { it.stage == "BR_FR_CTC" }
        assertNotNull(brFrCtcStage, "the stage must be present (and ran) even when it finds nothing")
        assertFalse(brFrCtcStage!!.skipped)
        assertTrue(brFrCtcStage.findings.isEmpty())
    }

    @Test
    fun `BR_FR_CTC runs with the seller's own country when a senderProfile is present, not just for a null seller`() {
        val engine = FakeFacturXEngine(
            xmlOutcome = FacturXEngineOutcome.Success(fixtureBytes("golden-fr-cii.xml")),
            validation = FacturXValidation(KositOutcome.VALID, emptyList()),
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = makeStore(),
            kositValidator = makeKositPort(),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(
            invoiceWithRecipientAndSellerCountry(recipientCountry = "FR", sellerCountry = "DE"),
            "job-brfrctc-with-seller",
        )
        val stages = stagesOf(outcome)

        val brFrCtcStage = stages.firstOrNull { it.stage == "BR_FR_CTC" }
        assertNotNull(brFrCtcStage, "a French recipient with a known (non-null) seller country must still run BR_FR_CTC")
        assertFalse(brFrCtcStage!!.skipped)
    }

    // ── gating: non-FR / LU / BE recipient → stage absent, caveat stays ───────────────────────

    private fun assertNoBrFrCtcStageAndCaveatStays(recipientCountry: String?) {
        val engine = FakeFacturXEngine(
            xmlOutcome = FacturXEngineOutcome.Success(fixtureBytes("golden-fr-cii.xml")),
            validation = FacturXValidation(KositOutcome.VALID, listOf(frRulesNotRunCaveat())),
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = makeStore(),
            kositValidator = makeKositPort(),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(
            invoiceWithRecipientCountry(recipientCountry, "INV-BRFRCTC-$recipientCountry"),
            "job-brfrctc-${recipientCountry ?: "null"}",
        )
        val stages = stagesOf(outcome)

        assertNull(stages.firstOrNull { it.stage == "BR_FR_CTC" }, "recipient=$recipientCountry must not produce a BR_FR_CTC stage at all")
        val facturxStage = stages.first { it.stage == "FACTURX" }
        assertTrue(
            facturxStage.findings.any { it.ruleId == "FACTURX-FR-RULES-NOT-RUN" },
            "the caveat must stay when BR_FR_CTC did not run (recipient=$recipientCountry)",
        )
    }

    @Test
    fun `German recipient never produces a BR_FR_CTC stage`() = assertNoBrFrCtcStageAndCaveatStays("DE")

    @Test
    fun `null (Germany) recipient never produces a BR_FR_CTC stage`() = assertNoBrFrCtcStageAndCaveatStays(null)

    @Test
    fun `Luxembourg recipient never produces a BR_FR_CTC stage (decision 8 - purely French)`() =
        assertNoBrFrCtcStageAndCaveatStays("LU")

    @Test
    fun `Belgian recipient never produces a BR_FR_CTC stage (decision 8 - purely French)`() =
        assertNoBrFrCtcStageAndCaveatStays("BE")

    // ── Mustang route: never even considers BR_FR_CTC ─────────────────────────────────────────

    @Test
    fun `Mustang export never produces a BR_FR_CTC stage, even for a French recipient`() {
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(de.littletrotter.xpdf.validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = makeReportRenderer(),
            documentStore = makeStore(),
            kositValidator = makeKositPort(),
        )

        val result = service.performExport(invoiceWithRecipientCountry("FR"), "job-mustang-brfrctc")

        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload)
        assertNull(payload!!.validationRun!!.stages.firstOrNull { it.stage == "BR_FR_CTC" })
    }

    // ── skipValidation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `skipValidation reports BR_FR_CTC skipped for a French recipient, not absent`() {
        val engine = FakeFacturXEngine(
            xmlOutcome = FacturXEngineOutcome.Success(fixtureBytes("golden-fr-cii.xml")),
            validation = FacturXValidation(KositOutcome.VALID, emptyList()),
        )
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock(),
            reportRenderer = makeReportRenderer(),
            documentStore = makeStore(),
            kositValidator = makeKositPort(),
            facturXEngine = engine,
        )

        val outcome = service.performFacturXExport(
            invoiceWithRecipientCountry("FR"), "job-brfrctc-skip", skipValidation = true,
        )
        val stages = stagesOf(outcome)

        val brFrCtcStage = stages.firstOrNull { it.stage == "BR_FR_CTC" }
        assertNotNull(brFrCtcStage, "skipValidation must still report the stage as present-but-skipped")
        assertTrue(brFrCtcStage!!.skipped)
        assertEquals("skipValidation", brFrCtcStage.skipReason)
    }
}
