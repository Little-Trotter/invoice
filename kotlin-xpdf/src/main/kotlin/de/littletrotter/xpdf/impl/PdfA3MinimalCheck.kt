package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.xmpbox.xml.DomXmpParser
import jakarta.enterprise.context.ApplicationScoped

/**
 * A minimal PDF/A-3 smoke check using the project's existing Apache PDFBox 3.0.8,
 * not a real conformance proof. A genuine validator
 * (veraPDF, the reference PDF/A checker) was tried first — its only complete implementation
 * (`org.verapdf:pdfbox-validation-model`) bundles its own forked `org.verapdf.pdfbox:pdfbox`
 * under the identical `org.apache.pdfbox.*` package names as this project's real PDFBox 3.0.8,
 * and at runtime the two collide (`NoSuchMethodError` on `PDDocument.load`, confirmed
 * empirically, not a guess) — a real, unresolved dependency incompatibility, not a config
 * mistake. Adopting veraPDF properly would need shading/relocating one of the two PDFBox trees;
 * deferred, this check instead finds the *most common real failure*: a
 * missing or wrongly-named/related embedded-XML attachment, and a missing/wrong PDF/A
 * identification in the XMP metadata. It does not prove ISO 19005-3 conformance — that stays
 * formally unbelegt until veraPDF (or an equivalent) is actually wired in.
 */
/** PDF/A-**3**, the only part this check targets (`pdfaid:part`). */
private const val PDFA_PART = 3

@ApplicationScoped
class PdfA3MinimalCheck {

    /** Never throws — an engine failure (PDF doesn't even parse) is `ERROR`, matching every
     *  other validator in this pipeline; a structural problem (missing attachment/XMP) is
     *  `INVALID`. A `null` [expectedAttachmentFilename] checks the XMP identification only, for a
     *  document that carries no attachment (an offer before its XML is embedded). */
    fun check(pdfBytes: ByteArray, expectedAttachmentFilename: String?): PdfA3CheckResult {
        return try {
            Loader.loadPDF(pdfBytes).use { document ->
                val findings = mutableListOf<ValidationMessage>()
                checkXmp(document, findings)
                if (expectedAttachmentFilename != null) checkAttachment(document, expectedAttachmentFilename, findings)
                val outcome = if (findings.isEmpty()) KositOutcome.VALID else KositOutcome.INVALID
                PdfA3CheckResult(outcome, findings)
            }
        } catch (e: java.io.IOException) {
            PdfA3CheckResult(
                outcome = KositOutcome.ERROR,
                findings = listOf(
                    ValidationMessage(
                        severity = "ERROR",
                        message = "PDF/A-3 minimal check engine error: ${e.message}",
                        stage = "PDFA",
                        validator = "pdfa-minimal",
                    )
                ),
            )
        }
    }

    private fun checkXmp(document: org.apache.pdfbox.pdmodel.PDDocument, findings: MutableList<ValidationMessage>) {
        val metadata = document.documentCatalog.metadata
        if (metadata == null) {
            findings.add(finding("XMP metadata is missing entirely — no PDF/A identification possible"))
            return
        }
        val xmp = try {
            metadata.exportXMPMetadata().use { DomXmpParser().parse(it) }
        } catch (e: org.apache.xmpbox.xml.XmpParsingException) {
            findings.add(finding("XMP metadata could not be parsed: ${e.message}"))
            null
        } catch (e: java.io.IOException) {
            findings.add(finding("XMP metadata could not be parsed: ${e.message}"))
            null
        } ?: return
        val pdfaId = xmp.pdfaIdentificationSchema
        if (pdfaId == null) {
            findings.add(finding("XMP metadata carries no PDF/A identification schema (pdfaid:part/pdfaid:conformance)"))
            return
        }
        if (pdfaId.part != PDFA_PART) {
            findings.add(finding("XMP pdfaid:part is '${pdfaId.part}', expected $PDFA_PART"))
        }
        val conformance = pdfaId.conformance?.uppercase()
        if (conformance !in setOf("A", "B", "U")) {
            findings.add(finding("XMP pdfaid:conformance is '${pdfaId.conformance}', expected A, B or U"))
        }
    }

    private fun checkAttachment(
        document: org.apache.pdfbox.pdmodel.PDDocument,
        expectedFilename: String,
        findings: MutableList<ValidationMessage>,
    ) {
        val embeddedFiles = document.documentCatalog.names?.embeddedFiles?.names
        if (embeddedFiles.isNullOrEmpty()) {
            findings.add(finding("No embedded files found — the invoice XML attachment (BT-125/hybrid PDF requirement) is missing"))
            return
        }
        val match = embeddedFiles[expectedFilename]
        if (match == null) {
            findings.add(
                finding(
                    "No embedded file named '$expectedFilename' found (present: ${embeddedFiles.keys.joinToString()})"
                )
            )
            return
        }
        val relationship = match.cosObject.getNameAsString(COSName.AF_RELATIONSHIP)
        if (relationship.isNullOrBlank()) {
            findings.add(finding("Embedded file '$expectedFilename' has no AFRelationship entry"))
        }
    }

    private fun finding(message: String) = ValidationMessage(
        severity = "ERROR",
        message = message,
        stage = "PDFA",
        validator = "pdfa-minimal",
    )
}

data class PdfA3CheckResult(
    val outcome: KositOutcome,
    val findings: List<ValidationMessage>,
)
