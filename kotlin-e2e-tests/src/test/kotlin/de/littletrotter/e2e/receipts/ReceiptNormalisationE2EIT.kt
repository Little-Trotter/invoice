package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptReductionFailedException
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.receipt.BudgetVerdict
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Random
import kotlin.math.roundToInt

/** A "scanned" fixture's pixel density: comfortably above the reducer's own 150 dpi target so
 *  every page genuinely needs reduction rather than passing through unchanged. */
private const val SCAN_DPI = 300
private const val POINTS_PER_INCH = 72f

/** [smoothScanImage]'s seed grid — small enough to be cheap to build, upscaled with bilinear
 *  interpolation to the full [SCAN_DPI] page size. */
private const val SMOOTH_SEED_GRID_WIDTH = 8
private const val SMOOTH_SEED_GRID_HEIGHT = 11

/** [stubbornScanImage]'s tile size, in pixels at [SCAN_DPI]. */
private const val STUBBORN_BLOCK_SIZE_PX = 10

/**
 * End-to-end coverage for the receipt-upload reduction pipeline (`ReceiptUploadService` wired with
 * the real `PdfBoxReceiptPdfReducer`, not the no-op stub): a genuinely oversized "scan" must come
 * back under `ReceiptSizePolicy.TARGET_RECEIPT_SIZE`, coexist with the other 18 receipts an
 * invoice can carry, and still let the export stay under the 20 MiB hard cap. Idiom and per-file
 * helpers copied from [ReceiptBudgetE2EIT] (fixture-builder shape, real-export/real-store proof
 * pattern) per that file's own documented convention — this file duplicates the small pieces it
 * needs rather than introducing a shared base class neither file currently has.
 *
 * RSN-1 is the happy path; RSN-2 is the border case where reduction is genuinely exhausted.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class ReceiptNormalisationE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    /** The receipt services, split into 4 units; a plain field bundle, no forwarding methods,
     *  since this fixture is shared across tests that each only need a subset. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    /** Unlike [ReceiptBudgetE2EIT.receiptService], wires the real
     *  [de.littletrotter.infrastructure.receipt.PdfBoxReceiptPdfReducer] instead of letting the
     *  upload service default to its no-op stub — this file exists specifically to prove the real
     *  reduction pipeline, not just the budget arithmetic around it. */
    private fun receiptService(): TestReceiptServices {
        val pool = ReceiptPoolService(receiptRepository, documentStore)
        val budget = ReceiptBudgetService(receiptRepository)
        return TestReceiptServices(
            upload = ReceiptUploadService(
                receiptRepository,
                documentStore,
                de.littletrotter.application.cache.NoopInvoiceCachePort(),
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
                de.littletrotter.infrastructure.receipt.PdfBoxReceiptPdfReducer(),
            ),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, pool, budget),
            budget = budget,
            pool = pool,
        )
    }

    /** A real, parseable one-page PDF, one per distinct [marker] — same shape as
     *  [ReceiptBudgetE2EIT.pdf]: already under the target, so upload stores it unchanged and it
     *  stands in for the 18 ordinary receipts an invoice carries alongside one reduced scan. */
    private fun pdf(marker: Int): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("RSN fixture marker=$marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun blankInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("RSN E2E base page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** Same fixture shape as [ReceiptBudgetE2EIT.buildInvoiceFixture]: a two-item DRAFT invoice,
     *  persisted via plain JPA. */
    private fun buildInvoiceFixture(tag: String): Invoice {
        val sender = SenderProfile().apply {
            name = "Normalisierung E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE111111111")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply { customerNumber = CustomerNumber("$tag-100"); name = "Kunde AG"; vatId = VatId("DE222222222") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("$tag-2026-0001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 8, 1)
            paymentTermsDays = 14
            addItem(
                de.littletrotter.domain.invoice.InvoiceItem().apply {
                    positionId = "1"; description = "Beratung"
                    quantity = BigDecimal.ONE; unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100.00"))
                    sortOrder = 1
                }.also { it.applyTaxRule(taxRate) },
            )
            addItem(
                de.littletrotter.domain.invoice.InvoiceItem().apply {
                    positionId = "2"; description = "Material"
                    quantity = BigDecimal.ONE; unitPriceNet = de.littletrotter.shared.Money(BigDecimal("50.00"))
                    sortOrder = 2
                }.also { it.applyTaxRule(taxRate) },
            )
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        return invoice
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Extracts the embedded `factur-x.xml`/`xrechnung.xml` attachment's own bytes from the
     *  exported hybrid PDF — same detection idiom [ReceiptBudgetE2EIT.extractEmbeddedXml] uses,
     *  duplicated locally per this module's own per-file-helper convention. `null` when no such
     *  attachment exists. */
    private fun extractEmbeddedXml(pdfBytes: ByteArray): String? = try {
        Loader.loadPDF(pdfBytes).use { doc ->
            val names = doc.documentCatalog.names?.embeddedFiles?.names ?: emptyMap()
            val spec = names["factur-x.xml"] ?: names["xrechnung.xml"]
            spec?.embeddedFile?.toByteArray()?.let { String(it, Charsets.UTF_8) }
        }
    } catch (ex: Exception) {
        null
    }

    // ── fixture builders — the "300 dpi scan" family ────────────────────────────────────────────

    /** Builds an all-image, [pageCount]-page PDF at [SCAN_DPI], each page's image produced by
     *  [pageImage] from the shared, seeded [random] — the one place both scan fixtures share their
     *  PDF plumbing so the two only differ in what makes their pixels hard or easy to compress. */
    private fun scanPdf(pageCount: Int, seed: Long, pageImage: (Random) -> BufferedImage): ByteArray {
        val random = Random(seed)
        PDDocument().use { document ->
            repeat(pageCount) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { cs ->
                    val xobject = LosslessFactory.createFromImage(document, pageImage(random))
                    cs.drawImage(xobject, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                }
            }
            val os = ByteArrayOutputStream()
            document.save(os)
            return os.toByteArray()
        }
    }

    private val scanPixelWidth = (PDRectangle.A4.width / POINTS_PER_INCH * SCAN_DPI).roundToInt()
    private val scanPixelHeight = (PDRectangle.A4.height / POINTS_PER_INCH * SCAN_DPI).roundToInt()

    /** A full-page RGB image built by upscaling a small seeded-random grid to [SCAN_DPI] with
     *  bilinear interpolation — the same spatial correlation a real scanner's optics give sensor
     *  noise. `LosslessFactory` still has to encode near-unique bytes per pixel (the grid values
     *  interpolate smoothly but rarely repeat exactly), so the raw page stays a genuine multi-MiB
     *  file; but once the reducer resamples down to its own 150 dpi target, that same smoothing
     *  has already removed most of the high-frequency content, so the result compresses cleanly
     *  under JPEG. This is what RSN-1 needs: a fixture that starts big and reduces successfully. */
    private fun smoothScanImage(random: Random): BufferedImage {
        val seedPixels = IntArray(SMOOTH_SEED_GRID_WIDTH * SMOOTH_SEED_GRID_HEIGHT) { random.nextInt(0x1000000) }
        val seed = BufferedImage(SMOOTH_SEED_GRID_WIDTH, SMOOTH_SEED_GRID_HEIGHT, BufferedImage.TYPE_INT_RGB)
        seed.setRGB(0, 0, SMOOTH_SEED_GRID_WIDTH, SMOOTH_SEED_GRID_HEIGHT, seedPixels, 0, SMOOTH_SEED_GRID_WIDTH)
        val full = BufferedImage(scanPixelWidth, scanPixelHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = full.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(seed, 0, 0, scanPixelWidth, scanPixelHeight, null)
        graphics.dispose()
        return full
    }

    /** A full-page RGB image tiled with small solid-color blocks, each genuinely random — the
     *  block period aliases against the reducer's own 150 dpi resample grid, so neither the
     *  image-downsample stage nor the page-rasterisation stage can smooth it away the way
     *  [smoothScanImage]'s upscaled gradient does: every page keeps costing several hundred KiB
     *  even after both reduction stages. This is what RSN-2 needs: a fixture that stays oversized
     *  no matter how it is reduced, so enough pages of it genuinely exhaust the reducer. */
    private fun stubbornScanImage(random: Random): BufferedImage {
        val image = BufferedImage(scanPixelWidth, scanPixelHeight, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(scanPixelWidth)
        var y = 0
        while (y < scanPixelHeight) {
            val rowsInTile = minOf(STUBBORN_BLOCK_SIZE_PX, scanPixelHeight - y)
            var x = 0
            while (x < scanPixelWidth) {
                val colsInTile = minOf(STUBBORN_BLOCK_SIZE_PX, scanPixelWidth - x)
                val color = random.nextInt(0x1000000)
                for (i in x until x + colsInTile) row[i] = color
                x += STUBBORN_BLOCK_SIZE_PX
            }
            for (yy in y until y + rowsInTile) image.setRGB(0, yy, scanPixelWidth, 1, row, 0, scanPixelWidth)
            y += STUBBORN_BLOCK_SIZE_PX
        }
        return image
    }

    private fun smoothScanPdf(pageCount: Int, seed: Long): ByteArray = scanPdf(pageCount, seed, ::smoothScanImage)

    private fun stubbornScanPdf(pageCount: Int, seed: Long): ByteArray = scanPdf(pageCount, seed, ::stubbornScanImage)

    // ── RSN-1 — happy path: a real scan is reduced and still fits the 19-receipt budget ────────

    @Test
    @Transactional
    fun `RSN-1 - happy path - a 300 dpi scan is reduced under the target and 19 receipts including it still fit the budget and the 20 MiB export`() {
        val invoice = buildInvoiceFixture("RSN1")
        val item1 = invoice.itemList[0].id!!
        val item2 = invoice.itemList[1].id!!
        val svc = receiptService()

        val scanBytes = smoothScanPdf(pageCount = 2, seed = 20260928L)
        assertTrue(
            scanBytes.size >= 3 * 1024 * 1024,
            "the fixture must start as a genuine >= 3 MiB scan for this test to prove a real reduction, was ${scanBytes.size} bytes",
        )

        val scan = svc.upload.upload("rsn1-scan.pdf", "Beleg rsn1-scan.pdf", scanBytes, LocalDate.of(2026, 3, 14))
        assertTrue(
            scan.sizeBytes!! <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES,
            "the stored scan must be reduced to the target, was ${scan.sizeBytes} bytes",
        )
        assertTrue(
            scan.normalisationStage != ReceiptNormalisationStage.NONE,
            "a ${scanBytes.size}-byte scan must have gone through a reduction stage",
        )
        assertEquals(scanBytes.size.toLong(), scan.originalSizeBytes, "the original size must be the uploaded size, not the reduced one")

        val storedScanBytes = documentStore.get(scan.objectKey)
        Loader.loadPDF(storedScanBytes).use { doc ->
            assertEquals(2, doc.numberOfPages, "reduction must not change the page count")
        }

        // 7 at invoice level (the reduced scan plus 6 small), 6 on item1, 6 on item2 — 19 distinct
        // receipts total, same split as ReceiptBudgetE2EIT's RB-1.
        val invoiceLevelSmallIds = (1..6).map {
            svc.upload.upload("rsn1-invoice-$it.pdf", "Beleg rsn1-invoice-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!!
        }
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(scan.id!!) + invoiceLevelSmallIds)
        val item1Ids = (7..12).map {
            svc.upload.upload("rsn1-item1-$it.pdf", "Beleg rsn1-item1-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!!
        }
        svc.link.addReceiptsToItem(item1, item1Ids)
        val item2Ids = (13..18).map {
            svc.upload.upload("rsn1-item2-$it.pdf", "Beleg rsn1-item2-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!!
        }
        svc.link.addReceiptsToItem(item2, item2Ids)
        em.flush(); em.clear()

        val budget = svc.budget.invoiceDocumentBudget(invoice.id!!)
        assertEquals(19, budget.distinctReceiptCount, "the reduced scan plus 18 small receipts must be exactly 19 distinct receipts")
        assertEquals(
            BudgetVerdict.Accepted, budget.verdict,
            "19 receipts, even including a full scan reduced down to a few hundred KiB, must stay inside the 20 MiB budget",
        )

        val dto = mapToDto(invoice.id!!)
        val pdfBytes = exportPdf(dto, "rsn-1")
        assertTrue(
            pdfBytes.size.toLong() <= ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE_BYTES,
            "the exported invoice document must stay within the 20 MiB hard cap, was ${pdfBytes.size} bytes",
        )
        Loader.loadPDF(pdfBytes).use { doc ->
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles?.names
            assertEquals(1, embeddedFiles?.size, "exactly one embedded file carries all 19 receipts, none of them a second time")
        }
        val xml = extractEmbeddedXml(pdfBytes) ?: error("the export must carry exactly one embedded XML")
        val attachments = EInvoiceXml.textList(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:AttachmentBinaryObject",
        )
        assertEquals(19, attachments.size, "one BG-24 AttachmentBinaryObject per distinct receipt, including the reduced scan")
    }

    // ── RSN-2 — border: reduction genuinely exhausted, nothing is persisted ────────────────────

    @Test
    @Transactional
    fun `RSN-2 - border - a 12-page scan that stays oversized after both reduction stages is refused and nothing is persisted`() {
        val stubbornBytes = stubbornScanPdf(pageCount = 12, seed = 20260929L)
        assertTrue(
            stubbornBytes.size.toLong() <= ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES,
            "the fixture must still pass the raw upload cap, so it is the reduction path — not the upload-too-large guard — that refuses it, " +
                "was ${stubbornBytes.size} bytes",
        )

        val svc = receiptService()
        val before = receiptRepository.count()

        val ex = assertThrows(ReceiptReductionFailedException::class.java) {
            svc.upload.upload("rsn2-stubborn.pdf", "Beleg rsn2-stubborn.pdf", stubbornBytes, LocalDate.of(2026, 3, 14))
        }
        assertEquals(12, ex.pageCount, "every page of the source PDF must be reported, none dropped along the way")
        assertTrue(
            ex.resultingSize > ReceiptSizePolicy.TARGET_RECEIPT_SIZE,
            "the reported resulting size must still exceed the target the reducer aimed for, was ${ex.resultingSize}",
        )

        assertEquals(before, receiptRepository.count(), "a refused upload must not persist any receipt row")
    }

    /** Drives the real Mustang export route against the real (S3-backed) [documentStore], with
     *  only the render step stubbed — same convention as [ReceiptBudgetE2EIT.exportPdf]. */
    private fun exportPdf(dto: InvoiceDto, jobId: String): ByteArray {
        val renderer: de.littletrotter.shared.BasePdfRendererPort = org.mockito.kotlin.mock()
        org.mockito.kotlin.whenever(renderer.render(org.mockito.kotlin.any())).thenReturn(blankInvoicePage())

        val service = de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory.create(
            xmlExportService = de.littletrotter.xpdf.impl.XmlExportServiceImpl(documentStore),
            reportRenderer = renderer,
            documentStore = documentStore,
            kositValidator = object : de.littletrotter.shared.KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) =
                    de.littletrotter.shared.KositValidationResult(status = de.littletrotter.shared.KositOutcome.VALID)
            },
        )
        val objectKey = service.performExport(dto, jobId).objectKey
        check(documentStore.exists(objectKey)) { "expected exported PDF at $objectKey" }
        return documentStore.get(objectKey)
    }
}
