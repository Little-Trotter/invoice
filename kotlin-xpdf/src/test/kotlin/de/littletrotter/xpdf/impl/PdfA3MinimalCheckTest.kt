package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import de.littletrotter.xpdf.b2bInvoiceDto
import de.littletrotter.xpdf.validCiiXml
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.XmpSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** F3, Stufe 5 — the minimal PDF/A-3 smoke check (see [PdfA3MinimalCheck]'s own KDoc for why
 *  this exists instead of a real conformance check). */
class PdfA3MinimalCheckTest {

    private val check = PdfA3MinimalCheck()

    private fun plainPdfBytes(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** A one-page PDF with actual content (not a bare [PDPage]) — Mustang's real exporter
     *  assumes a populated resources dictionary during PDF/A preparation. */
    private fun pageWithContentBytes(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Test")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** Runs the real Mustang exporter (not mocked) — same call [PdfExportServiceImpl]'s
     *  `renderPdfWithZugferd` makes — to get a genuinely embedded-attachment PDF/A-3 hybrid. */
    private fun hybridPdfBytes(xmlBytes: ByteArray = MINIMAL_XML): ByteArray {
        val out = ByteArrayOutputStream()
        ZUGFeRDExporterFromA3().use { exporter ->
            exporter.load(pageWithContentBytes())
            exporter.setProfile("EN16931")
            exporter.setXML(xmlBytes)
            exporter.export(out)
        }
        return out.toByteArray()
    }

    @Test
    fun `a real ZUGFeRD-embedded PDF has an embedded attachment named factur-x_xml with an AFRelationship`() {
        // This is the positive-path proof: Mustang's own real export, inspected by our check.
        val pdf = hybridPdfBytes()
        val result = check.check(pdf, expectedAttachmentFilename = "factur-x.xml")
        assertTrue(
            result.findings.none { it.message.contains("embedded file") || it.message.contains("Embedded file") },
            "attachment findings unexpected: ${result.findings}",
        )
    }

    @Test
    fun `a plain PDF with no embedded XML at all is INVALID with a clear finding`() {
        val result = check.check(plainPdfBytes(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.any { it.message.contains("No embedded files") })
    }

    @Test
    fun `the wrong expected filename is reported as a mismatch, not silently accepted`() {
        val pdf = hybridPdfBytes()
        val result = check.check(pdf, expectedAttachmentFilename = "xrechnung.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.any { it.message.contains("No embedded file named 'xrechnung.xml'") })
    }

    @Test
    fun `a plain PDF with no XMP metadata at all is INVALID`() {
        val result = check.check(plainPdfBytes(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.any { it.message.contains("XMP metadata is missing") })
    }

    /**
     * T7 — the PDF part of the export proof for the B2B document ([b2bInvoiceDto]): its
     * **actually generated** XRechnung XML (not the minimal fixture) is embedded via the same
     * `ZUGFeRDExporterFromA3` that `PdfExportServiceImpl.renderPdfWithZugferd` calls, and the
     * result passes the check.
     *
     * **Explicit caveat:** this is the *minimal* check (attachment + XMP identification), **not**
     * an ISO-19005-3 conformance proof — see [PdfA3MinimalCheck]'s own KDoc. The real proof lives
     * in [VeraPdfConformanceIT] and only runs where the veraPDF CLI is present; without it, it is
     * skipped, not passed. This case here is the part that **always** runs.
     */
    @Test
    fun `T7 - the B2B invoice's own generated xml embeds into a PDF that passes the minimal check`() {
        val xml = XmlExportServiceImpl().generate(b2bInvoiceDto())

        val result = check.check(hybridPdfBytes(xmlBytes = xml), expectedAttachmentFilename = "factur-x.xml")

        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Befunde: " + result.findings.joinToString("; ") { it.message },
        )
    }

    @Test
    fun `garbage bytes yield ERROR, never VALID`() {
        val result = check.check(byteArrayOf(0x00, 0x01), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.ERROR, result.outcome)
    }

    /** Builds a standalone `pdfaid` XMP schema so `part`/`conformance` can be set independently of
     *  a real Mustang export — the wrong-part and missing-conformance boundaries below need values
     *  Mustang's own exporter never produces. */
    private fun xmpBytes(part: Int?, conformance: String?): ByteArray {
        val xmp = XMPMetadata.createXMPMetadata()
        val schema = xmp.createAndAddPDFAIdentificationSchema()
        if (part != null) schema.setPart(part)
        if (conformance != null) schema.setConformance(conformance)
        val out = ByteArrayOutputStream()
        XmpSerializer().serialize(xmp, out, true)
        return out.toByteArray()
    }

    private fun pdfWithXmp(part: Int?, conformance: String?): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata(xmpBytes(part, conformance))
            doc.documentCatalog.metadata = metadata
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** Manually assembled embedded-file dictionary (not via Mustang) so the AFRelationship entry
     *  can be deliberately left out — the exact ZUGFeRD/Factur-X boundary the risk focus names. */
    private fun pdfWithEmbeddedFileMissingAfRelationship(filename: String = "factur-x.xml"): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata(xmpBytes(part = 3, conformance = "B"))
            doc.documentCatalog.metadata = metadata

            val embeddedFile = PDEmbeddedFile(doc, ByteArrayInputStream(MINIMAL_XML))
            embeddedFile.setSubtype("text/xml")
            val fileSpec = PDComplexFileSpecification()
            fileSpec.setFile(filename)
            fileSpec.setEmbeddedFile(embeddedFile)
            // AFRelationship deliberately not set — this is the boundary under test.

            val embeddedFilesTree = PDEmbeddedFilesNameTreeNode()
            embeddedFilesTree.setNames(mapOf(filename to fileSpec))
            val names = PDDocumentNameDictionary(doc.documentCatalog)
            names.setEmbeddedFiles(embeddedFilesTree)
            doc.documentCatalog.names = names

            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** Isolates `embeddedFiles.isNullOrEmpty()`'s `isEmpty()` branch from its `== null`
     *  branch: [plainPdfBytes] never sets a `names` dictionary at all (the null branch, already
     *  covered), while every other fixture that sets one always adds at least one entry — an
     *  explicitly empty embedded-files tree drives the middle case neither existing fixture
     *  reaches. */
    private fun pdfWithEmptyEmbeddedFilesTree(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata(xmpBytes(part = 3, conformance = "B"))
            doc.documentCatalog.metadata = metadata

            val embeddedFilesTree = PDEmbeddedFilesNameTreeNode()
            embeddedFilesTree.setNames(emptyMap())
            val names = PDDocumentNameDictionary(doc.documentCatalog)
            names.setEmbeddedFiles(embeddedFilesTree)
            doc.documentCatalog.names = names

            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    @Test
    fun `boundary - an embedded-files tree present but with zero entries is reported like no embedded files at all`() {
        val result = check.check(pdfWithEmptyEmbeddedFilesTree(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("No embedded files found") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    /** Isolates `relationship.isNullOrBlank()`'s `isBlank()` branch from its `== null`
     *  branch: [pdfWithEmbeddedFileMissingAfRelationship] never sets the entry at all (the null
     *  branch), while [hybridPdfBytes] (Mustang's real export) always sets a non-blank one — an
     *  explicitly blank AFRelationship drives the middle case neither existing fixture reaches. */
    private fun pdfWithBlankAfRelationship(filename: String = "factur-x.xml"): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata(xmpBytes(part = 3, conformance = "B"))
            doc.documentCatalog.metadata = metadata

            val embeddedFile = PDEmbeddedFile(doc, ByteArrayInputStream(MINIMAL_XML))
            embeddedFile.setSubtype("text/xml")
            val fileSpec = PDComplexFileSpecification()
            fileSpec.setFile(filename)
            fileSpec.setEmbeddedFile(embeddedFile)
            fileSpec.cosObject.setName(COSName.AF_RELATIONSHIP, "") // blank, not absent — the boundary under test

            val embeddedFilesTree = PDEmbeddedFilesNameTreeNode()
            embeddedFilesTree.setNames(mapOf(filename to fileSpec))
            val names = PDDocumentNameDictionary(doc.documentCatalog)
            names.setEmbeddedFiles(embeddedFilesTree)
            doc.documentCatalog.names = names

            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    @Test
    fun `boundary - a blank AFRelationship entry is reported the same as a missing one`() {
        val result = check.check(pdfWithBlankAfRelationship(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("has no AFRelationship entry") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    @Test
    fun `happy path - a real ZUGFeRD-embedded PDF has PDF-A-3 XMP identification and no findings`() {
        val pdf = hybridPdfBytes()
        val result = check.check(pdf, expectedAttachmentFilename = "factur-x.xml")
        assertEquals(
            KositOutcome.VALID, result.outcome,
            "Befunde: " + result.findings.joinToString("; ") { it.message },
        )
    }

    @Test
    fun `boundary - a wrong PDF-A part in the XMP identification is reported with the actual value`() {
        val result = check.check(pdfWithXmp(part = 1, conformance = "B"), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("pdfaid:part is '1', expected 3") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    /** Isolates `pdfaId.part != PDFA_PART` for `part == null` (the schema node present
     *  but `pdfaid:part` itself never written) from the "wrong value" boundary above (`part = 1`)
     *  — neither Mustang's real export nor any other existing fixture ever omits `part` once the
     *  schema exists, so no other test reaches this branch. */
    @Test
    fun `boundary - a PDF-A identification schema with no part value at all is reported`() {
        val result = check.check(pdfWithXmp(part = null, conformance = "B"), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("pdfaid:part is 'null', expected 3") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    @Test
    fun `boundary - a missing PDF-A conformance letter in the XMP identification is reported`() {
        val result = check.check(pdfWithXmp(part = 3, conformance = null), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("pdfaid:conformance is 'null', expected A, B or U") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    /** XMP present but carrying no `pdfaid` schema at all (a bare, empty RDF document) — distinct
     *  from "no XMP metadata" (checked above) and from "wrong part/conformance" (the schema IS
     *  present there); this is the third branch of [PdfA3MinimalCheck.checkXmp]. */
    private fun pdfWithXmpMissingPdfaIdSchema(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val metadata = PDMetadata(doc)
            val bare = XMPMetadata.createXMPMetadata()
            val out = ByteArrayOutputStream()
            XmpSerializer().serialize(bare, out, true)
            metadata.importXMPMetadata(out.toByteArray())
            doc.documentCatalog.metadata = metadata
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** XMP bytes that are not parseable XML at all — [DomXmpParser] throws, exercising
     *  [PdfA3MinimalCheck.checkXmp]'s own parse-failure catch (distinct from the outer
     *  engine-level catch the "garbage bytes" test proves). */
    private fun pdfWithUnparseableXmp(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata("not xml at all {{{".toByteArray())
            doc.documentCatalog.metadata = metadata
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    @Test
    fun `boundary - XMP present without any pdfaid identification schema is reported`() {
        val result = check.check(pdfWithXmpMissingPdfaIdSchema(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("carries no PDF/A identification schema") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    @Test
    fun `boundary - unparseable XMP bytes are reported instead of throwing`() {
        val result = check.check(pdfWithUnparseableXmp(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("XMP metadata could not be parsed") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    @Test
    fun `boundary - an embedded attachment without an AFRelationship entry is reported`() {
        val result = check.check(pdfWithEmbeddedFileMissingAfRelationship(), expectedAttachmentFilename = "factur-x.xml")
        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(
            result.findings.any { it.message.contains("has no AFRelationship entry") },
            "Befunde: ${result.findings.map { it.message }}",
        )
    }

    companion object {
        private val MINIMAL_XML = validCiiXml().toByteArray()
    }

    @Test
    fun `without an expected attachment only the XMP identification is checked`() {
        val result = check.check(plainPdfBytes(), expectedAttachmentFilename = null)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.none { it.message.contains("embedded", ignoreCase = true) }, result.findings.toString())
    }
}
