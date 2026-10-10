package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.InvoiceDocumentSizeExceededException
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.application.invoice.ReceiptUnreducedTooLargeException
import de.littletrotter.application.invoice.ReceiptUploadTooLargeException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.receipt.BudgetVerdict
import de.littletrotter.shared.receipt.DocumentSize
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
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

/**
 * End-to-end coverage for the invoice document size budget: 19 distinct
 * receipts per invoice, 20 MiB total (1 MiB reserve + 19 x 1 MiB), 1 MiB per file, all through the
 * real Postgres + S3 stack, no mocks for the pieces under test. Idiom copied from
 * [ReceiptS3EmbeddingE2EIT] — same fixture-builder shape, same real-export/real-store proof pattern —
 * so this file duplicates its small per-file helpers rather than introducing a shared base class
 * neither file currently has.
 *
 * RB-1 is the happy path; RB-2..RB-7 are the six border cases. RB-8 (IR7-4 follow-up) adds the READ side the UI consumes:
 * the budget state behind the size-exhausted hint.
 */

/** RB-4's "over the limit" boundary: 1.5 MiB, not exactly one byte past the cap. */
private const val BOUNDARY_OVER_LIMIT_BYTES = 1_572_864

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class ReceiptBudgetE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    /** The receipt services, split into 4 units; a plain field bundle, no forwarding methods,
     *  since this fixture is shared across tests that each only need a subset. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    /** Wires the real [de.littletrotter.infrastructure.receipt.PdfBoxReceiptPdfReducer], not the
     *  no-op stub — RB-4 exercises actual `REDUCE` behaviour on an oversized fixture, so it needs
     *  the real reduction pipeline; every other test's fixtures are already under the target size,
     *  where the reducer's first check (`size <= target`) short-circuits before touching the bytes,
     *  so this wiring changes nothing for them. Same idiom as [ReceiptNormalisationE2EIT.receiptService]. */
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

    /** A real, parseable one-page PDF, one per distinct [marker] — the real Mustang export needs
     *  valid PDF for PDFBox parsing, and a distinct marker gives a distinct checksum/object key. */
    private fun pdf(marker: Int): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("IR-6 budget fixture marker=$marker")
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
                cs.showText("IR-6 budget E2E base page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** Same fixture shape as [ReceiptS3EmbeddingE2EIT.buildInvoiceFixture]: a two-item DRAFT invoice,
     *  persisted via plain JPA. */
    private fun buildInvoiceFixture(tag: String): Invoice {
        val sender = SenderProfile().apply {
            name = "Belegbudget E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
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

    /** Bypasses [ReceiptUploadService.upload]'s 1 MiB per-file cap on purpose (border case RB-5 needs
     *  receipts larger than any real upload could ever produce) — a direct JPA insert, the same
     *  way a stock receipt from before the size budget could exist. [Receipt.validate] is never called by JPA itself
     *  (no `@PrePersist` on the entity), so this is a legitimate way to construct a fixture the
     *  application's own upload path could never create. */
    private fun persistOversizedReceipt(tag: String, index: Int, sizeBytes: Long): Receipt {
        val receipt = Receipt().apply {
            fileName = "$tag-oversized-$index.pdf"
            description = "Beleg $tag-oversized-$index"
            objectKey = "fake/$tag/oversized-$index"
            this.sizeBytes = sizeBytes
            sha256Checksum = "fake-checksum-$tag-$index".padEnd(64, '0')
            uploadedAt = OffsetDateTime.now()
            documentDate = LocalDate.of(2026, 3, 14)
        }
        em.persist(receipt)
        return receipt
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Extracts the embedded `factur-x.xml`/`xrechnung.xml` attachment's own bytes from the
     *  exported hybrid PDF — same detection idiom [de.littletrotter.e2e.einvoice
     *  .Bt19EndToEndE2EIT.extractEmbeddedXml] uses, duplicated locally per this module's own
     *  per-file-helper convention (see this class's own KDoc). `null` when no such attachment
     *  exists. */
    private fun extractEmbeddedXml(pdfBytes: ByteArray): String? = try {
        Loader.loadPDF(pdfBytes).use { doc ->
            val names = doc.documentCatalog.names?.embeddedFiles?.names ?: emptyMap()
            val spec = names["factur-x.xml"] ?: names["xrechnung.xml"]
            spec?.embeddedFile?.toByteArray()?.let { String(it, Charsets.UTF_8) }
        }
    } catch (ex: Exception) {
        null
    }

    // ── RB-1 — happy path: 19 distinct receipts across positions and invoice level, exported ────

    @Test
    @Transactional
    fun `RB-1 - happy path - 19 distinct receipts spread across positions and invoice level each embed as their own BG-24 entry, never as annex pages`() {
        val invoice = buildInvoiceFixture("RB1")
        val item1 = invoice.itemList[0].id!!
        val item2 = invoice.itemList[1].id!!
        val svc = receiptService()

        // 7 at invoice level, 6 on item1, 6 on item2 — 19 distinct receipts total.
        val invoiceLevelIds = (1..7).map { svc.upload.upload("rb1-invoice-$it.pdf", "Beleg rb1-invoice-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToInvoice(invoice.id!!, invoiceLevelIds)
        val item1Ids = (8..13).map { svc.upload.upload("rb1-item1-$it.pdf", "Beleg rb1-item1-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToItem(item1, item1Ids)
        val item2Ids = (14..19).map { svc.upload.upload("rb1-item2-$it.pdf", "Beleg rb1-item2-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToItem(item2, item2Ids)
        em.flush(); em.clear()

        val budget = svc.budget.invoiceDocumentBudget(invoice.id!!)
        assertEquals(19, budget.distinctReceiptCount, "19 distinct receipts, just under the 20 MiB budget")

        val dto = mapToDto(invoice.id!!)
        val pdfBytes = exportPdf(dto, "rb-1")

        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages, "receipts never add pages to the PDF any more — only the base invoice page")
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles?.names
            assertEquals(1, embeddedFiles?.size, "exactly one embedded file, none of the 19 belege a second time")
        }
        val xml = extractEmbeddedXml(pdfBytes) ?: error("the export must carry exactly one embedded XML")
        val names = EInvoiceXml.textList(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:IssuerAssignedID",
        )
        assertEquals(19, names.size, "one BG-24 entry per distinct receipt")
        (1..19).forEach { marker ->
            val name = when {
                marker <= 7 -> "rb1-invoice-$marker.pdf"
                marker <= 13 -> "rb1-item1-$marker.pdf"
                else -> "rb1-item2-$marker.pdf"
            }
            assertTrue(names.contains(name), "receipt $name must be embedded as its own BG-24 entry")
        }
    }

    // ── RB-2 — border: exactly 19 accepted, the 20th refused and nothing written ────────────────

    @Test
    @Transactional
    fun `RB-2 - border - exactly 19 receipts are accepted, the 20th is refused and nothing is written`() {
        val invoice = buildInvoiceFixture("RB2")
        val svc = receiptService()

        val nineteenIds = (1..19).map { svc.upload.upload("rb2-$it.pdf", "Beleg rb2-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!! }
        svc.link.addReceiptsToInvoice(invoice.id!!, nineteenIds)
        em.flush(); em.clear()
        assertEquals(0, svc.budget.invoiceDocumentBudget(invoice.id!!).remainingSlots, "no slots left after 19")

        val twentieth = svc.upload.upload("rb2-20.pdf", "Beleg rb2-20.pdf", pdf(20), LocalDate.of(2026, 3, 14))
        val ex = assertThrows(ReceiptCountLimitExceededException::class.java) {
            svc.link.addReceiptsToInvoice(invoice.id!!, listOf(twentieth.id!!))
        }
        assertEquals(19, ex.limit)
        assertEquals(20, ex.attempted)

        val afterFailure = svc.link.findReceiptsForInvoice(invoice.id!!)
        assertEquals(19, afterFailure.size, "the invoice's receipt set must be unchanged after the failed 20th write")
        assertTrue(afterFailure.none { it.fileName == "rb2-20.pdf" }, "the refused receipt must not have been attached")
    }

    // ── RB-3 — border: dedup — 19 distinct documents used 30 times must still be accepted ───────

    @Test
    @Transactional
    fun `RB-3 - border - 19 distinct documents used 30 times count as 19, not 30, and are accepted`() {
        val invoice = buildInvoiceFixture("RB3")
        val item1 = invoice.itemList[0].id!!
        val svc = receiptService()

        val nineteenIds = (1..19).map { svc.upload.upload("rb3-$it.pdf", "Beleg rb3-$it.pdf", pdf(it), LocalDate.of(2026, 3, 14)).id!! }
        // 19 links at invoice level, plus the first 11 of the same receipts ALSO on item1 —
        // 19 + 11 = 30 total link rows across 19 distinct documents.
        svc.link.addReceiptsToInvoice(invoice.id!!, nineteenIds)
        svc.link.addReceiptsToItem(item1, nineteenIds.take(11))
        em.flush(); em.clear()

        val budget = svc.budget.invoiceDocumentBudget(invoice.id!!)
        assertEquals(19, budget.distinctReceiptCount, "deduplicated by object key: 19 distinct, not 30")
        assertEquals(0, budget.remainingSlots, "exactly at the count cap")

        val totalLinks = nineteenIds.sumOf { svc.pool.findReceiptUsage(it).size }
        assertEquals(30, totalLinks, "30 independent junction rows across the 19 distinct documents")
    }

    // ── RB-4 — border: unreduced upload at the file limit; REDUCE on the same bytes; the upload cap ─

    @Test
    fun `RB-4 - border - KEEP_UNREDUCED accepts exactly 1 MiB unchanged and refuses one over, REDUCE brings the same bytes under target, past the upload cap is refused outright`() {
        // Boundary fixtures — byte-exact PDFs built in-process by [BoundaryPdfFixtures], not
        // read from an external dataset generator: the "over" fixture is 1.5 MiB per its own
        // spec (1_572_864 bytes), not exactly 1 MiB + 1 byte.
        val exactlyOneMib = BoundaryPdfFixtures.exactSizePdf(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES.toInt())
        val overOneMib = BoundaryPdfFixtures.exactSizePdf(BOUNDARY_OVER_LIMIT_BYTES)
        val overUploadCap = BoundaryPdfFixtures.exactSizePdf((ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES + 1).toInt())
        assertEquals(
            ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES, exactlyOneMib.size.toLong(),
            "the boundary fixture must be exactly the policy limit for this test to prove the boundary",
        )
        assertTrue(overOneMib.size > ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES, "the 'over' fixture must exceed the unreduced cap")

        val svc = receiptService()

        // KEEP_UNREDUCED: bytes are stored exactly as uploaded, up to the unreduced cap.
        val acceptedUnreduced = svc.upload.upload(
            "rb4-exactly-1mib.pdf", "Beleg rb4-exactly-1mib.pdf", exactlyOneMib, LocalDate.of(2026, 3, 14),
            mode = ReceiptUploadMode.KEEP_UNREDUCED,
        )
        assertEquals(ReceiptNormalisationStage.NONE, acceptedUnreduced.normalisationStage, "KEEP_UNREDUCED never touches the bytes")
        assertEquals(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES, acceptedUnreduced.sizeBytes, "stored unchanged at exactly the cap")

        val unreducedEx = assertThrows(ReceiptUnreducedTooLargeException::class.java) {
            svc.upload.upload(
                "rb4-over-1mib-unreduced.pdf", "Beleg rb4-over-1mib-unreduced.pdf", overOneMib, LocalDate.of(2026, 3, 14),
                mode = ReceiptUploadMode.KEEP_UNREDUCED,
            )
        }
        assertEquals(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE, unreducedEx.limit)
        assertEquals(DocumentSize.ofBytes(overOneMib.size.toLong()), unreducedEx.uploaded)

        // REDUCE (default mode): the same oversized bytes are reduced towards the target instead of
        // refused. The fixture's content stream carries only whitespace padding — no image, nothing
        // drawn — so stage 1 (image downsampling) cannot help, but stage 2 (page rasterisation)
        // renders the effectively blank page to a JPEG that lands far under the target.
        val reduced = svc.upload.upload("rb4-over-1mib-reduced.pdf", "Beleg rb4-over-1mib-reduced.pdf", overOneMib, LocalDate.of(2026, 3, 14))
        assertTrue(
            reduced.sizeBytes!! <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES,
            "reduced bytes must be at or under the target, was ${reduced.sizeBytes}",
        )
        assertEquals(ReceiptNormalisationStage.RASTERISED, reduced.normalisationStage, "no image to downsample — stage 2 rasterisation is the only stage that can help here")
        assertEquals(overOneMib.size.toLong(), reduced.originalSizeBytes, "the pre-reduction upload size must still be on record")

        // Beyond MAX_UPLOAD_SIZE, reduction cannot help at all — refused regardless of mode.
        val uploadTooLargeEx = assertThrows(ReceiptUploadTooLargeException::class.java) {
            svc.upload.upload("rb4-over-upload-cap.pdf", "Beleg rb4-over-upload-cap.pdf", overUploadCap, LocalDate.of(2026, 3, 14))
        }
        assertEquals(ReceiptSizePolicy.MAX_UPLOAD_SIZE, uploadTooLargeEx.limit)
        assertEquals(DocumentSize.ofBytes(overUploadCap.size.toLong()), uploadTooLargeEx.uploaded)
    }

    // ── RB-5 — border: the size limit is advisory at link time, a hard stop only at generation ──

    @Test
    @Transactional
    fun `RB-5 - border - fewer than 19 receipts whose sum exceeds the size budget link successfully and are refused only at document generation`() {
        val invoice = buildInvoiceFixture("RB5")
        val svc = receiptService()

        // 5 receipts x 4 MiB raw: Base64-encoded (BG-24's transport channel inflates by 4/3) plus
        // the 1 MiB reserve lands well past the 20 MiB document cap, while the count (5) is nowhere
        // near the 19-receipt cap — real uploads can never build this fixture (each is capped at
        // 1 MiB), so these are persisted directly.
        val perReceiptBytes = 4L * 1024 * 1024
        val receiptCount = 5
        val oversizedIds = (1..receiptCount).map { persistOversizedReceipt("RB5", it, perReceiptBytes).id!! }

        // PO decision: linking no longer refuses an oversized set — the write succeeds.
        svc.link.addReceiptsToInvoice(invoice.id!!, oversizedIds)
        em.flush(); em.clear()

        val linked = svc.link.findReceiptsForInvoice(invoice.id!!)
        assertEquals(receiptCount, linked.size, "the size rule is advisory at link time — all 5 receipts must be attached")

        val expectedEstimated = ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE +
            DocumentSize.sum((1..receiptCount).map { DocumentSize.ofBytes(perReceiptBytes).base64Encoded() })
        val budget = svc.budget.invoiceDocumentBudget(invoice.id!!)
        check(budget.verdict is BudgetVerdict.DocumentTooLarge) { "expected DocumentTooLarge, was ${budget.verdict}" }
        val verdict = budget.verdict as BudgetVerdict.DocumentTooLarge
        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, verdict.limit)
        assertEquals(expectedEstimated, verdict.estimated, "estimated size is reserve + sum of Base64-encoded receipt sizes")

        // The hard stop moves to document generation (GenerationJobCreationService.createJob).
        val ex = assertThrows(InvoiceDocumentSizeExceededException::class.java) {
            generationJobCreationService.createJob(invoice.id!!, "PRINT_JOB", DocumentJobLane.BULK)
        }
        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, ex.limit)
        assertEquals(expectedEstimated, ex.estimated, "generation must refuse with the same estimate the budget reports")
    }

    // ── RB-7 — border: a read-only invoice refuses before any budget arithmetic runs ───────────

    @Test
    @Transactional
    fun `RB-7 - border - a finalized invoice refuses a receipt change before the budget is even evaluated`() {
        val invoice = buildInvoiceFixture("RB7")
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.SENT)
        em.flush()

        val svc = receiptService()
        val receipt = svc.upload.upload("rb7-blocked.pdf", "Beleg rb7-blocked.pdf", pdf(1), LocalDate.of(2026, 3, 14))

        assertThrows(InvoiceImmutableException::class.java) {
            svc.link.addReceiptsToInvoice(invoice.id!!, listOf(receipt.id!!))
        }

        assertTrue(svc.link.findReceiptsForInvoice(invoice.id!!).isEmpty(), "a read-only invoice's receipt set must stay untouched")
    }

    // ── RB-8 — read side: the budget's remaining size/slots under the real Base64 estimator ─────

    @Test
    @Transactional
    fun `RB-8 - read side - a receipt set leaving a fraction of a receipt's size headroom reports remaining size and slots exactly`() {
        val invoice = buildInvoiceFixture("RB8")
        val svc = receiptService()

        // 5 receipts x 2900 KiB raw: Base64-encoded (BG-24's transport channel inflates by 4/3)
        // plus the 1 MiB reserve lands just under the 20 MiB cap on purpose — every expected number
        // below is derived from ReceiptSizePolicy/DocumentSize.base64Encoded() rather than a
        // hand-computed literal that could silently drift from what InvoiceDocumentBudget itself
        // calculates. Real uploads (1 MiB per-file cap) can never build this set, so the receipts
        // are persisted directly, same rationale as RB-5's fixture.
        val perReceiptBytes = 2900L * 1024
        val receiptCount = 5
        val oversizedIds = (1..receiptCount).map { persistOversizedReceipt("RB8", it, perReceiptBytes).id!! }
        svc.link.addReceiptsToInvoice(invoice.id!!, oversizedIds)
        em.flush(); em.clear()

        val budget = svc.budget.invoiceDocumentBudget(invoice.id!!)
        assertEquals(receiptCount, budget.distinctReceiptCount, "all five receipts must have been attached")
        check(budget.verdict == BudgetVerdict.Accepted) { "fixture must stay under the cap, was ${budget.verdict}" }

        val expectedEncodedSum = DocumentSize.sum((1..receiptCount).map { DocumentSize.ofBytes(perReceiptBytes).base64Encoded() })
        val expectedEstimated = ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + expectedEncodedSum
        val expectedRemaining = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE.remainingAfter(expectedEstimated)

        assertEquals(expectedEstimated, budget.estimatedDocumentSize, "estimated size must be reserve + sum of Base64-encoded receipt sizes")
        assertEquals(expectedRemaining, budget.remainingSize, "remaining size must follow the same Base64 estimator the write-path guard uses")
        assertTrue(budget.remainingSize.bytes > 0, "some size headroom remains — this is not the DocumentTooLarge case (RB-5)")
        assertTrue(
            budget.remainingSize < ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE,
            "the headroom left is smaller than a single further receipt could ever legally be",
        )
        assertEquals(
            ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE - receiptCount, budget.remainingSlots,
            "slots follow the count arithmetic exactly",
        )
        assertTrue(budget.remainingSlots > 0, "the count limit must NOT be the binding one in this state")
        assertFalse(budget.isFull, "isFull only trips once a limit has zero headroom left, on either axis")
    }

    /** Drives the real Mustang export route against the real (S3-backed) [documentStore], with
     *  only the render step stubbed — same convention as [ReceiptS3EmbeddingE2EIT.exportPdf]. */
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
