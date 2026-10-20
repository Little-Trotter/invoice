package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.KositReportRefDto
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.documentjob.DocumentJobResult
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.slf4j.LoggerFactory

/**
 * NATS JetStream producer for publishing job results back to the main application.
 *
 * This class used to open its own,
 * second NATS connection in a no-arg constructor and never closed it. It now shares the single
 * connection/`JetStream` context the whole worker uses, produced once by
 * [NatsConnectionProducer]; there is exactly one NATS connection per `kotlin-xpdf` instance.
 */
@ApplicationScoped
class ResultPublisher @Inject constructor(
    private val jetStream: JetStream,
    // `@param:` pins this to the constructor parameter only; without it, Kotlin 2.4's
    // default annotation-use-site-target policy also copies `@ConfigProperty` onto the backing
    // (final) field, which Qodana's CDI inspector then misreads as an illegal final-field
    // injection point even though CDI only ever reads the parameter here (no `@Inject` on the
    // field itself — CDI only ever reads the parameter).
    @param:ConfigProperty(name = "nats.subject.document-job-results", defaultValue = DocumentJobSubjects.RESULTS)
    private val resultsSubject: String,
    /** Nullable so the test-only constructors below stay usable without wiring a real
     *  Valkey; a `null` exchange means a non-blank [publishResult] `kositReport` is simply never
     *  stored, so the ref it still builds resolves to nothing on the reading side — the same
     *  "ref present but key missing" state a genuine Valkey outage produces, and the documented
     *  failure path (WARN log, "report unavailable" marker, never a processing failure) already
     *  covers it. */
    private val payloadExchange: PayloadExchange?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Test-only convenience constructor (kept intentionally — see AP-M1 §1, "the test constructor
     * taking a `JetStream` stays"): bakes in the same default the `@ConfigProperty` above resolves
     * to when the property is unset, so unit tests that don't care about a custom subject don't
     * have to pass one. Same pattern as [de.littletrotter.xpdf.impl.S3DocumentStore]'s own
     * test-only secondary constructor.
     */
    constructor(jetStream: JetStream) : this(jetStream, DocumentJobSubjects.RESULTS, null)

    /** Test-only — same reasoning as the single-arg constructor above, for tests that also want
     *  a custom results subject without wiring Valkey. */
    constructor(jetStream: JetStream, resultsSubject: String) : this(jetStream, resultsSubject, null)

    /** Test-only — for tests that want a real/fake [PayloadExchange] wired with the default
     *  subject (distinct overload from the one above: unambiguous by argument type). */
    constructor(jetStream: JetStream, payloadExchange: PayloadExchange) :
            this(jetStream, DocumentJobSubjects.RESULTS, payloadExchange)

    /**
     * Exponential backoff schedule for [publishResult]'s retry loop (AP-M1 §2: "250 ms/1 s/4 s").
     * Only the first two entries are ever slept — [MAX_ATTEMPTS] caps the loop at 3 total tries,
     * which has room for exactly two backoffs between them. The third entry documents the next
     * step of the series (x4 each time) for whoever tunes [MAX_ATTEMPTS] later; it is data, not
     * dead code — nothing branches on whether it gets read.
     */
    private val backoffScheduleMs = longArrayOf(250L, 1_000L, 4_000L)

    /**
     * Publishes the job result synchronously with up to [MAX_ATTEMPTS] attempts, backing off
     * between them per [backoffScheduleMs]. Returns the broker's [PublishAck] on success; on
     * final failure it throws so the caller — [JobConsumer] — decides what to do (nak the job
     * message so JetStream redelivers, since NATS being unreachable means nobody could have
     * received the result anyway).
     */
    fun publishResult(
        jobId: String,
        status: String,
        resultRefs: Map<String, String>?,
        kositReport: String = "",
        correlationId: String?,
        // The job's own result subject; absent means this publisher's configured subject, which is
        // what every producer without the field has always received its results on.
        resultSubject: String? = null,
    ): PublishAck {
        // AP-M6 — the report never travels inline: a non-blank report is SETEX'd into Valkey
        // under a per-job key and only the {key} reference is sent (KositReportRefDto). A blank
        // report (no KoSIT run, or KoSIT not configured — every existing call site that passes
        // "") stays exactly as light as before: no Valkey round-trip, no ref on the wire at all.
        val kositReportRef = kositReportRef(jobId, kositReport)
        val json = DocumentJobWireCodec.encodeResult(
            DocumentJobResult(jobId, status, resultRefs, kositReportRef, correlationId),
        )
        val headers = Headers().add("jobId", jobId)
        if (correlationId != null) headers.add("correlationId", correlationId)
        val body = json.toByteArray(Charsets.UTF_8)
        // AP-M2 — a stable Nats-Msg-Id per
        // job+status so a redelivered-and-reprocessed job (heartbeat lapse, or the worker
        // completing an already-nak'd job a second time) cannot double-apply the same verdict
        // within the broker's dedup window. Belt-and-braces on top of the already-idempotent
        // `markCompleted` on the reader side (kotlin-invoice), not a replacement for it: the
        // dedup window is short-lived and only protects the *publish*, not every possible replay.
        val publishOptions = PublishOptions.builder().messageId("result-$jobId-$status").build()

        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return jetStream.publish(resultSubject ?: resultsSubject, headers, body, publishOptions)
            } catch (e: Exception) {
                lastError = e
                handlePublishRetry(attempt, jobId, e)
            }
        }
        throw lastError!!
    }

    /** Logs the failed [attempt] and, unless it was the last one, sleeps the matching
     *  [backoffScheduleMs] entry before the caller's loop retries — extracted out of
     *  [publishResult]'s `catch` to keep that method's nested block depth down. */
    private fun handlePublishRetry(attempt: Int, jobId: String, cause: Exception) {
        if (attempt == MAX_ATTEMPTS) {
            log.error("Failed to publish job result {} to NATS after {} attempts: {}", jobId, attempt, cause.message)
            return
        }
        val backoff = backoffScheduleMs[attempt - 1]
        log.warn("Publish attempt {} failed for job result {}, retrying in {}ms: {}", attempt, jobId, backoff, cause.message)
        sleepBackoff(backoff, cause)
    }

    /** [cause] is rethrown on interrupt so the retry loop stops instead of trying again — same
     *  behaviour as the inline `try`/`catch` this replaced. */
    private fun sleepBackoff(backoffMs: Long, cause: Exception) {
        try {
            Thread.sleep(backoffMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw cause
        }
    }

    /**
     * AP-M6 — `SETEX lt:kosit:{jobId} 3600 <report>` when [kositReport] is non-blank, returning
     * the `{key}` reference to send instead. A blank report yields `null` — nothing to store,
     * nothing to reference, byte-identical to how every existing `""`-default call site already
     * behaved before this AP. A `null` [payloadExchange] (test wiring without Valkey) still
     * returns a ref: the ref key is fully determined by [jobId], not by a successful store — the
     * write just silently never lands, same degrade contract as [PayloadExchange.put] itself.
     */
    private fun kositReportRef(jobId: String, kositReport: String): KositReportRefDto? {
        if (kositReport.isBlank()) return null
        val key = "lt:kosit:$jobId"
        payloadExchange?.put(key, kositReport, KOSIT_REPORT_TTL_SECONDS)
        return KositReportRefDto(key)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** AP-M6 — the reader resolves the ref within seconds of the result being published. */
        const val KOSIT_REPORT_TTL_SECONDS = 3_600L
    }
}
