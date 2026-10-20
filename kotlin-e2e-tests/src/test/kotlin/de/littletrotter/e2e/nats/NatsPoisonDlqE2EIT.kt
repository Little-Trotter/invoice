package de.littletrotter.e2e.nats

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.PdfExportPort
import io.nats.client.Message
import io.nats.client.impl.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Duration

/**
 * Poison -> DLQ: a message `JobConsumer` cannot process at all (unparseable
 * JSON, or here an unmappable invoice payload — the two poison kinds `NATS.md` itself names) lands
 * on `LITTLE_TROTTER_DLQ` alongside a FAILED result, is never redelivered, and never reaches
 * [PdfExportPort] at all.
 *
 * A retry-budget-exhausted path can also look like a DLQ trigger, since "processing fails past
 * the retry budget" reads that way. The real
 * `JobConsumer` does not implement that path today — `publishRetryExhaustedFailure` (reached once
 * `job.retry.count` is exhausted on a genuine export failure) only ever publishes a FAILED result
 * on the normal `document-job-results` subject and acks; it never calls `publishRawToDlq`. Only
 * the two poison-message branches (`handleUnparseableMessage`/`handleUnmappableInvoice`) ever
 * write to `LITTLE_TROTTER_DLQ`. Reproducing "retry-budget-exhausted -> DLQ" as literally described
 * would require changing `JobConsumer` itself, which is treated here as a lock-zone class — left
 * for separate follow-up rather than forced into this test. This test covers the real,
 * existing DLQ path.
 *
 * `GenerationJobResultConsumer`'s
 * "FAILED" branch used to never forward the worker's own specific failure reason into the
 * app-side `resultRefs.error` — it hardcoded a generic `"external worker reported failure"`
 * string regardless of what the worker actually reported. That gap was closed
 * (`GenerationJobResultConsumer` now reads the worker's real `resultRefs.error` text
 * through the typed `GenerationJobResultRefs.error` field); the assertion below now pins the
 * fixed behavior instead of the old constant.
 */
@ExtendWith(NatsE2EExtension::class)
class NatsPoisonDlqE2EIT {

    private val mapper = jacksonObjectMapper()
    private val workerDurable = "e2e-poison-worker"
    private val appDurable = "e2e-poison-app"

