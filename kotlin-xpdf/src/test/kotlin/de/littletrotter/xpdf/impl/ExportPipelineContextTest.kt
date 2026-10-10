package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.BrFrCtcValidator
import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.shared.ExportKeys
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit pins for the export-pipeline parameter objects ([ExportRequest], [ExportArtifacts],
 * [ExportStages]) that replace the long parameter lists of [PdfExportServiceImpl]'s stages.
 */
class ExportPipelineContextTest {

    private val keys = ExportKeys("doc.pdf", "doc.xml", "doc-llm.json", "doc.tar.gz")

    private fun stage(name: String, outcome: KositOutcome = KositOutcome.VALID, skipped: Boolean = false) =
        ValidationStageResult(name, outcome, skipped = skipped, skipReason = if (skipped) "skipValidation" else null)

    @Nested
    inner class Request {

        @Test
        fun `carries every per-job input unchanged`() {
            val invoice = InvoiceDto(items = emptyList())
            val request = ExportRequest(invoice, "job-1", keys, "2026-09-23T10:00Z", false, true, true)

            assertSame(invoice, request.invoice)
            assertEquals("job-1", request.jobId)
            assertSame(keys, request.keys)
            assertEquals("2026-09-23T10:00Z", request.checkedAt)
            assertFalse(request.skipValidation)
            assertTrue(request.veraPdfCheck)
            assertTrue(request.llmOptimize)
        }

        @Test
        fun `validation skipped carries no checkedAt timestamp`() {
            val request = ExportRequest(InvoiceDto(items = emptyList()), "job-2", keys, null, true, false, false)

            assertTrue(request.skipValidation)
            assertNull(request.checkedAt)
            assertFalse(request.veraPdfCheck)
            assertFalse(request.llmOptimize)
        }
    }

    @Nested
    inner class Artifacts {

        @Test
        fun `mustang artifacts keep their own profile and attachment name`() {
            val xml = byteArrayOf(1, 2)
            val pdf = byteArrayOf(3)
            val artifacts = ExportArtifacts(xml, "doc.xml", pdf, "XRechnung", "xrechnung.xml", "CII D16B")

            assertTrue(artifacts.hasXml)
            assertArrayEquals(xml, artifacts.xmlBytes)
            assertArrayEquals(pdf, artifacts.finalPdfBytes)
            assertEquals("XRechnung", artifacts.profile)
            assertEquals("xrechnung.xml", artifacts.expectedAttachmentFilename)
            assertEquals("CII D16B", artifacts.xsdVersionLabel)
        }

        @Test
        fun `facturX factory fixes profile EN16931 and factur-x attachment`() {
            val artifacts = ExportArtifacts.facturX(byteArrayOf(1), "doc.xml", byteArrayOf(2), "label")

            assertEquals(ExportArtifacts.FACTURX_PROFILE, artifacts.profile)
            assertEquals("EN16931", artifacts.profile)
            assertEquals("factur-x.xml", artifacts.expectedAttachmentFilename)
            assertEquals("label", artifacts.xsdVersionLabel)
        }

        @Test
        fun `blank xml key means there is no xml at all`() {
            val artifacts = ExportArtifacts.facturX(ByteArray(0), "", byteArrayOf(9), "no xml")

            assertFalse(artifacts.hasXml)
            assertEquals(0, artifacts.xmlBytes.size)
        }

        @Test
        fun `withFinalPdf replaces only the pdf bytes`() {
            val original = ExportArtifacts.facturX(byteArrayOf(1), "doc.xml", byteArrayOf(2), "label")
            val normalized = byteArrayOf(7, 7)

            val replaced = original.withFinalPdf(normalized)

            assertArrayEquals(normalized, replaced.finalPdfBytes)
            assertArrayEquals(byteArrayOf(2), original.finalPdfBytes)
            assertSame(original.xmlBytes, replaced.xmlBytes)
            assertEquals(original.zugferdXmlObjectKey, replaced.zugferdXmlObjectKey)
            assertEquals(original.profile, replaced.profile)
            assertEquals(original.expectedAttachmentFilename, replaced.expectedAttachmentFilename)
            assertEquals(original.xsdVersionLabel, replaced.xsdVersionLabel)
        }
    }

    @Nested
    inner class Stages {

        private val syntax = stage("SYNTAX")
        private val schematron = stage(FacturXSchematronValidator.STAGE)
        private val kosit = stage("KOSIT")
        private val pdfa = stage("PDFA")
        private val business = stage("BUSINESS", skipped = true)

        @Test
        fun `full chain keeps the documented stage order`() {
            val stages = ExportStages(syntax, schematron, kosit, brFrCtc = stage(BrFrCtcValidator.STAGE))
                .withEngine(stage("FACTURX", KositOutcome.INVALID))

            val chain = stages.chain(pdfa = pdfa, veraPdf = stage("VERAPDF"), business = business)

            assertEquals(
                listOf(
                    "SYNTAX", FacturXSchematronValidator.STAGE, "KOSIT", "PDFA", "VERAPDF", "FACTURX",
                    BrFrCtcValidator.STAGE, "BUSINESS",
                ),
                chain.map { it.stage },
            )
        }

        @Test
        fun `missing engine and non-french recipient are absent, not empty`() {
            val stages = ExportStages(syntax, schematron, kosit)

            assertNull(stages.engine)
            assertNull(stages.brFrCtc)
            assertEquals(
                listOf("SYNTAX", FacturXSchematronValidator.STAGE, "KOSIT", "PDFA", "VERAPDF", "BUSINESS"),
                stages.chain(pdfa, stage("VERAPDF"), business).map { it.stage },
            )
        }

        @Test
        fun `veraPDF unavailable leaves the layer out of the chain`() {
            val chain = ExportStages(syntax, schematron, kosit).chain(pdfa = pdfa, veraPdf = null, business = business)

            assertFalse(chain.any { it.stage == "VERAPDF" })
            assertEquals(5, chain.size)
        }

        @Test
        fun `validation skipped stages are carried verbatim`() {
            val skippedSyntax = stage("SYNTAX", skipped = true)
            val skippedKosit = stage("KOSIT", skipped = true)
            val stages = ExportStages(skippedSyntax, stage(FacturXSchematronValidator.STAGE, skipped = true), skippedKosit)

            assertEquals(3, stages.preEngine.size)
            assertTrue(stages.preEngine.all { it.skipped })
            assertSame(skippedSyntax, stages.preEngine[0])
            assertSame(skippedKosit, stages.preEngine[2])
        }

        @Test
        fun `withEngine keeps every earlier stage and the brFrCtc stage`() {
            val brFrCtc = stage(BrFrCtcValidator.STAGE)
            val engine = stage("FACTURX")
            val original = ExportStages(syntax, schematron, kosit, brFrCtc = brFrCtc)

            val withEngine = original.withEngine(engine)

            assertNull(original.engine)
            assertSame(engine, withEngine.engine)
            assertSame(brFrCtc, withEngine.brFrCtc)
            assertEquals(original.preEngine, withEngine.preEngine)
        }
    }
}
