package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.facturx.HttpFacturXEngineClient
import de.littletrotter.facturx.testsupport.FacturXEngineTestResource
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The strongest available proof: a `FACTURX` job
 * driven through the **actual job-path method**
 * ([PdfExportServiceImpl.performFacturXExport]) against the real pinned engine container
 * (`facturx-engine:2.1.0-arm64-f157670`), with the real embedded KoSIT validator
 * ([EmbeddedKositValidator]) — not a mock — checking the engine's real `generateXml` output.
 * [de.littletrotter.facturx.FacturXEngineContractIT] already proves the raw [HttpFacturXEngineClient]
 * adapter works against this container; this class proves the *job path wiring on top of
 * it*: `renderBase` → `convert` → the shared `completeExport` machinery → KoSIT-`VALID`,
 * the same proof the acceptance criterion requires: KoSIT reports `VALID` on the real path.
 *
 * `@Testcontainers(disabledWithoutDocker = true)` — skips cleanly (reported *disabled*, not
 * failed) when the local Docker daemon isn't reachable, same pattern as
 * [de.littletrotter.xpdf.NatsTestContainersExtension]/[de.littletrotter.facturx.FacturXEngineContractIT].
 *
 * The engine container is started by [FacturXEngineTestResource], the same shared resource
 * [de.littletrotter.facturx.FacturXEngineContractIT] uses, and runs on every default
 * `mvn clean verify` — a missing or unpullable image fails this class loudly instead of skipping.
 *
 * Stayed in kotlin-xpdf (moved from `impl.facturx` to bare `impl`, alongside
 * [FacturXJobPathTest]) rather than moving into kotlin-facturx with the rest of the `impl/facturx`
 * test tree: it constructs [PdfExportServiceImpl]/[XmlExportServiceImpl]/[EmbeddedKositValidator]
 * directly, all of which stay in kotlin-xpdf — moving this class would have made kotlin-facturx's
 * test sources depend on kotlin-xpdf's main sources, a real circular module dependency Maven
 * cannot build (kotlin-xpdf's main sources already depend on kotlin-facturx's). Its
 * [BasePdfRendererPort] is a plain mock (module split, ADR-030 — kotlin-xpdf no longer has a real
 * layout engine on its own test classpath), so it tests the job-path orchestration
 * ([PdfExportServiceImpl]), not the Factur-X client library itself, so kotlin-xpdf is where it
 * belongs regardless.
 */
@Testcontainers(disabledWithoutDocker = true)
class FacturXJobPathContractIT {

    companion object {
        private val engineResource = FacturXEngineTestResource()

        private lateinit var client: HttpFacturXEngineClient
        private lateinit var kositValidator: EmbeddedKositValidator

        @JvmStatic
        @BeforeAll
        fun startEngineAndValidator() {
            engineResource.start()
            client = HttpFacturXEngineClient(
                baseUrl = engineResource.baseUrl(),
                connectTimeoutMs = 5000,
                requestTimeoutMs = 15000,
                maxUploadMb = 25,
            )
            // Real KoSIT engine + real XRechnung scenario ZIP from the classpath — the same
            // production bean (EmbeddedKositValidator, @ApplicationScoped, lazily initialized),
            // constructed directly here exactly like KositValidationIT already
            // does without a container; `.apply { init() }` forces that lazy build now instead of
            // on the first validate() call below. This is what proves decision 6 (KoSIT stays
            // mandatory on the Factur-X route) actually produces VALID against the real engine's
            // D22B XML, not just that the wiring compiles.
            kositValidator = EmbeddedKositValidator().apply { init() }
        }
    }