    @AfterEach
    fun cleanup() {
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, workerDurable)
        NatsE2ESupport.deleteConsumer(NatsStreamSetup.STREAM_NAME, appDurable)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.JOBS_SUBJECT)
        NatsE2ESupport.purgeSubject(NatsE2EExtension.RESULTS_SUBJECT)
    }

    @Test
    fun `an unmappable invoice payload is DLQ'd, FAILED once on the results subject, and never redelivered`() {
        val jobId = 900_004L
        val repo = RecordingGenerationJobRepository(
            aGenerationJob { id = jobId; status = GenerationJobStatus.DELEGATED },
        )
        val resultConsumer = NatsE2ESupport.newAppResultConsumer(repo)
        val appHandle = NatsE2ESupport.subscribeAppAdapter(resultConsumer, appDurable)

        val pdfExportPort: PdfExportPort = mock()
        val workerConsumer = NatsE2ESupport.newWorkerConsumer(pdfExportPort)
        val workerHandle = NatsE2ESupport.subscribeWorker(workerConsumer, workerDurable)

        try {
            // Valid JSON, valid DocumentJobRequest shape, but no "payload" (hence no invoice) at all -
            // exactly the "unmappable invoice" poison kind NATS.md names, and JobConsumerTest's
            // own (mock-based) unit-test precedent for it (JobConsumer.handle: a present-but-empty
            // "payload":{} would NOT trigger this - it falls back to treating the payload object
            // itself as the invoice and happily maps an all-null InvoiceDto from it).
            val poisonJson = """{"id":"$jobId","type":"PDF_EXPORT","correlationId":"corr-poison"}"""
            NatsE2EExtension.jetStream().publish(
                NatsE2EExtension.JOBS_SUBJECT,
                Headers().add("jobId", jobId.toString()),
                poisonJson.toByteArray(Charsets.UTF_8),
            )

            val dlqMessage = pollDlq(timeoutSeconds = 20) { it.headers?.getFirst("jobId") == jobId.toString() }
            assertNotNull(dlqMessage, "the poison message's raw bytes must land on $NATS_DLQ_SUBJECT")
            assertEquals(poisonJson, String(dlqMessage!!.data, Charsets.UTF_8), "the DLQ copy must carry the exact original bytes")
            assertTrue(
                dlqMessage.headers?.getFirst("reason")?.contains("unmappable job payload") == true,
                "the DLQ headers must carry a meaningful reason",
            )
            assertEquals(NatsE2EExtension.JOBS_SUBJECT, dlqMessage.headers?.getFirst("origSubject"))

            // GenerationJobService.markFailed writes status BEFORE resultRefs, and the recording
            // double hands out the same shared instance — polling on status alone can catch the
            // job mid-mutation (FAILED set, refs not yet). save() runs after every field write,
            // so gating on saveCount (an AtomicInteger, giving the happens-before edge the plain
            // entity fields lack) makes the subsequent resultRefs read race-free. In production
            // this window doesn't exist: the JPA transaction commits both columns atomically.
            val failed = NatsE2ESupport.waitUntil(20) {
                repo.saveCount.get() > 0 && repo.findById(jobId)?.status == GenerationJobStatus.FAILED
            }
            assertTrue(failed, "the app-side job must be marked FAILED from the worker's poison-job FAILED result")

            val saved = repo.findById(jobId)
            assertNotNull(saved?.resultRefs, "a FAILED job must carry a resultRefs error explanation")
            val resultRefs: Map<String, Any?> = mapper.readValue(saved!!.resultRefs!!)
            // GenerationJobResultConsumer's "FAILED" branch now forwards the worker's own
            // specific reason into the app-side resultRefs.error, the same text the DLQ headers
            // above already carry — no longer the generic "external worker reported failure"
            // constant.
            assertTrue(
                resultRefs["error"]?.toString()?.contains("unmappable job payload") == true,
                "the app-side resultRefs.error must carry the worker's specific reason, not a generic constant: ${resultRefs["error"]}",
            )

            verify(pdfExportPort, never()).performExport(any(), any(), any(), any(), any(), anyOrNull())

            // No infinite redelivery loop: a poison message is always ack'd once, never nak'd.
            Thread.sleep(2_000)
            val info = NatsE2EExtension.jetStreamManagement().getConsumerInfo(NatsStreamSetup.STREAM_NAME, workerDurable)
            assertEquals(0L, info.redelivered, "a poison message must be ack'd once, never redelivered")
            assertEquals(0L, info.numAckPending, "nothing should remain pending after the single poison delivery is ack'd")
        } finally {
            NatsE2ESupport.closeConsumer(workerHandle)
            NatsE2ESupport.closeConsumer(appHandle)
        }
    }

    private val NATS_DLQ_SUBJECT get() = NatsE2EExtension.JOBS_DLQ_SUBJECT

    /** Reads from the real `LITTLE_TROTTER_DLQ` stream (Limits retention - safe to read
     *  concurrently, unlike the main WorkQueue stream) via a plain ephemeral JetStream
     *  subscription, matching `DocumentJobPublisherIT.pollMessage`'s own established pattern. */
    private fun pollDlq(timeoutSeconds: Long, matching: (Message) -> Boolean): Message? {
        val sub = NatsE2EExtension.jetStream().subscribe(NATS_DLQ_SUBJECT)
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val msg = sub.nextMessage(Duration.ofMillis(300)) ?: continue
                msg.ack()
                if (matching(msg)) return msg
            }
        } finally {
            sub.unsubscribe()
        }
        return null
    }
}
