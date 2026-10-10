package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.archive.Ed25519ArchiveSigner
import de.littletrotter.shared.archive.Ed25519Keys
import de.littletrotter.xpdf.validCiiXml
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream

/**
 * [PdfExportServiceImpl.completeExport]'s archive-package integration
 * ([PdfExportServiceImpl.buildAndStoreArchivePackage]): built and stored only for an issued
 * (non-draft) invoice and only when an [de.littletrotter.shared.archive.ArchiveSigner] is
 * configured, storing under [DocumentKeyRules.deriveJobExportKeys]'s `archiveObjectKey`, and every
 * signature in it verifies against the configured public key.
 */
class PdfExportServiceImplArchivePackageTest {

    private fun minimalPdfWithContent(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Test")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun makeInvoice(number: String = "INV-ARCHIVE-1", draft: Boolean = false): InvoiceDto =
        InvoiceDto(invoiceNumber = InvoiceNumberDto(number), items = emptyList(), draft = draft)

    private fun makeKositPort(): KositValidatorPort {
        val port: KositValidatorPort = mock()
        whenever(port.validate(any(), any())).thenReturn(KositValidationResult(status = KositOutcome.VALID, summary = "ok"))
        return port
    }

    private data class Fixture(val service: PdfExportServiceImpl, val store: DocumentStore, val publicKey: java.security.PublicKey)

    private fun makeServiceWithSigner(): Fixture {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())

        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")

        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())

        val keyPair = Ed25519Keys.generateKeyPair()
        val signer = Ed25519ArchiveSigner(keyPair.private, Ed25519Keys.fingerprint(keyPair.public))

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = xmlService,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = makeKositPort(),
            archiveSigner = signer,
        )
        return Fixture(service, store, keyPair.public)
    }

    @Test
    fun `RS-9 - an issued invoice's export stores a signed archive package and reports its key-checksum`() {
        val (service, store, _) = makeServiceWithSigner()
        val invoice = makeInvoice()

        val result = service.performExport(invoice, "job-archive-1")

        // Per-attempt key set (one base for PDF/XML/archive), never the bare canonical name.
        assertThat(DocumentKeyRules.isIssuedJobPdfKey(result.objectKey, invoice.invoiceNumber.value, invoice.id)).isTrue()
        val expectedArchiveKey = result.objectKey.removeSuffix(".pdf") + ".tar.gz"
        assertThat(result.archiveObjectKey).isEqualTo(expectedArchiveKey)
        assertThat(result.archiveObjectKey).isNotEqualTo(DocumentKeyRules.deriveExportKeys(invoice.invoiceNumber.value, invoice.id).archiveObjectKey)
        assertThat(result.archiveSha256).isNotNull().hasSize(64)
        verify(store).put(eq(expectedArchiveKey), any(), eq("application/gzip"))
    }

    @Test
    fun `RS-9 - a draft invoice's export never builds or stores an archive package`() {
        val (service, store, _) = makeServiceWithSigner()
        val invoice = makeInvoice(draft = true)

        val result = service.performExport(invoice, "job-archive-draft")

        assertThat(result.archiveObjectKey).isNull()
        assertThat(result.archiveSha256).isNull()
        verify(store, never()).put(any(), any(), eq("application/gzip"))
        assertThat(result.objectKey).startsWith("invoices/draft/")
    }

    @Test
    fun `RS-9 - without a configured signer, an issued invoice's export never builds an archive package`() {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalPdfWithContent())
        val xmlService: XmlExportServiceImpl = mock()
        whenever(xmlService.generate(any())).thenReturn(validCiiXml().toByteArray())
        whenever(xmlService.resolveProfile(any())).thenReturn("EN16931")
        val store: DocumentStore = mock()
        doNothing().whenever(store).put(any(), any(), any())
        // 4-arg constructor: archiveSigner defaults to null (the same seam other tests use).
        val service = PdfExportServiceImplTestFactory.create(xmlService, renderer, store, makeKositPort())

        val result = service.performExport(makeInvoice(), "job-archive-no-signer")

        assertThat(result.archiveObjectKey).isNull()
        assertThat(result.archiveSha256).isNull()
    }

    @Test
    fun `RS-9 - the stored archive package unpacks to a manifest and signatures that verify against the configured public key`() {
        val (service, store, publicKey) = makeServiceWithSigner()
        val invoice = makeInvoice(number = "INV-ARCHIVE-VERIFY")
        val archiveBytesCaptor = argumentCaptor<ByteArray>()

        val result = service.performExport(invoice, "job-archive-verify")
        verify(store).put(eq(result.archiveObjectKey!!), archiveBytesCaptor.capture(), eq("application/gzip"))

        val entries = unpackTarGz(archiveBytesCaptor.firstValue)
        assertThat(entries.keys).contains(
            "manifest.json", "signed/manifest.json.sig",
            "original/INV-ARCHIVE-VERIFY.pdf", "signed/INV-ARCHIVE-VERIFY.pdf.sig",
            "original/INV-ARCHIVE-VERIFY.xml", "signed/INV-ARCHIVE-VERIFY.xml.sig",
        )
        assertThat(Ed25519Keys.verify(publicKey, entries.getValue("manifest.json"), entries.getValue("signed/manifest.json.sig"))).isTrue()
        assertThat(Ed25519Keys.verify(publicKey, entries.getValue("original/INV-ARCHIVE-VERIFY.pdf"), entries.getValue("signed/INV-ARCHIVE-VERIFY.pdf.sig"))).isTrue()

        val manifest = com.fasterxml.jackson.databind.ObjectMapper().readTree(entries.getValue("manifest.json"))
        assertThat(manifest.get("invoiceNumber").asText()).isEqualTo("INV-ARCHIVE-VERIFY")
        assertThat(manifest.get("jobId").asText()).isEqualTo("job-archive-verify")
        assertThat(manifest.get("actor").asText()).isEqualTo("system")
        assertThat(manifest.get("algorithm").asText()).isEqualTo("Ed25519")

        // sanity: the returned checksum matches the package's own bytes, same relationship
        // ArchivePackageBuilder.build guarantees directly.
        assertThat(result.archiveSha256).isEqualTo(sha256Hex(archiveBytesCaptor.firstValue))
    }

    // ── local tar.gz unpack helper (mirrors ArchivePackageBuilderTest's own, kept test-local so
    // this module's tests do not depend on kotlin-shared's test sources) ──────────────────────
    private fun unpackTarGz(tarGzBytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(tarGzBytes.inputStream()).use { gzipIn ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    entries[entry.name] = tarIn.readAllBytes()
                    entry = tarIn.nextEntry
                }
            }
        }
        return entries
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
}
