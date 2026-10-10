package de.littletrotter.system

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.value.TaxRatePercent

import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobEventDispatcher
import de.littletrotter.application.invoice.GenerationJobLlmResultHandler
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.NoOpLlmAnalysisRepository
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.test.CombinedTestContainersExtension
import de.littletrotter.test.NatsTestContainersExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.enterprise.event.Event
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * System-level integration test verifying the full workflow across modules.
 * - Invoices finalized in kotlin-invoice
 * - Jobs published to NATS
 * - Worker (simulated via local logic) processes and publishes result
 * - kotlin-invoice updates job status
 */
@ExtendWith(CombinedTestContainersExtension::class)
class SystemWorkflowIT {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `full workflow from invoice finalization to job completion`() {
        val connection = NatsTestContainersExtension.connection
        val topicSuffix = UUID.randomUUID().toString()
        val topicJobs = "document-jobs-$topicSuffix"
        val topicResults = "document-job-results-$topicSuffix"

        // --- SETUP INVOICE APP COMPONENTS ---
        val idSequence = AtomicLong(System.currentTimeMillis())
        val jobRepo = object : de.littletrotter.domain.repository.GenerationJobRepository {
            val jobs = mutableMapOf<Long, GenerationJob>()
            override fun findByInvoiceId(invoiceId: Long) = jobs.values.filter { it.invoice?.id == invoiceId }
            override fun findByInvoiceIdWithTaxRates(invoiceId: Long) = findByInvoiceId(invoiceId)
            override fun findPending() = jobs.values.filter { it.status == GenerationJobStatus.PENDING }
            override fun findPendingWithInvoice() = findPending()
            override fun findById(id: Long) = jobs[id]
            override fun save(job: GenerationJob): GenerationJob {
                if (job.id == null) job.id = idSequence.incrementAndGet()
                jobs[job.id!!] = job
                return job
            }
            override fun tryMarkDelegated(id: Long): Boolean {
                val job = jobs[id] ?: return false
                if (job.status != GenerationJobStatus.PENDING) return false
                job.status = GenerationJobStatus.DELEGATED
                return true
            }
        }
        // InvoiceRepository stub used during createJob validation — stores invoices by id
        val invoiceStore = mutableMapOf<Long, Invoice>()
        val invoiceRepo = object : de.littletrotter.domain.repository.InvoiceRepository {
            override fun findById(id: Long) = invoiceStore[id]
            override fun findByIdWithItems(id: Long) = invoiceStore[id]
            override fun findByInvoiceNumber(n: String) = invoiceStore.values.find { it.invoiceNumber.value == n }
            override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
            override fun findAll(p: Int, s: Int) = invoiceStore.values.toList()
            override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: de.littletrotter.domain.invoice.InvoiceStatus?) = emptyList<Invoice>()
            override fun countForOverview(filterText: String?, status: de.littletrotter.domain.invoice.InvoiceStatus?) = 0L
            override fun findAllForRecalculation() = emptyList<Invoice>()
            override fun count() = invoiceStore.size.toLong()
            override fun countByStatus(st: de.littletrotter.domain.invoice.InvoiceStatus) = 0L
            override fun findByRecipientId(id: Long, p: Int, s: Int) = emptyList<Invoice>()
            override fun findBySenderProfileId(id: Long, p: Int, s: Int) = emptyList<Invoice>()
            override fun existsByBillingAddressId(id: Long) = false
            override fun existsByDeliveryAddressId(id: Long) = false
            override fun findInvoiceByItemId(itemId: Long): de.littletrotter.domain.invoice.Invoice? = null
            override fun save(i: Invoice) = i.also { invoiceStore[it.id!!] = it }
            override fun forceVersionIncrement(invoice: Invoice) { invoice.version = (invoice.version ?: 0L) + 1L }
        }
        // GenerationJobService dissolved: wires the real collaborators it used to build
        // internally, all sharing jobRepo/invoiceRepo/the same event dispatcher.
        val jobEventDispatcher = GenerationJobEventDispatcher(mock<Event<DomainEvent>>(), null)
        val time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))
        // The document-size budget guard added to createJob: this workflow's synthetic invoices
        // never carry receipts, so a mock that always reports an empty (Accepted) budget is enough
        // — a real ReceiptBudgetService would need a ReceiptRepository this test has no use for.
        val receiptBudgetService = mock<ReceiptBudgetService>().also {
            whenever(it.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
        }
        val jobService = GenerationJobCreationService(jobRepo, invoiceRepo, mock<Event<DomainEvent>>(), time, receiptBudgetService)
        val jobPublishingService = GenerationJobPublishingService(jobRepo, time)
        val jobQueryService = GenerationJobQueryService(jobRepo)
        val jobResultHandler = GenerationJobResultHandler(jobRepo, jobEventDispatcher, time = FIXED_TIME_SOURCE)
        val jobLlmResultHandler = GenerationJobLlmResultHandler(jobRepo, NoOpLlmAnalysisRepository, invoiceRepo, jobEventDispatcher, time)
        val jobRecoveryService = GenerationJobRecoveryService(jobRepo, invoiceRepo, jobEventDispatcher, jobLlmResultHandler, time)

        val publisher = DocumentJobPublisher(
            jetStreamProvider = { CombinedTestContainersExtension.natsJetStream() },
            jobsSubjectOverride = topicJobs,
        )
        val resultConsumer = GenerationJobResultConsumer(jobQueryService, jobResultHandler, jobLlmResultHandler, jobRecoveryService)

        // --- SIMULATE WORKER (XPDF) LOGIC ---
        // Instead of using XPDF classes directly, we mirror the logic here for the system test
        val latchInvoiceReceived = CountDownLatch(1)
        val workerReady = CountDownLatch(1)
        val resultConsumerReady = CountDownLatch(1)

        // Simulating the Worker Loop
        thread(name = "worker-sim-loop") {
            connection.createDispatcher { msg ->
                val jobMap: Map<String, Any> = mapper.readValue(String(msg.data, Charsets.UTF_8))
                val jobId = jobMap["id"] as String
                val correlationId = jobMap["correlationId"] as? String

                // Mock "Work" (PDF Export)
                val resultRefs = mapOf("pdf" to "s3://bucket/invoices/$jobId.pdf")
                val resultPayload = mapOf(
                    "id" to jobId,
                    "status" to "COMPLETED",
                    "resultRefs" to resultRefs,
                    "correlationId" to correlationId
                )
                connection.publish(topicResults, mapper.writeValueAsString(resultPayload).toByteArray(Charsets.UTF_8))
            }.subscribe(topicJobs)
            workerReady.countDown()
        }

        // Simulating the Invoice App Result Consumer Loop
        thread(name = "invoice-app-loop") {
            connection.createDispatcher { msg ->
                resultConsumer.consume(String(msg.data, Charsets.UTF_8))
                latchInvoiceReceived.countDown()
            }.subscribe(topicResults)
            resultConsumerReady.countDown()
        }

        // --- ACT ---
        assertTrue(workerReady.await(5, TimeUnit.SECONDS), "Worker consumer was not ready in time")
        assertTrue(resultConsumerReady.await(5, TimeUnit.SECONDS), "Result consumer was not ready in time")

        val testItem = de.littletrotter.domain.invoice.InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = java.math.BigDecimal("1.00")
            unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("100.00"))
            netAmount = de.littletrotter.shared.Money(java.math.BigDecimal("100.00"))
            vatAmount = de.littletrotter.shared.Money(java.math.BigDecimal("19.00"))
            grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("119.00"))
            taxRateRef = de.littletrotter.domain.reference.TaxRate().apply { rate = TaxRatePercent(java.math.BigDecimal("19.00")) }
        }
        val testInvoice = Invoice().apply {
            id = 123L
            invoiceNumber = InvoiceNumber("INV-2024-0001")
            senderProfile = de.littletrotter.domain.masterdata.SenderProfile().apply {
                name = "Sender"; vatId = VatId("DE123456789")
                email = de.littletrotter.domain.value.Email.ofNullable("billing@sender.example")
                taxNumber = TaxNumber("12/345/67890")
                iban = de.littletrotter.domain.value.Iban.ofNullable("DE89370400440532013000")
            }
            recipient = de.littletrotter.domain.masterdata.Recipient().apply {
                name = "Recipient"; customerNumber = CustomerNumber("KD-001")
                email = de.littletrotter.domain.value.Email.ofNullable("ap@recipient.example")
                customerType = de.littletrotter.shared.tax.CustomerType.CONSUMER
            }
            totals.netAmount = de.littletrotter.shared.Money(java.math.BigDecimal("100.00"))
            totals.vatAmount = de.littletrotter.shared.Money(java.math.BigDecimal("19.00"))
            totals.grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("119.00"))
            itemList.asMutableForTest().add(testItem.also { it.invoice = this })
        }
        invoiceStore[testInvoice.id!!] = testInvoice

        // 1. Create Job
        val job = jobService.createJob(testInvoice, "PDF_EXPORT", DocumentJobLane.BULK)

        // A result can only legally complete a DELEGATED/RUNNING job; claim the
        // PENDING->DELEGATED transition explicitly here, exactly like GenerationJobProcessor does
        // in production before it ever calls DocumentJobPublisher.publish.
        jobPublishingService.tryMarkDelegated(job)

        // 2. Publish (normally done by EventHandler)
        publisher.publish(testInvoice, job.id!!).get(5, TimeUnit.SECONDS)

        // --- ASSERT ---
        val success = latchInvoiceReceived.await(20, TimeUnit.SECONDS)
        assertTrue(success, "Workflow did not complete in time")

        val updatedJob = jobRepo.findById(job.id!!)
        assertEquals(GenerationJobStatus.COMPLETED, updatedJob?.status, "Job should be COMPLETED")
        assertTrue(updatedJob?.resultRefs?.contains("pdf") == true, "ResultRefs should contain pdf link")
    }
}
