package de.littletrotter.e2e.mail

import de.littletrotter.application.mail.InvoiceMailComposeTransactions
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.InvoiceMailPreparation
import de.littletrotter.application.mail.MailComposeResult
import de.littletrotter.application.mail.port.inbound.InvoiceMailReadinessQueryPort
import de.littletrotter.application.mail.port.inbound.MailDocumentReadiness
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.MailModuleTestProfile
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.xpdf.infrastructure.messaging.JobConsumer
import de.littletrotter.xpdf.infrastructure.messaging.JobDispatcher
import de.littletrotter.xpdf.infrastructure.messaging.JobLane
import de.littletrotter.xpdf.infrastructure.messaging.JobResultReporter
import de.littletrotter.xpdf.infrastructure.messaging.PoisonMessageHandler
import de.littletrotter.xpdf.infrastructure.messaging.QuoteJobProcessor
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import de.littletrotter.shared.Money
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.MessageConsumer
import io.nats.client.Nats
import io.nats.client.PurgeOptions
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Bulk load on the document pipeline: [BULK_DEFAULT] issued invoices (configurable through
 * `e2e.bulk.invoices`) have a queued generation job that has not completed, plus one interactive
 * invoice. While its job is open the mail handover reports `DocumentPending` instead of failing
 * and the readiness query says `PENDING`; once the job's result is processed the handover
 * preparation finds the checksummed document and the button's readiness turns `READY`.
 *
 * The last step stops at the prepared draft with presigned attachments: whether the mail module
 * can reach the object store from its own container is a deployment seam covered by the
 * recovery scenario. Synthetic data only.
 */
