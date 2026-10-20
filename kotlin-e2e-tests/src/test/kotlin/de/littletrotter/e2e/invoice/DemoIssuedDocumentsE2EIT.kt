package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.toView
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.domain.exception.GenerationBlockReason
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.GenerationJobResultRefs
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.archive.Ed25519ArchiveSigner
import de.littletrotter.shared.archive.Ed25519Keys
import de.littletrotter.ui.view.invoice.ValidationOutcome
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog
import de.littletrotter.ui.view.invoice.resolveValidationOutcome
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
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
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.HexFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * WP-C (fix-datagen-issued-documents-without-artifacts.md) — end to end against a real Postgres
 * ([PostgresTestResource]) AND a real RustFS object store ([S3TestResource]): the artifacts a
 * datagen-seeded issued invoice is missing must land in the actual object store, not an in-memory
 * stand-in.
 *
 * Fixtures are built via direct [EntityManager] persistence plus the real [InvoiceLifecycleService]
 * (same idiom as [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT]/[InvoiceGenerationLockE2EIT]);
 * the cancellation pair goes through the real [InvoiceCancellationService.createCreditNote]. The maintenance
 * action under test, [DatabaseMaintenanceService.performQueueIssuedGenerationJobs], only ever
 * queues a PENDING [GenerationJob] via the real [GenerationJobService.createJob] — WP-A's own gate,
 * journal and single-generation checks all apply. [completeIssuedJob] then stands in for the worker: it drives
 * the real Mustang [PdfExportServiceImpl] against the real, S3-backed [documentStore] and the real
 * embedded KoSIT validator ([EmbeddedKositValidator]) to produce genuine PDF/XML/archive bytes and
 * a genuine KoSIT report, then feeds the result into [GenerationJobService.markCompleted] exactly
 * the way [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]'s own COMPLETED
 * branch does — the same resultRefs shape [de.littletrotter.xpdf.JobConsumer
 * .publishCompletedResult] builds. Every assertion below reads back persisted state (the
 * `generation_job`/`invoice_document` rows, the object store, the UI-layer download/report
 * builders) — never a mock's recorded call.
 *
 * Shared, never-cleaned-between-classes database (same convention as
 * [PostgresDatabaseMaintenanceServiceIT][de.littletrotter.infrastructure.persistence
 * .PostgresDatabaseMaintenanceServiceIT]/[RecalculationSweepMalformedRowE2EIT]): every fixture
 * invoice below carries a scenario-tag in its sender/recipient names, and every assertion reads
 * back that invoice's OWN generation-job/document rows by id — never a global count across the
 * whole table, which the sweep also touches for whatever unrelated rows this shared database
 * already carries.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class DemoIssuedDocumentsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var maintenanceService: DatabaseMaintenanceService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var generationJobRecoveryService: GenerationJobRecoveryService

    @Inject
    lateinit var generationJobRepository: GenerationJobRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceDetailQueryPort: InvoiceDetailQueryPort

    companion object {
        /** The real, embedded KoSIT validator — shared across test methods, same convention as
         *  [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT] (loading the XRechnung scenario
         *  ZIP is comparatively expensive and it carries no per-test state). */
        private lateinit var kosit: EmbeddedKositValidator

        /** Regression detector for the invoice/xpdf `s3.*`/`invoice.s3.*` property-name drift that
         *  originally let this class's generated documents silently fall back to the real
         *  filesystem [de.littletrotter.infrastructure.storage.DocumentStore] under the host's own
         *  home directory instead of [S3TestResource]'s per-run RustFS container: a plain file
         *  count under the real `~/.little-trotter`, taken before this class's fixtures run and
         *  compared after every test method has finished. */
        private val realHomeDocumentsDirectory: Path = Paths.get(System.getProperty("user.home"), ".little-trotter")
        private var homeDocumentsBaselineCount: Long = 0

        private fun countFilesUnderRealHome(): Long =
            if (Files.isDirectory(realHomeDocumentsDirectory)) {
                Files.walk(realHomeDocumentsDirectory).use { paths -> paths.filter(Files::isRegularFile).count() }
            } else {
                0
            }

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
            homeDocumentsBaselineCount = countFilesUnderRealHome()
        }

        @JvmStatic
        @AfterAll
        fun assertRealHostHomeGainedNoDocuments() {
            // Intentionally "no gain", not exact equality: this directory is the real machine-wide
            // home directory, so an unrelated process (another agent session, a manual cleanup)
            // can legitimately shrink it between the baseline and this check. A regression in the
            // invoice.* storage properties can only ever grow it.
            assertTrue(
                countFilesUnderRealHome() <= homeDocumentsBaselineCount,
                "generated documents must stay inside S3TestResource's per-run container, never under " +
                    "the real $realHomeDocumentsDirectory -- a growing file count here means the " +
                    "invoice.* storage properties regressed back to the filesystem default",
            )
        }
    }

    /** A real, freshly generated Ed25519 keypair signs every archive package this suite's
     *  [completeIssuedJob] builds — without a real [de.littletrotter.shared.archive.ArchiveSigner]
     *  [PdfExportServiceImpl] never builds an archive package at all (see its own KDoc). */
    private val archiveKeyPair = Ed25519Keys.generateKeyPair()
    private val archiveSigner = Ed25519ArchiveSigner(archiveKeyPair.private, Ed25519Keys.fingerprint(archiveKeyPair.public))

    private val objectMapper = jacksonObjectMapper()
    private val createdInvoiceIds = mutableListOf<Long>()

    /** Cascade-deletes every invoice this test created (generation_job/invoice_document both carry
     *  `ON DELETE CASCADE` off `invoice_id` in the baseline schema, so removing the invoice is
     *  enough). `invoice_audit_journal` is deliberately left untouched -- it carries NO FK to
     *  `invoice` at all (append-only, see the baseline schema's own comment on that table) and is
     *  append-only for every role; nothing here ever attempts to delete from it. */
    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
        }
        createdInvoiceIds.clear()
    }

    // ─── Fixture helpers (shape follows PostgresDatabaseMaintenanceServiceIT/EInvoiceGenerationE2EIT) ───

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
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1)
            isSelectable = false; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    /** A DRAFT invoice with one real item, no status transition. */
    private fun draftInvoice(tag: String): Long {
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        createdInvoiceIds += invoiceId
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
        return invoiceId
    }

    private fun inReviewInvoice(tag: String): Long {
        val invoiceId = draftInvoice(tag)
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        return invoiceId
    }

    private fun sentInvoice(tag: String): Long {
        val invoiceId = inReviewInvoice(tag)
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return invoiceId
    }

    private fun voidedInvoice(tag: String): Long {
        val invoiceId = draftInvoice(tag)
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.VOIDED) }
        return invoiceId
    }

    private data class CancellationPair(val originalId: Long, val creditNoteId: Long)

    /** An ISSUED original cancellation'd into its own type-code-384 credit note, via the real
     *  [InvoiceCancellationService.createCreditNote] -- neither document carries a job yet. */
    private fun cancelledPairViaCancellation(tag: String): CancellationPair {
        val originalId = sentInvoice(tag)
        val creditNoteId = QuarkusTransaction.requiringNew().call {
            cancellationService.createCreditNote(originalId, "E2E DD storno").id!!
        }
        createdInvoiceIds += creditNoteId
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, em.find(Invoice::class.java, creditNoteId)!!.typeCode)
        }
        return CancellationPair(originalId, creditNoteId)
    }

    private fun jobsOf(invoiceId: Long): List<GenerationJob> =
        QuarkusTransaction.requiringNew().call { generationJobRepository.findByInvoiceId(invoiceId) }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    private fun blankInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("DD E2E base invoice page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Stands in for the worker on ONE already-queued (PENDING) issued job: drives the real Mustang
     * [PdfExportServiceImpl] against the real, S3-backed [documentStore] and the real embedded
     * KoSIT validator (only the render step is stubbed, same allowance
     * [de.littletrotter.e2e.receipts.ReceiptS3EmbeddingE2EIT] uses), then feeds the result into
     * [GenerationJobService.markCompleted] with exactly the `resultRefs` shape
     * [de.littletrotter.xpdf.JobConsumer.publishCompletedResult] builds on the real wire. The real
     * `DocumentGenerated` event this fires drives
     * [de.littletrotter.application.invoice.DocumentGeneratedReactionHandler]'s async reactions
     * (invoice_document rows, checksums) -- callers poll for those via [awaitInvoiceDocuments].
     */
    private fun completeIssuedJob(invoiceId: Long): GenerationJob {
        // GenerationJobService.createJob unconditionally fires an async fast-path publish
        // attempt (GenerationJobFastPublishTransaction.publishOne) a few hundred ms to 3s after the
        // queueing sweep's own transaction commits; against a genuinely reachable NATS broker (a
        // live dev stack on this machine, not this suite's own test resources) that attempt can
        // win the PENDING->DELEGATED claim before this method runs. Either starting point is
        // accepted -- this suite always drives the real export/markCompleted itself from here,
        // never the live broker's own (unrelated) worker.
        val queued = jobsOf(invoiceId).single { it.status == GenerationJobStatus.PENDING || it.status == GenerationJobStatus.DELEGATED }
        val delegated = if (queued.status == GenerationJobStatus.PENDING) {
            QuarkusTransaction.requiringNew().call { generationJobPublishingService.markDelegated(queued) }
        } else {
            queued
        }
        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }

        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(blankInvoicePage())
        val exportService = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = renderer,
            documentStore = documentStore,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = kosit.validate(xml, timeoutMs)
            },
            archiveSigner = archiveSigner,
        )
        val result = exportService.performExport(dto, delegated.id.toString(), llmOptimize = false)

        // Same key set de.littletrotter.xpdf.JobConsumer.publishCompletedResult builds on the wire.
        val resultRefs = buildMap {
            put("objectKey", result.objectKey)
            put("downloadUrl", "http://test-rustfs/dd-e2e-bucket/${result.objectKey}")
            put("zugferdObjectKey", result.zugferdXmlKey)
            if (result.sha256.isNotBlank()) put("sha256", result.sha256)
            result.zugferdXmlSha256?.takeIf { it.isNotBlank() }?.let { put("zugferdXmlSha256", it) }
            result.archiveObjectKey?.takeIf { it.isNotBlank() }?.let { put("archiveObjectKey", it) }
            result.archiveSha256?.takeIf { it.isNotBlank() }?.let { put("archiveSha256", it) }
            result.formatGuideline?.takeIf { it.isNotBlank() }?.let { put("formatGuideline", it) }
        }
        val resultRefsJson = objectMapper.writeValueAsString(resultRefs)

        return QuarkusTransaction.requiringNew().call {
            generationJobResultHandler.markCompleted(
                delegated,
                resultRefs = resultRefsJson,
                kositReport = result.kositReport,
                llmStatus = result.llmStatus,
            )
        }
    }

    /** Polls for [DocumentGeneratedReactionHandler][de.littletrotter.application.invoice
     *  .DocumentGeneratedReactionHandler]'s async `invoice_document` write -- `markCompleted` fires
     *  `DocumentGenerated` via `@ObservesAsync`, so the checksummed rows land some milliseconds
     *  after this test's own thread already saw the job COMPLETED. */
    private fun awaitInvoiceDocuments(invoiceId: Long, expectedCount: Int, timeoutMs: Long = 15_000): List<InvoiceDocument> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: List<InvoiceDocument> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            last = QuarkusTransaction.requiringNew().call { invoiceRepository.findById(invoiceId)?.documentList.orEmpty().sortedByDescending { it.generatedAt } }
            if (last.size >= expectedCount) return last
            Thread.sleep(100)
        }
        return last
    }

    /** The diagonal watermark's own glyphs per page (body text excluded) -- same shear-based
     *  detection [de.littletrotter.e2e.invoice.InvoiceLifecycleWatermarkE2EIT.watermarkTexts] uses,
     *  since the mark's ~45° run
     *  otherwise interleaves with axis-aligned body text under a naive text extraction. */
    private fun watermarkTexts(pdfBytes: ByteArray): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = object : PDFTextStripper() {
                val rotated = StringBuilder()

                override fun processTextPosition(text: TextPosition) {
                    if (kotlin.math.abs(text.textMatrix.shearY) > 0.01f) rotated.append(text.unicode)
                }
            }
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            stripper.rotated.toString().replace(Regex("\\s+"), "")
        }
    }

    /** Crosses the kotlin-invoice `internal` boundary for the view overload of
     *  `buildDownloadAction` -- same reflection idiom [PdfViewerTabE2EIT] already uses for the row
     *  overload (Kotlin's `internal` is a compile-time check only). */
    private fun invokeBuildDownloadAction(job: GenerationJob): Component {
        val view = job.toView()
        val facade = Class.forName("de.littletrotter.ui.view.invoice.GenerationJobFormattingKt")
        val method = facade.declaredMethods.first {
            it.name == "buildDownloadAction" && it.parameterTypes.getOrNull(0) == view.javaClass
        }
        method.isAccessible = true
        val tr: (String) -> String = { key -> key }
        // buildDownloadAction dropped its checksumVerifier parameter entirely (the
        // checksum-verified download path moved to DocumentDownloadResource's own stable route);
        // this reflection call now matches its real 3-argument signature.
        return method.invoke(null, view, documentStore, tr) as Component
    }

    private fun collectAnchors(component: Component): List<Anchor> {
        val found = mutableListOf<Anchor>()
        fun visit(c: Component) {
            if (c is Anchor) found += c
            c.children.forEach(::visit)
        }
        visit(component)
        return found
    }

    // ─── DD-E1 — happy path ───────────────────────────────────────────────────────────────────

    @Test
    fun `DD-E1 - happy path -- ISSUED, CANCELLED original plus its 384 cancellation get real PDF, XML, archive and KoSIT artifacts, DRAFT-IN_REVIEW-VOIDED stay untouched`() {
        val sentId = sentInvoice("DDE1S")
        val pair = cancelledPairViaCancellation("DDE1C")
        val draftId = draftInvoice("DDE1D")
        val inReviewId = inReviewInvoice("DDE1R")
        val voidedId = voidedInvoice("DDE1V")
        val issuedIds = listOf(sentId, pair.originalId, pair.creditNoteId)

        (issuedIds + listOf(draftId, inReviewId, voidedId)).forEach {
            assertTrue(jobsOf(it).isEmpty(), "sanity: invoice #$it must start with no generation job")
        }

        val message = maintenanceService.performQueueIssuedGenerationJobs()
        assertTrue(message.startsWith("Queued "), message)

        issuedIds.forEach { id ->
            val jobs = jobsOf(id)
            assertEquals(1, jobs.size, "invoice #$id must get exactly one queued PRINT_JOB")
            val job = jobs.single()
            assertEquals("PRINT_JOB", job.jobType)
            // The async fast-path publish can already have raced this job to DELEGATED
            // against a genuinely reachable NATS broker by the time this reads back -- see
            // completeIssuedJob's own KDoc. Either is the correct "queued, not yet completed" state.
            assertTrue(
                job.status == GenerationJobStatus.PENDING || job.status == GenerationJobStatus.DELEGATED,
                "invoice #$id's job must be queued (PENDING or DELEGATED), was ${job.status}",
            )
            assertFalse(job.llmOptimize)
            assertTrue(job.issued, "a job queued for an already-issued invoice must be issued=true")
        }
        assertTrue(jobsOf(draftId).isEmpty(), "a DRAFT invoice must never get a job")
        assertTrue(jobsOf(inReviewId).isEmpty(), "an IN_REVIEW invoice must never get a job")
        assertTrue(jobsOf(voidedId).isEmpty(), "a VOIDED invoice must never get a job")

        issuedIds.forEach(::completeIssuedJob)

        issuedIds.forEach { id ->
            val job = jobsOf(id).single()
            assertEquals(GenerationJobStatus.COMPLETED, job.status)

            val refs = GenerationJobResultRefs.decode(job.resultRefs)
            assertNotNull(refs, "result_refs must decode for invoice #$id")
            val objectKey = refs!!.objectKey
            val zugferdObjectKey = refs.zugferdObjectKey
            val archiveObjectKey = refs.archiveObjectKey
            assertTrue(!objectKey.isNullOrBlank(), "objectKey missing for #$id")
            assertTrue(!zugferdObjectKey.isNullOrBlank(), "zugferdObjectKey missing for #$id")
            assertTrue(!archiveObjectKey.isNullOrBlank(), "archiveObjectKey missing for #$id")
            assertTrue(!refs.sha256.isNullOrBlank(), "sha256 missing for #$id")

            val invoiceNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, id)!!.invoiceNumber.value }
            // Per-attempt keys (DocumentKeyRules.deriveJobExportKeys): one base, three extensions.
            assertTrue(DocumentKeyRules.isIssuedJobPdfKey(objectKey!!, invoiceNumber, id), "per-job PDF key expected, got $objectKey")
            assertEquals(objectKey.removeSuffix(".pdf") + ".xml", zugferdObjectKey)
            assertEquals(objectKey.removeSuffix(".pdf") + ".tar.gz", archiveObjectKey)
            assertTrue(documentStore.exists(objectKey!!), "PDF key must exist in the real object store")
            assertTrue(documentStore.exists(zugferdObjectKey!!), "XML key must exist in the real object store")
            assertTrue(documentStore.exists(archiveObjectKey!!), "archive key must exist in the real object store")

            assertTrue(!job.kositReport.isNullOrBlank(), "kosit_report must be non-blank for #$id")
            assertEquals(ValidationOutcome.Successful, resolveValidationOutcome(job.toView()), "a clean domestic invoice must validate Successful")

            val docs = awaitInvoiceDocuments(id, 3)
            assertEquals(3, docs.size, "PDF/XML/ARCHIVE invoice_document rows expected for #$id, got: ${docs.map { it.documentType }}")
            val byType = docs.associateBy { it.documentType }
            val hex64 = Regex("^[0-9a-f]{64}$")
            listOf("PDF", "XML", "ARCHIVE").forEach { type ->
                val doc = byType.getValue(type)
                assertTrue(hex64.matches(doc.sha256Checksum.orEmpty()), "$type checksum must be 64 hex chars, got '${doc.sha256Checksum}'")
                assertEquals(sha256Hex(documentStore.get(doc.objectKey)), doc.sha256Checksum, "$type checksum must match the stored bytes")
            }

            assertEquals(GenerationBlockReason.IMMUTABLE, invoiceDetailQueryPort.generationBlockReason(id), "an issued, completed job must lock generation")
        }

        assertNull(invoiceDetailQueryPort.generationBlockReason(draftId))
        assertNull(invoiceDetailQueryPort.generationBlockReason(inReviewId))
        assertEquals(GenerationBlockReason.VOIDED, invoiceDetailQueryPort.generationBlockReason(voidedId))
        assertTrue(jobsOf(draftId).isEmpty())
        assertTrue(jobsOf(inReviewId).isEmpty())
        assertTrue(jobsOf(voidedId).isEmpty())

        // UI-level: buildDownloadAction renders PDF/XML/archive anchors; the validation report
        // dialog's own availability predicate reports available.
        MockVaadin.setup()
        try {
            issuedIds.forEach { id ->
                val job = jobsOf(id).single()
                val downloadComponent = invokeBuildDownloadAction(job)
                val anchors = collectAnchors(downloadComponent)
                assertEquals(3, anchors.size, "expected PDF+XML+archive anchors for #$id")

                val reportAction = ValidationReportDialog(
                    { it }, invoiceDetailQueryPort, documentStore,
                    time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
                ).buildAction(job.toView())
                assertTrue(reportAction is Button, "the validation report must be available for #$id's completed job")
            }
        } finally {
            MockVaadin.tearDown()
        }
    }

    // ─── DD-E2 — ordering: created_at wins, not type ─────────────────────────────────────────────

    private fun drainPendingMaintenanceJobs() {
        QuarkusTransaction.requiringNew().run {
            while (maintenanceService.claimOldestPendingMaintenanceJob() != null) { /* drain leftovers */ }
        }
    }

    private fun insertPendingRecalculation(createdAt: OffsetDateTime): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val event = DatabaseMaintenanceEvent(
                createdAt = createdAt,
                type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
                status = DatabaseMaintenanceStatus.PENDING,
                details = "DD-E2 queued",
            )
            em.persist(event)
            em.flush()
            event.id!!
        }

    private fun insertPendingQueueIssued(createdAt: OffsetDateTime): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val event = DatabaseMaintenanceEvent(
                createdAt = createdAt,
                type = DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS,
                status = DatabaseMaintenanceStatus.PENDING,
                details = "DD-E2 queued",
            )
            em.persist(event)
            em.flush()
            event.id!!
        }

    @Test
    fun `DD-E2 - ordering -- a RECALCULATE_TOTALS event queued before QUEUE_ISSUED_GENERATION_JOBS is claimed first, reversed queueing is claimed reversed`() {
        drainPendingMaintenanceJobs()
        val recalcFirst = insertPendingRecalculation(OffsetDateTime.now().minusMinutes(5))
        val queueSecond = insertPendingQueueIssued(OffsetDateTime.now())

        val first = maintenanceService.claimOldestPendingMaintenanceJob()
        assertEquals(recalcFirst, first?.eventId, "the older RECALCULATE_TOTALS event must be claimed first")
        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, first?.type)
        val second = maintenanceService.claimOldestPendingMaintenanceJob()
        assertEquals(queueSecond, second?.eventId)
        assertEquals(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, second?.type)
        assertNull(maintenanceService.claimOldestPendingMaintenanceJob())

        drainPendingMaintenanceJobs()
        val queueFirst = insertPendingQueueIssued(OffsetDateTime.now().minusMinutes(5))
        val recalcSecond = insertPendingRecalculation(OffsetDateTime.now())

        val third = maintenanceService.claimOldestPendingMaintenanceJob()
        assertEquals(queueFirst, third?.eventId, "reversed queueing order must be claimed in reversed order -- created_at wins, not type")
        assertEquals(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, third?.type)
        val fourth = maintenanceService.claimOldestPendingMaintenanceJob()
        assertEquals(recalcSecond, fourth?.eventId)
        assertEquals(DatabaseMaintenanceType.RECALCULATE_TOTALS, fourth?.type)
        assertNull(maintenanceService.claimOldestPendingMaintenanceJob())
    }

    // ─── Idempotence / single-generation gate ──────────────────────────────────────────────────────

    @Test
    fun `DD-E3 - idempotence -- a back-to-back run creates nothing, a FAILED-only invoice gets one retry, a PENDING invoice gets none, a P5 restore case is skipped and named`() {
        // Idempotence: nothing changes the eligible set between these two calls within this one
        // test method, so the second one is a genuine no-op regardless of what earlier tests in
        // this shared, never-cleaned database left behind.
        maintenanceService.performQueueIssuedGenerationJobs()
        val secondMessage = maintenanceService.performQueueIssuedGenerationJobs()
        assertTrue(secondMessage.contains("Queued 0 issued-generation job(s)"), "a back-to-back run with nothing new must create 0: $secondMessage")

        val failedId = sentInvoice("DDE3F")
        val failedJob = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(failedId, "PRINT_JOB", DocumentJobLane.BULK) }
        QuarkusTransaction.requiringNew().run { generationJobRecoveryService.markFailed(failedJob, message = "DD-E3 simulated worker crash") }
        assertEquals(1, jobsOf(failedId).size, "sanity: only the FAILED job exists yet")

        val pendingId = sentInvoice("DDE3P")
        QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(pendingId, "PRINT_JOB", DocumentJobLane.BULK) }
        assertEquals(1, jobsOf(pendingId).size, "sanity: one PENDING job exists")

        val restoredId = sentInvoice("DDE3R")
        val restoredInvoiceNumber = QuarkusTransaction.requiringNew().call<String> {
            em.find(Invoice::class.java, restoredId)!!.invoiceNumber.value
        }
        val restoredPdfKey = DocumentKeyRules.deriveJobExportKeys(restoredInvoiceNumber, restoredId, "1", draft = false).pdfObjectKey
        val restoredBytes = "restored-from-backup".toByteArray()
        documentStore.put(restoredPdfKey, restoredBytes, "application/pdf")

        val thirdMessage = maintenanceService.performQueueIssuedGenerationJobs()

        val failedInvoiceJobs = jobsOf(failedId)
        assertEquals(2, failedInvoiceJobs.size, "the FAILED attempt plus exactly one new retry")
        assertTrue(
            failedInvoiceJobs.any { it.status == GenerationJobStatus.PENDING || it.status == GenerationJobStatus.DELEGATED },
            "a fresh retry must have been queued",
        )

        assertEquals(1, jobsOf(pendingId).size, "an already-PENDING job must not be duplicated")

        assertTrue(jobsOf(restoredId).isEmpty(), "P5: the restore case must never get a job -- createJob's own render would overwrite the restored PDF")
        assertTrue(thirdMessage.contains("skipped"), thirdMessage)
        val event = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS)
        assertNotNull(event)
        assertTrue(
            event!!.details?.contains("Invoice #$restoredId skipped -- restore case") == true,
            "the P5 restore case must be named in the event details: ${event.details}",
        )
        assertTrue(documentStore.exists(restoredPdfKey), "the restored PDF itself must be left untouched")
        assertEquals(restoredBytes.toList(), documentStore.get(restoredPdfKey).toList(), "the restored PDF's bytes must be unchanged")
    }

    // ─── DD-E4 — watermark ────────────────────────────────────────────────────────────────────

    @Test
    fun `DD-E4 - watermark -- the ISSUED PDF carries no draft mark, the 384 cancellation PDF carries the CANCELLATION mark`() {
        val sentId = sentInvoice("DDE4S")
        val pair = cancelledPairViaCancellation("DDE4C")

        maintenanceService.performQueueIssuedGenerationJobs()
        completeIssuedJob(sentId)
        completeIssuedJob(pair.originalId)
        completeIssuedJob(pair.creditNoteId)

        val sentPdf = documentStore.get(
            GenerationJobResultRefs.decode(jobsOf(sentId).single().resultRefs)!!.objectKey!!,
        )
        val cancellationPdf = documentStore.get(
            GenerationJobResultRefs.decode(jobsOf(pair.creditNoteId).single().resultRefs)!!.objectKey!!,
        )

        val sentMarks = watermarkTexts(sentPdf)
        assertTrue(sentMarks.isNotEmpty(), "the ISSUED PDF must carry a watermark run")
        assertTrue(
            sentMarks.all { !it.contains("ENTWURF") && !it.contains("DRAFT") },
            "an ISSUED invoice's PDF must never carry the draft mark, got: $sentMarks",
        )
        assertTrue(sentMarks.any { it.contains("LittleTrotter") }, "an ISSUED invoice's PDF carries the ordinary brand mark, got: $sentMarks")

        val cancellationMarks = watermarkTexts(cancellationPdf)
        assertTrue(cancellationMarks.isNotEmpty(), "the 384 storno PDF must carry a watermark run")
        assertTrue(
            cancellationMarks.all { it.contains("STORNO") },
            "the 384 storno document's PDF must carry the STORNO mark on every page, got: $cancellationMarks",
        )
    }
}
