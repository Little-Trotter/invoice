package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.shared.documentjob.QuoteDocumentJob
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.xpdf.QuotePdfExport
import de.littletrotter.xpdf.QuotePdfResult
import de.littletrotter.xpdf.readEnv
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Runs one decoded `QUOTE_PDF` job: renders through [QuotePdfExport], retrying a transient
 * failure up to the configured budget, and reports the definitive outcome on the job's own result
 * subject. The report travels inline in the result because the offer service has no access to the
 * payload exchange the invoice reports use.
 */
@ApplicationScoped
class QuoteJobProcessor @Inject constructor(
    private val pipeline: QuotePdfExport,
    private val resultPublisher: ResultPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** `internal var` so a test can shorten the retry budget without touching process-wide properties. */
    internal var retryCount = (System.getProperty("job.retry.count") ?: readEnv("JOB_RETRY_COUNT") ?: "3").toInt()
    internal var backoffMs = (System.getProperty("job.retry.backoff.ms") ?: readEnv("JOB_RETRY_BACKOFF_MS") ?: "1000").toLong()

    fun process(job: DocumentJobRequest, payload: QuoteDocumentPayload) {
        // The result publish stays outside the retry loop: a failed publish must reach the
        // consumer so the job message is redelivered, not re-rendered.
        when (val outcome = renderWithRetry(job, payload)) {
            is Rendered -> resultPublisher.publishResult(
                job.id,
                "COMPLETED",
                mapOf(
                    QuoteDocumentJob.REF_OBJECT_KEY to outcome.result.objectKey,
                    QuoteDocumentJob.REF_SHA256 to outcome.result.sha256,
                    QuoteDocumentJob.REF_VALIDATION_REPORT to outcome.result.validationReportJson,
                ),
                correlationId = job.correlationId,
                resultSubject = job.resultSubject,
            )
            is Exhausted -> resultPublisher.publishResult(
                job.id,
                "FAILED",
                mapOf(QuoteDocumentJob.REF_ERROR to (outcome.cause.message ?: outcome.cause.javaClass.simpleName)),
                correlationId = job.correlationId,
                resultSubject = job.resultSubject,
            )
        }
    }

    private fun renderWithRetry(job: DocumentJobRequest, payload: QuoteDocumentPayload): Outcome {
        var attempt = 0
        while (true) {
            attempt++
            val rendered = runCatching { pipeline.export(job.id, payload) }
            rendered.onSuccess { return Rendered(it) }
            val failure = checkNotNull(rendered.exceptionOrNull())
            log.warn("Offer job {} failed on attempt {}", job.id, attempt, failure)
            if (attempt > retryCount) return Exhausted(failure)
            TimeUnit.MILLISECONDS.sleep(backoffMs * attempt)
        }
    }

    private sealed interface Outcome

    private class Rendered(val result: QuotePdfResult) : Outcome

    private class Exhausted(val cause: Throwable) : Outcome
}