@QuarkusTest
@TestProfile(MailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class InvoiceMailDocumentPendingE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var composeTransactions: InvoiceMailComposeTransactions

    @Inject
    lateinit var dispatchService: InvoiceMailDispatchService

    @Inject
    lateinit var readiness: InvoiceMailReadinessQueryPort

    @Test
    fun `under bulk load the interactive invoice reports pending until its document completes`() {
        val bulkCount = ConfigProvider.getConfig().getOptionalValue(BULK_PROPERTY, String::class.java).orElse(null)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: BULK_DEFAULT
        val master = persistMasterData()
        val bulkJobs = (1..bulkCount).map { index -> queueJob(persistInvoice(master, "BULK-2026-01-%05d".format(index)).first) }
        val (interactiveId, interactiveNumber) = persistInvoice(master, "INTER-2026-01-00001")
        val interactiveJob = queueJob(interactiveId)

        assertEquals(bulkCount, bulkJobs.size)
        assertEquals(MailDocumentReadiness.PENDING, readiness.readiness(interactiveId))
        assertEquals(InvoiceMailPreparation.DocumentPending, composeTransactions.prepare(interactiveId))
        assertEquals(MailComposeResult.DocumentPending, dispatchService.compose(interactiveId))

        completeJob(interactiveId, interactiveNumber, interactiveJob)

        assertEquals(MailDocumentReadiness.READY, readiness.readiness(interactiveId))
        val prepared = composeTransactions.prepare(interactiveId)
        assertTrue(prepared is InvoiceMailPreparation.Ready, "prepared: $prepared")
        assertEquals(1, (prepared as InvoiceMailPreparation.Ready).draft.attachments.size)
        // A neighbour in the bulk queue is unaffected by the interactive completion.
        assertEquals(MailComposeResult.DocumentPending, dispatchService.compose(bulkJobs.first().invoiceId))
    }

    /**
     * The 500-bulk-jobs acceptance: the bulk lane is loaded with [BULK_DEFAULT] jobs (configurable)
     * that a deliberately slow bulk worker works off one at a time, then one interactive job is
     * requested. Its document must be READY within [INTERACTIVE_BUDGET_SECONDS] seconds of the
     * request, while most of the bulk jobs are still open. Both lanes are served by the real worker
     * consumer on their own durable consumer; only the PDF export is replaced by a stub that
     * stores a checksummed document.
     */
    @Test
    fun `an interactive document is ready within the budget while the bulk lane is loaded`() {
        val bulkCount = ConfigProvider.getConfig().getOptionalValue(BULK_PROPERTY, String::class.java).orElse(null)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: BULK_DEFAULT
        val natsUrl = ConfigProvider.getConfig().getValue("nats.url", String::class.java)
        val master = persistMasterData()
        Nats.connect(natsUrl).use { connection ->
            val jsm = connection.jetStreamManagement()
            jsm.purgeStream(NatsStreamSetup.STREAM_NAME, PurgeOptions.subject(DocumentJobSubjects.JOBS))
            jsm.purgeStream(NatsStreamSetup.STREAM_NAME, PurgeOptions.subject(DocumentJobSubjects.INTERACTIVE_JOBS))

            val bulkIds = (1..bulkCount).map { index ->
                queueBulkJob(persistInvoice(master, "LANE-2026-01-%05d".format(index)).first)
            }
            val interactiveInvoice = persistInvoice(master, "LANEI-2026-01-00001")
            assertEquals(bulkCount.toLong(), jobsInLane(bulkIds, DocumentJobLane.BULK), "the load must really be bulk-lane jobs")

            val consumers = listOf(
                startWorker(connection, JobLane("bulk", DocumentJobSubjects.JOBS, "e2e-lane-bulk", 1), BULK_RENDER_MILLIS),
                startWorker(connection, JobLane.interactive(durableName = "e2e-lane-interactive"), INTERACTIVE_RENDER_MILLIS),
            )
            try {
                val startedAt = System.nanoTime()
                val interactiveJob = QuarkusTransaction.requiringNew().call<GenerationJob> {
                    generationJobCreationService.createJob(interactiveInvoice.first, "PRINT_JOB", DocumentJobLane.INTERACTIVE)
                }
                assertEquals(DocumentJobLane.INTERACTIVE, interactiveJob.lane)

                var state = readiness.readiness(interactiveInvoice.first)
                while (state != MailDocumentReadiness.READY &&
                    System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(INTERACTIVE_BUDGET_SECONDS)
                ) {
                    Thread.sleep(POLL_MILLIS)
                    state = readiness.readiness(interactiveInvoice.first)
                }
                val latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                println("interactive document latency under $bulkCount bulk jobs: $latencyMillis ms")
                assertEquals(MailDocumentReadiness.READY, state, "interactive document not READY after $latencyMillis ms")
                assertTrue(latencyMillis <= INTERACTIVE_BUDGET_SECONDS * 1000, "latency $latencyMillis ms")

                val stillOpen = bulkCount - jobsWithStatus(bulkIds, GenerationJobStatus.COMPLETED)
                println("bulk jobs not completed when the interactive document was ready: $stillOpen of $bulkCount")
                assertTrue(stillOpen * 2 >= bulkCount, "the bulk queue must still be loaded: $stillOpen of $bulkCount open")
            } finally {
                consumers.forEach { it.close() }
                jsm.deleteConsumer(NatsStreamSetup.STREAM_NAME, "e2e-lane-bulk")
                jsm.deleteConsumer(NatsStreamSetup.STREAM_NAME, "e2e-lane-interactive")
                jsm.purgeStream(NatsStreamSetup.STREAM_NAME, PurgeOptions.subject(DocumentJobSubjects.JOBS))
            }
        }
    }

    private fun queueBulkJob(invoiceId: Long): Long = QuarkusTransaction.requiringNew().call<Long> {
        requireNotNull(generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK).id)
    }

    private fun jobsInLane(ids: List<Long>, lane: DocumentJobLane): Long = QuarkusTransaction.requiringNew().call<Long> {
        em.createQuery("select count(j) from GenerationJob j where j.id in :ids and j.lane = :lane", Long::class.javaObjectType)
            .setParameter("ids", ids).setParameter("lane", lane).singleResult.toLong()
    }

    private fun jobsWithStatus(ids: List<Long>, status: GenerationJobStatus): Long = QuarkusTransaction.requiringNew().call<Long> {
        em.createQuery("select count(j) from GenerationJob j where j.id in :ids and j.status = :status", Long::class.javaObjectType)
            .setParameter("ids", ids).setParameter("status", status).singleResult.toLong()
    }

    /** The real worker consumer on its own durable consumer and a single render thread, so a lane's
     *  throughput is exactly one document per [renderMillis]. */
    private fun startWorker(connection: Connection, lane: JobLane, renderMillis: Long): MessageConsumer {
        val jetStream = connection.jetStream()
        val resultPublisher = ResultPublisher(jetStream)
        val consumer = JobConsumer(
            connection,
            jetStream,
            PoisonMessageHandler(resultPublisher, jetStream, DocumentJobSubjects.JOBS_DLQ),
            JobDispatcher(storingExportPort(renderMillis), mock<PayloadExchange>(), JobResultReporter(resultPublisher)),
            QuoteJobProcessor(mock(), resultPublisher),
            lane,
        )
        NatsStreamSetup.ensureConsumer(
            connection.jetStreamManagement(),
            NatsStreamSetup.STREAM_NAME,
            NatsStreamSetup.consumerConfiguration(lane.durableName, lane.subject, maxAckPending = 2 * lane.workerThreads, ackWaitSeconds = 120),
        )
        return jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME).getConsumerContext(lane.durableName)
            .consume(ConsumeOptions.builder().batchSize(2 * lane.workerThreads).expiresIn(2_000).build()) { consumer.receive(it) }
    }

    private fun storingExportPort(renderMillis: Long) = object : PdfExportPort {
        override fun performExport(
            invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
        ): ExportResult {
            Thread.sleep(renderMillis)
            val keys = DocumentKeyRules.deriveJobExportKeys(invoice.invoiceNumber.value, invoice.id, jobId, draft = false)
            val pdf = "%PDF-1.4 synthetic lane $jobId".toByteArray()
            documentStore.put(keys.pdfObjectKey, pdf, "application/pdf")
            documentStore.put(keys.xmlObjectKey, "<invoice/>".toByteArray(), "application/xml")
            return ExportResult(keys.pdfObjectKey, keys.xmlObjectKey, true, "kosit-ok", sha256Hex(pdf))
        }

        override fun performFacturXExport(
            invoice: InvoiceDto, jobId: String, llmOptimize: Boolean, skipValidation: Boolean, veraPdfCheck: Boolean, outputIntent: String?,
        ): FacturXExportOutcome = throw UnsupportedOperationException("Mustang-only stub")
    }

    private class QueuedJob(val jobId: Long, val invoiceId: Long)

    private class MasterData(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun persistMasterData(): MasterData = QuarkusTransaction.requiringNew().call<MasterData> {
        val tag = UUID.randomUUID().toString().take(8)
        val sender = SenderProfile().apply {
            name = "Pending Muster GmbH"
            email = Email("billing@pending.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "PD$tag-"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-$tag")
            name = "Pending Kunde AG"
            email = Email("kunde@pending.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "E2E_PENDING_$tag"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        em.flush()
        MasterData(requireNotNull(sender.id), requireNotNull(recipient.id), requireNotNull(taxRate.id))
    }

    private fun persistInvoice(master: MasterData, number: String): Pair<Long, String> =
        QuarkusTransaction.requiringNew().call<Pair<Long, String>> {
            val taxRate = requireNotNull(em.find(TaxRate::class.java, master.taxRateId))
            val invoice = Invoice.withInitialStatus(InvoiceStatus.SENT).apply {
                invoiceNumber = InvoiceNumber(number)
                invoiceDate = LocalDate.now()
                senderProfile = em.find(SenderProfile::class.java, master.senderId)
                recipient = em.find(Recipient::class.java, master.recipientId)
                totals.netAmount = Money(BigDecimal("100.00"))
                totals.vatAmount = Money(BigDecimal("19.00"))
                totals.grossAmount = Money(BigDecimal("119.00"))
            }
            invoice.addItem(
                InvoiceItem().apply {
                    positionId = "1"
                    description = "Beratung"
                    quantity = BigDecimal("1.00")
                    unitPriceNet = Money(BigDecimal("100.00"))
                    netAmount = Money(BigDecimal("100.00"))
                    vatAmount = Money(BigDecimal("19.00"))
                    grossAmount = Money(BigDecimal("119.00"))
                    taxRateRef = taxRate
                },
            )
            em.persist(invoice)
            em.flush()
            requireNotNull(invoice.id) to number
        }

    /** The job is created and delegated to the worker, but its result has not been processed. */
    private fun queueJob(invoiceId: Long): QueuedJob {
        val invoice = QuarkusTransaction.requiringNew().call<Invoice> { requireNotNull(em.find(Invoice::class.java, invoiceId)) }
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)
        return QueuedJob(requireNotNull(job.id), invoiceId)
    }

    private fun completeJob(invoiceId: Long, number: String, queued: QueuedJob) {
        val job = QuarkusTransaction.requiringNew().call<GenerationJob> { requireNotNull(em.find(GenerationJob::class.java, queued.jobId)) }
        val bytes = "%PDF-1.4 synthetic pending ${UUID.randomUUID()}".toByteArray()
        val key = DocumentKeyRules.deriveJobExportKeys(number, invoiceId, queued.jobId.toString(), draft = false).pdfObjectKey
        documentStore.put(key, bytes, "application/pdf")
        generationJobResultHandler.markCompleted(job, """{"objectKey":"$key","sha256":"${sha256Hex(bytes)}"}""")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BULK_PROPERTY = "e2e.bulk.invoices"
        const val BULK_DEFAULT = 500
        const val INTERACTIVE_BUDGET_SECONDS = 10L
        const val BULK_RENDER_MILLIS = 200L
        const val INTERACTIVE_RENDER_MILLIS = 50L
        const val POLL_MILLIS = 100L
    }
}