    /** A minimal, real, parseable one-page PDF — [BasePdfStage.renderBase] needs something
     *  [org.apache.pdfbox.Loader] can actually load (unlike a bare `"%PDF"` header). */
    private fun minimalValidPdf(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun validInvoice(number: String = "IT-FACTURX-JOBPATH-1"): InvoiceDto = InvoiceDto(
        invoiceNumber = InvoiceNumberDto(number),
        dates = InvoiceDatesDto(invoiceDate = "2026-02-10", dueDate = "2026-03-12"),
        senderProfile = PartyDto(
            name = "Sender GmbH", street = "Hafenstr. 12", postalCode = "28195", city = "Bremen", country = "DE",
            email = "buchhaltung@sender.example", vatId = "DE123456789",
        ),
        recipient = PartyDto(
            name = "Acme AG", street = "Musterstr. 42", postalCode = "10115", city = "Berlin", country = "DE",
            vatId = "DE987654321",
        ),
        items = listOf(
            InvoiceItemDto(
                positionId = "1",
                description = "Beratung",
                quantity = BigDecimal("8"),
                unitPriceNet = Money(BigDecimal("100.00")),
                taxCategory = "S",
                taxRatePercent = BigDecimal("19"),
                netAmount = Money(BigDecimal("800.00")),
                vatAmount = Money(BigDecimal("152.00")),
            ),
        ),
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("800.00")),
            vatAmount = Money(BigDecimal("152.00")),
            grossAmount = Money(BigDecimal("952.00")),
        ),
    )

    /** Same shape as [validInvoice], but the recipient is in France with a real (well-formed,
     *  Luhn-valid) SIRET — the case this test exists for:
     *  German seller, French customer. */
    private fun frenchRecipientInvoice(number: String = "IT-BRFRCTC-JOBPATH-1"): InvoiceDto = InvoiceDto(
        invoiceNumber = InvoiceNumberDto(number),
        dates = InvoiceDatesDto(invoiceDate = "2026-02-10", dueDate = "2026-03-12"),
        senderProfile = PartyDto(
            name = "Sender GmbH", street = "Hafenstr. 12", postalCode = "28195", city = "Bremen", country = "DE",
            email = "buchhaltung@sender.example", vatId = "DE123456789",
        ),
        recipient = PartyDto(
            name = "Acme SARL", street = "12 Rue de Paris", postalCode = "75001", city = "Paris", country = "FR",
            vatId = "FR40303265045", siret = "73282932000074",
        ),
        items = listOf(
            InvoiceItemDto(
                positionId = "1",
                description = "Beratung",
                quantity = BigDecimal("8"),
                unitPriceNet = Money(BigDecimal("100.00")),
                taxCategory = "S",
                taxRatePercent = BigDecimal("19"),
                netAmount = Money(BigDecimal("800.00")),
                vatAmount = Money(BigDecimal("152.00")),
            ),
        ),
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("800.00")),
            vatAmount = Money(BigDecimal("152.00")),
            grossAmount = Money(BigDecimal("952.00")),
        ),
    )

    @Test
    fun `performFacturXExport against the real engine produces a job-path result with a real KoSIT VALID`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalValidPdf())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock<XmlExportServiceImpl>(), // unused on the Factur-X route
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = kositValidator,
            facturXEngine = client,
        )

        val outcome = service.performFacturXExport(validInvoice(), "job-facturx-it-1")

        assertTrue(outcome is FacturXExportOutcome.Success, "expected a Success outcome against the real engine, got: $outcome")
        val result = (outcome as FacturXExportOutcome.Success).result
        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload, "kositReport must decode")
        val stages = payload!!.validationRun!!.stages

        val syntaxStage = stages.first { it.stage == "SYNTAX" }
        assertTrue(syntaxStage.skipped, "SYNTAX must be reported skipped on the Factur-X route")

        // The acceptance criterion: KoSIT must report VALID on the real path — the real
        // embedded KoSIT engine, checking the real engine's generateXml output (profile=en16931,
        // decision 4), must report VALID, not just "ran without throwing".
        val kositStage = stages.first { it.stage == "KOSIT" }
        assertFalse(kositStage.skipped, "KOSIT stays mandatory on the Factur-X route (decision 6)")
        assertEquals(
            KositOutcome.VALID, kositStage.outcome,
            "expected real KoSIT VALID, got ${kositStage.outcome}: ${kositStage.findings.map { it.message }}",
        )

        val facturxStage = stages.firstOrNull { it.stage == "FACTURX" }
        assertNotNull(facturxStage, "the Factur-X route must add its own FACTURX stage from the real engine's /v1/validate")

        // The converted PDF (embedding the real engine's CII XML) must actually be stored.
        verify(store).put(eq(result.objectKey), any(), eq("application/pdf"))
        assertEquals(64, result.sha256.length)
    }

    /**
     * The strongest available proof for the
     * `BR_FR_CTC` stage: a real Factur-X job, driven through the actual job-path method, against
     * the real pinned engine's real `/v1/xml` output (not a fixture), run through the real
     * extracted `BR-FR-CII.xsl` (not mocked — [PdfExportServiceImpl]'s default-constructed
     * [de.littletrotter.facturx.BrFrCtcValidator]). This is the same container [FacturXJobPathContractIT]'s other test
     * already proves the base job path against; this one adds a French recipient.
     */
    @Test
    fun `performFacturXExport against the real engine adds a real BR_FR_CTC stage for a French recipient`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalValidPdf())

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mock<XmlExportServiceImpl>(), // unused on the Factur-X route
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = kositValidator,
            facturXEngine = client,
        )

        val outcome = service.performFacturXExport(frenchRecipientInvoice(), "job-facturx-it-brfrctc")

        assertTrue(outcome is FacturXExportOutcome.Success, "expected a Success outcome against the real engine, got: $outcome")
        val result = (outcome as FacturXExportOutcome.Success).result
        val payload = KositReportPayload.decode(result.kositReport)
        assertNotNull(payload, "kositReport must decode")
        val stages = payload!!.validationRun!!.stages

        val brFrCtcStage = stages.firstOrNull { it.stage == "BR_FR_CTC" }
        assertNotNull(brFrCtcStage, "a French recipient must produce a real BR_FR_CTC stage")
        assertFalse(brFrCtcStage!!.skipped)
        // Advisory rule — our seller (senderProfile) has no French VAT/SIREN
        // context, so the stage's own outcome must never drag the overall verdict down.
        assertEquals(KositOutcome.VALID, brFrCtcStage.outcome)
        // The seller-SIREN rule this document structurally cannot satisfy (no seller SIREN is
        // ever sent, ENGINE-METADATA-2.1.0.md §4) must stay hidden by the curated suppression
        // list — a regression in either the suppression file or its parsing would surface it here.
        assertTrue(
            brFrCtcStage.findings.none { it.ruleId == "BR-FR-10_BT-30" },
            "the suppressed seller-SIREN rule leaked into the real engine's job-path findings: ${brFrCtcStage.findings}",
        )
        // Real, currently-unaddressed data gaps (mandatory BG-1 mentions, billing-mode BT-23,
        // seller electronic address, the 19% German VAT rate not being on BR-FR-16's French
        // allow-list) are expected to surface here — this route's InvoiceDto -> CII mapping does
        // not populate any of them today. They must all be downgraded to warning (advisory).
        assertTrue(brFrCtcStage.findings.isNotEmpty(), "expected real advisory findings against genuine production output, got none")
        brFrCtcStage.findings.forEach { finding ->
            assertEquals("warning", finding.severity, "non-FR-seller findings must be advisory: $finding")
            assertEquals("BR_FR_CTC", finding.stage)
            assertEquals("br-fr-ctc-1.4.0", finding.validator)
        }

        // The transition (dropped once BR_FR_CTC actually ran in the same job).
        val facturxStage = stages.first { it.stage == "FACTURX" }
        assertTrue(
            facturxStage.findings.none { it.ruleId == "FACTURX-FR-RULES-NOT-RUN" },
            "FACTURX-FR-RULES-NOT-RUN must be dropped once BR_FR_CTC ran in the same job: ${facturxStage.findings}",
        )
    }
}
