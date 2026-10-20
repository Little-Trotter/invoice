package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.validation.CiiSchematronValidator
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
        fun `blank xml key means there is no xml at all`() {
            val artifacts = ExportArtifacts(ByteArray(0), "", byteArrayOf(9), "EN16931", "factur-x.xml", "no xml")

            assertFalse(artifacts.hasXml)
            assertEquals(0, artifacts.xmlBytes.size)
        }

        @Test
        fun `withFinalPdf replaces only the pdf bytes`() {
            val original = ExportArtifacts(byteArrayOf(1), "doc.xml", byteArrayOf(2), "EN16931", "factur-x.xml", "label")
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
        private val schematron = stage(CiiSchematronValidator.STAGE)
        private val kosit = stage("KOSIT")
        private val pdfa = stage("PDFA")
        private val business = stage("BUSINESS", skipped = true)

        @Test
        fun `full chain keeps the documented stage order`() {
            val stages = ExportStages(syntax, schematron, kosit, profile = stage("PROFILE"))

            val chain = stages.chain(pdfa = pdfa, veraPdf = stage("VERAPDF"), business = business)

            assertEquals(
                listOf(
                    "SYNTAX", CiiSchematronValidator.STAGE, "PROFILE", "KOSIT", "PDFA", "VERAPDF", "BUSINESS",
                ),
                chain.map { it.stage },
            )
        }

        @Test
        fun `missing profile stage is absent, not empty`() {
            val stages = ExportStages(syntax, schematron, kosit)

            assertNull(stages.profile)
            assertEquals(
                listOf("SYNTAX", CiiSchematronValidator.STAGE, "KOSIT", "PDFA", "VERAPDF", "BUSINESS"),
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
            val stages = ExportStages(skippedSyntax, stage(CiiSchematronValidator.STAGE, skipped = true), skippedKosit)

            val chain = stages.chain(pdfa = pdfa, veraPdf = null, business = business)

            assertTrue(chain.take(3).all { it.skipped })
            assertSame(skippedSyntax, chain[0])
            assertSame(skippedKosit, chain[2])
        }
    }
}
