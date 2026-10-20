package de.littletrotter.xpdf.pdfcheck

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.documentjob.PdfCheckRequest
import de.littletrotter.shared.documentjob.PdfCheckResult
import de.littletrotter.xpdf.impl.VeraPdfRestClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Checks one stored PDF against the conformance service. The document is streamed from object
 * storage straight into the service request: it is neither held in memory nor opened with a PDF
 * library here, which is what lets the check run beside a memory-bound export.
 *
 * A check that could not be performed comes back as an `ERROR` result carrying the reason; it is
 * the caller that decides whether to try again.
 */
@ApplicationScoped
class PdfCheckProcessor @Inject constructor(
    private val documentStore: DocumentStore,
    private val client: VeraPdfRestClient,
    private val registry: MeterRegistry,
) {

    fun check(request: PdfCheckRequest): PdfCheckResult {
        val sample = Timer.start(registry)
        val verdict = documentStore.openStream(request.objectKey).use { client.validate(it, request.sha256) }
        val result = PdfCheckResult(
            checkId = request.checkId,
            jobId = request.jobId,
            objectKey = request.objectKey,
            sha256 = request.sha256,
            outcome = verdict.outcome.name,
            profile = verdict.profile,
            validator = verdict.validator,
            failedRuleCount = verdict.failedRuleCount,
            failedCheckCount = verdict.failedCheckCount,
            findings = verdict.findings.take(MAX_FINDINGS),
            engineError = verdict.engineError,
            correlationId = request.correlationId,
        )
        sample.stop(
            Timer.builder(METER)
                .tag("outcome", result.outcome.lowercase())
                .publishPercentileHistogram()
                .register(registry),
        )
        return result
    }

    companion object {
        const val METER = "xpdf.pdfcheck"

        /** One finding per failed rule; the cap keeps the result message far below the broker's
         *  message size limit for a document that violates the profile everywhere. */
        const val MAX_FINDINGS = 200
    }
}
