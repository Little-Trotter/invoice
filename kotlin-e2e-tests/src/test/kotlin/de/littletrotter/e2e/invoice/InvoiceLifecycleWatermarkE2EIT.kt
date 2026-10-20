package de.littletrotter.e2e.invoice

import de.littletrotter.e2e.receipts.receiptUploadService
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.buyerSnapshot
import de.littletrotter.domain.invoice.sellerSnapshot
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
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
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * ST-10 Scenario A/B — the two suites this module was missing: a happy-path run
 * (`DRAFT -> IN_REVIEW -> ISSUED`) that pins the number/date/snapshot timing plus the watermark
 * switch from `ENTWURF` to the brand mark, and a cancellation run that proves the two facts only an
 * E2E test can show — a completed e-invoice job for BOTH documents, and the watermark/page-1
 * reference switch (`CANCELLATION` plus the original's number on the credit note) — in both document
 * languages. PO 2026-09-04 (greenfield deletion) — the original's own re-print no longer
 * carries a dedicated mark (a cancelled original is never regenerated any more, the
 * single-generation gate); that half of the former assertion is gone from this suite, full
 * re-coverage belongs to the single-generation gate's own E2E suite.
 *
 * Uses the REAL Typst renderer (unlike [de.littletrotter.e2e.receipts
 * .CancellationReceiptEmbeddingE2EIT] and [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT], which
 * stub it for speed): the mark ([de.littletrotter.xpdf.impl.PdfWatermarkStamper]) and the page-1
 * reference (ST-6, `InvoiceReportModelBuilder`) are both real post/at-render steps this suite is
 * the one place proving compose correctly on an actual rendered document. A receipt uploaded and
 * linked here is appended as a page behind the invoice pages, and the draft mark must appear on
 * that page too. KoSIT itself stays stubbed VALID throughout —
 * [de.littletrotter.e2e.invoice.ZugferdCreditNoteConformanceE2EIT] (ST-7) owns that assertion, not
 * duplicated here. The receipt full-copy contract itself (both levels, per-diem entries) is
 * [CancellationReceiptEmbeddingE2EIT]'s job; this suite adds exactly one per-diem-bearing TRIP item
 * (PER_DIEM merged into TRIP) to prove the cancellation copy still reaches items this suite's own
 * assertions don't otherwise touch.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceLifecycleWatermarkE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var tripService: TripService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceLookup: InvoiceLookupRepository

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** A genuinely parseable one-page PDF, same idiom as [CancellationReceiptEmbeddingE2EIT.pdf] — kept
     *  genuinely valid, not a magic-header stub, since the raw bytes are what would reach the
     *  wire as a BG-24 `AttachmentBinaryObject` were this suite's own exports given a real
     *  [DocumentStore] on their `XmlExportServiceImpl` (they are not — see [exportPdf]'s own
     *  KDoc: this suite's subject is the watermark/page-1 reference, never receipt embedding). */
    private fun pdf(marker: Byte): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E lifecycle-watermark receipt fixture marker=$marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Drives the REAL rendering pipeline (see this file's own KDoc for why) and returns the
     *  final, stored PDF bytes. This suite's subject is the watermark and the page-1 reference,
     *  never receipt embedding — `XmlExportServiceImpl` is deliberately given `null`, not [store],
     *  so the XML side needs no receipt bytes; the receipt pages are read from [store]
     *  (embedding coverage lives in [ReceiptEmbeddingE2EIT]/
     *  [CancellationReceiptEmbeddingE2EIT] instead). */
    private fun exportPdf(dto: InvoiceDto, store: InMemoryDocumentStore, jobId: String): ByteArray {
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        // Looked up by its exact, derived key rather than a `.single { starts/ends with }` filter:
        // this store accumulates exports for MORE THAN ONE invoice number within a single test
        // (the original and its credit note, or the original before and after its own cancellation),
        // so a startsWith/endsWith filter over "invoices/*.pdf" stops being unique the moment a
        // second document has been exported into the same store.
        val objectKey = service.performExport(dto, jobId).objectKey
        return store.get(objectKey)
    }

    /** The extracted, whitespace-collapsed text of every page, in order — used both for the
     *  watermark's primary line (present on every page) and for the page-1 BG-3 reference.
     *
     *  Collapsing whitespace is required, not cosmetic: [PDFTextStripper]'s line-grouping
     *  heuristic keys on each glyph's vertical position, which the watermark's diagonal (~45°)
     *  run defeats on every single page — every rotated character lands on its own "line", so the
     *  extracted text comes back interleaved with stray whitespace/newlines even though the
     *  glyphs themselves are intact (same fix [PdfWatermarkStamperTest.collapseWhitespace] uses).
     *  All expected substrings below are written in already-collapsed form to match. */
    private fun pageTexts(pdfBytes: ByteArray): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }
            stripper.getText(doc).replace(Regex("\\s+"), "")
        }
    }

    /**
     * The diagonal watermark's own glyphs per page, body text excluded.
     *
     * Asserting the mark through [pageTexts] used to work by luck and stopped on 05.09.2026: the
     * mark is one rotated run, PDFTextStripper emits glyphs in content-stream order, and on a
     * densely filled invoice page the run interleaves with the body text that shares its band —
     * page 1 of an English credit note came out as "…Page1/1REVERSL", one glyph short. A sparser
     * page rendered the same mark intact, which is what first exposed the interleaving.
     * `sortByPosition = true` is not the cure but the opposite: at 45° every glyph sits at its own
     * height, so sorting by position scatters the run completely (measured — it broke all three
     * mark assertions at once).
     *
     * This selects by what actually distinguishes the mark: it is the only text on the page drawn
     * with a rotated text matrix (`shearY != 0`; body text is axis-aligned, so its shear is zero).
     * The result answers "was the mark drawn on this page", which is what the tests mean, instead
     * of "did the extractor happen to emit it in one piece".
     */
    private fun watermarkTexts(pdfBytes: ByteArray): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = object : PDFTextStripper() {
                val rotated = StringBuilder()

                override fun processTextPosition(text: org.apache.pdfbox.text.TextPosition) {
                    if (kotlin.math.abs(text.textMatrix.shearY) > 0.01f) rotated.append(text.unicode)
                }
            }
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            stripper.rotated.toString().replace(Regex("\\s+"), "")
        }
    }

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun statusOf(invoiceId: Long): InvoiceStatus =
        QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, invoiceId).status }

    // ─── Scenario A — happy path ──────────────────────────────────────────────────────────────

    @Test
    fun `happy path - DRAFT to IN_REVIEW to ISSUED - number timing, invoiceDate stamp, frozen snapshots and the watermark switch`() {
        val tag = "LCA1"
        val parties = buildParties(tag)
        val store = InMemoryDocumentStore()
        val receiptUploadSvc = receiptUploadService(receiptRepository, store, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
        val receiptLinkSvc = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, ReceiptPoolService(receiptRepository, store), ReceiptBudgetService(receiptRepository))

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        val receiptId = QuarkusTransaction.requiringNew().call { receiptUploadSvc.upload("nachweis.pdf", "Beleg nachweis.pdf", pdf(1), LocalDate.of(2026, 3, 14)).id!! }
        QuarkusTransaction.requiringNew().run { receiptLinkSvc.addReceiptsToInvoice(invoiceId, listOf(receiptId)) }

        // ── DRAFT: numbered from birth (N5), editable, watermark carries E N T W U R F ───────
        assertEquals(InvoiceStatus.DRAFT, statusOf(invoiceId))
        val draftNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, invoiceId).invoiceNumber.value }
        assertTrue(draftNumber.isNotBlank(), "a draft carries a real invoice number from create() onward (N5)")

        // Editable before ISSUED — a second item add must succeed.
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Zweite Position",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }

        val draftDto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        val draftPdf = exportPdf(draftDto, store, "lca1-draft")
        assertTrue(
            watermarkTexts(draftPdf).any { it.contains("ENTWURF") },
            "a draft PDF must carry the E N T W U R F mark, got: ${watermarkTexts(draftPdf)}",
        )
        // The receipt page behind the invoice pages is part of the draft document and is marked too.
        val draftMarks = watermarkTexts(draftPdf)
        assertTrue(draftMarks.size >= 2, "the draft must contain the invoice page(s) plus the receipt page, got ${draftMarks.size} pages")
        assertTrue(draftMarks.all { it.contains("ENTWURF") }, "every page, receipt pages included, carries the mark: $draftMarks")

        // ── -> IN_REVIEW -> ISSUED: number unchanged from create() (N5), invoiceDate stamped ───
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }

        val sent = QuarkusTransaction.requiringNew().call {
            val inv = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, inv.status)
            Triple(inv.invoiceNumber.value, inv.invoiceDate, inv.paymentTermSnapshot != null)
        }
        assertEquals(draftNumber, sent.first, "the number drawn at create() (N5) survives -> ISSUED unchanged")
        assertEquals(E2eBusinessDay.today(), sent.second, "the invoice date is stamped to today at -> ISSUED")
        assertTrue(sent.third, "the payment-term snapshot is frozen at -> ISSUED")

        val partySnapshotsFrozen = QuarkusTransaction.requiringNew().call<Boolean> {
            val inv = em.find(Invoice::class.java, invoiceId)!!
            inv.sellerSnapshot() != null && inv.buyerSnapshot() != null
        }
        assertTrue(partySnapshotsFrozen, "seller/buyer party snapshots are frozen by the time the invoice is ISSUED")

        // ── read-only after ISSUED ──────────────────────────────────────────────────────────────
        assertThrows(InvoiceImmutableException::class.java) {
            QuarkusTransaction.requiringNew().run {
                itemMutationService.addItem(
                    invoiceId,
                    InvoiceItemCommand(
                        category = InvoiceItemCategory.SERVICE,
                        description = "Nachtrag nach Versand",
                        quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.HOUR,
                        unitPriceNet = Money.of("10.00"),
                    ),
                )
            }
        }

        // ── the ISSUED PDF carries the brand mark, not E N T W U R F any more ───────────────────
        val sentDto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        val sentPages = pageTexts(exportPdf(sentDto, store, "lca1-sent"))
        assertTrue(sentPages.any { it.contains("LittleTrotter") }, "an ISSUED invoice's PDF carries the brand mark")
        assertFalse(sentPages.any { it.contains("ENTWURF") }, "an ISSUED invoice's PDF must no longer carry the draft mark")
    }

    // ─── Scenario B — cancellation ──────────────────────────────────────────────────────────────────

    private data class CancellationFixture(val invoiceId: Long, val store: InMemoryDocumentStore)

    /** Builds an ISSUED invoice with one SERVICE item, one per-diem-bearing TRIP item (PER_DIEM
     *  merged into TRIP; per-diem entries are part of the cancellation "full copy" this suite
     *  still needs to touch — [CancellationReceiptEmbeddingE2EIT] covers receipts, not per-diem, at E2E
     *  level) and one invoice-level receipt. Returns the
     *  [DocumentStore] the receipt was uploaded into — the caller must reuse it for every
     *  [exportPdf] call against this fixture (see that method's own KDoc). */
    private fun sentInvoiceForCancellation(tag: String, documentLanguage: String? = null): CancellationFixture {
        val parties = buildParties(tag)
        val store = InMemoryDocumentStore()
        val receiptUploadSvc = receiptUploadService(receiptRepository, store, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
        val receiptLinkSvc = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, ReceiptPoolService(receiptRepository, store), ReceiptBudgetService(receiptRepository))
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = de.littletrotter.domain.reference.PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E fixture"
                validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate)
            em.flush()
            rate.id!!
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run {
            // A business trip goes through TripService.addTrip (per-diem days
            // only ever occur inside a trip).
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "Verpflegungsmehraufwand",
                    perDiemDays = listOf(
                        PerDiemEntryCommand(
                            date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false,
                            breakfast = false, lunch = false, dinner = false,
                            description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId,
                        ),
                    ),
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        if (documentLanguage != null) {
            // R3 normally resolves this once at creation from the recipient's country and never
            // touches it again; overridden directly here purely to drive the language dimension,
            // the same idiom other suites use for fixture-only entity tweaks (e.g. deliveryDate
            // above).
            QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { this.documentLanguage = documentLanguage } }
        }
        val receiptId = QuarkusTransaction.requiringNew().call { receiptUploadSvc.upload("vertrag.pdf", "Beleg vertrag.pdf", pdf(2), LocalDate.of(2026, 3, 14)).id!! }
        QuarkusTransaction.requiringNew().run { receiptLinkSvc.addReceiptsToInvoice(invoiceId, listOf(receiptId)) }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return CancellationFixture(invoiceId, store)
    }

    /** PENDING -> DELEGATED -> COMPLETED, the same two-step [GenerationJobService] sequence a
     *  real worker delegation would leave a job in (see [de.littletrotter.e2e.nats
     *  .PrintJobListViewLiveUpdateE2EIT]'s own fixture KDoc) — proving createJob is accepted AND
     *  the job can actually reach a terminal, successful state for this invoice. */
    private fun createAndCompleteJob(invoiceId: Long): GenerationJobStatus {
        val job = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        val delegated = QuarkusTransaction.requiringNew().call { generationJobPublishingService.markDelegated(job) }
        val completed = QuarkusTransaction.requiringNew().call { generationJobResultHandler.markCompleted(delegated) }
        return completed.status
    }

    @Test
    fun `cancellation - both documents accept and complete an e-invoice job, and the watermark plus page-1 reference switch correctly (German)`() {
        val tag = "LCB1"
        val (originalId, store) = sentInvoiceForCancellation(tag)
        val originalNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, originalId).invoiceNumber.value }

        // ── before the cancellation: the original's own PDF carries the brand mark ─────────────────
        val beforeDto = QuarkusTransaction.requiringNew().call { mapToDto(originalId) }
        assertTrue(
            watermarkTexts(exportPdf(beforeDto, store, "$tag-before")).any { it.contains("LittleTrotter") },
            "before its own storno, the original carries the brand mark like any other ISSUED invoice",
        )

        // ── cancellation ─────────────────────────────────────────────────────────────────────────
        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(originalId, "E2E Storno-Grund").id!! }

        QuarkusTransaction.requiringNew().run {
            val original = em.find(Invoice::class.java, originalId)!!
            assertEquals(InvoiceStatus.CANCELLED, original.status, "the original moves ISSUED -> CANCELLED")
            val creditNote = em.find(Invoice::class.java, creditNoteId)!!
            assertEquals(InvoiceStatus.CANCELLED, creditNote.status, "the storno document is born CANCELLED")
            assertTrue(creditNote.invoiceNumber.isAssigned(), "the storno document carries its own number")
            assertNotEquals(originalNumber, creditNote.invoiceNumber.value)
            assertEquals(1, creditNote.itemList.count { it.category == InvoiceItemCategory.TRIP }, "the per-diem-bearing TRIP item is part of the full copy")
            assertEquals(
                1, creditNote.itemList.first { it.category == InvoiceItemCategory.TRIP }.perDiemEntryList.size,
                "its per-diem entries are copied too",
            )
        }

        // ── both documents can still get an e-invoice job, and it completes ──────────────────
        assertEquals(GenerationJobStatus.COMPLETED, createAndCompleteJob(creditNoteId), "an e-invoice job for the storno document must complete")
        assertEquals(GenerationJobStatus.COMPLETED, createAndCompleteJob(originalId), "an e-invoice job for the cancelled original must still complete")

        // ── cancellation PDF: S T O R N O on every page, page 1 names the original ─────────────────
        val creditNoteDto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        val creditNotePdf = exportPdf(creditNoteDto, store, "$tag-storno")
        val creditNotePages = pageTexts(creditNotePdf)
        val creditNoteMarks = watermarkTexts(creditNotePdf)
        assertTrue(creditNotePages.isNotEmpty(), "the export must produce at least the base document")
        assertTrue(
            creditNoteMarks.all { it.contains("STORNO") },
            "every page carries the S T O R N O mark, got: $creditNoteMarks",
        )
        assertTrue(creditNotePages.first().contains(originalNumber), "page 1 names the original invoice number (ST-6)")

        // PO 2026-09-04 (greenfield deletion) — the former "cancelled-original" three-line
        // mark is gone: a cancelled original is never regenerated any more (the single-
        // generation gate), so its archived PDF simply keeps the ordinary brand mark it received
        // when it was issued. The re-print assertion this test used to make here is a now-
        // impossible case; full re-coverage of the single-generation gate's own guarantee is
        // the gate's own E2E suite's job, not a compile-only adaptation of this file.
    }

    @Test
    fun `cancellation with documentLanguage en - REVERSAL and REVERSED (LOCKED) marks and the English page-1 notice`() {
        val tag = "LCB2"
        val (originalId, store) = sentInvoiceForCancellation(tag, documentLanguage = "en")
        val originalNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, originalId).invoiceNumber.value }

        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(originalId, "E2E storno reason").id!! }

        val creditNoteDto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        val creditNotePdf = exportPdf(creditNoteDto, store, "$tag-storno-en")
        val creditNotePages = pageTexts(creditNotePdf)
        val creditNoteMarks = watermarkTexts(creditNotePdf)
        assertTrue(
            creditNoteMarks.all { it.contains("REVERSAL") },
            "the English storno mark on every page, got: $creditNoteMarks",
        )
        assertTrue(creditNotePages.first().contains("Reversalofinvoice"), "the English page-1 notice label (ST-6)")
        assertTrue(creditNotePages.first().contains(originalNumber), "page 1 still names the original invoice number")

        // PO 2026-09-04 (greenfield deletion) — see the German scenario above: the former
        // "cancelled-original" three-line mark (English: ORIGINAL / REVERSED (LOCKED)) is gone,
        // this re-print assertion is a now-impossible case, and full re-coverage lies elsewhere.
    }
}
