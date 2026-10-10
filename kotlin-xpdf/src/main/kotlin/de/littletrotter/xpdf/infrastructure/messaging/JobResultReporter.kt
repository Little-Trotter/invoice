package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.FacturXExportOutcome
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.xpdf.readEnv

private const val RESULT_KEY_ERROR = "error"

/**
 * Split out of `JobConsumer`: shapes and publishes every definitive job outcome
 * (`COMPLETED`/`FAILED`) on the wire. [JobDispatcher] decides *why* a job succeeded or failed;
 * this class owns *how* that decision is reported.
 */
@ApplicationScoped
class JobResultReporter @Inject constructor(
  private val resultPublisher: ResultPublisher,
) {

  private val log = LoggerFactory.getLogger(javaClass)

  private val s3Endpoint = System.getProperty("s3.endpoint") ?: readEnv("S3_ENDPOINT") ?: "http://rustfs:9000"
  private val bucket = System.getProperty("s3.bucket") ?: readEnv("S3_BUCKET") ?: "documents"

  /** Publishes a `COMPLETED` result for [result] — identical for both engines, since
   *  [ExportResult]'s shape is already engine-agnostic. */
  fun publishCompleted(job: DocumentJobRequest, result: ExportResult) {
    // build result refs — use actual stored keys from ExportResult, not re-derived paths
    val downloadUrl = "$s3Endpoint/$bucket/${result.objectKey}"
    val resultRefs = buildMap {
      put("objectKey",        result.objectKey)
      put("downloadUrl",      downloadUrl)
      put("zugferdObjectKey", result.zugferdXmlKey)
      // The checksums already computed at export time. `zugferdXmlSha256` is absent only
      // for the one Factur-X outcome with no XML at all — see ExportResult's own KDoc.
      if (result.sha256.isNotBlank())          put("sha256",           result.sha256)
      result.zugferdXmlSha256?.takeIf { it.isNotBlank() }?.let { put("zugferdXmlSha256", it) }
      // The archive package's own key/checksum, absent for a draft export or the one
      // degraded Factur-X outcome with no XML at all.
      result.archiveObjectKey?.takeIf { it.isNotBlank() }?.let { put("archiveObjectKey", it) }
      result.archiveSha256?.takeIf { it.isNotBlank() }?.let { put("archiveSha256", it) }
      if (result.llmReportKey.isNotBlank()) put("llmReportKey", result.llmReportKey)
      // Carried in resultRefs so the reader can close the InvoiceLlmAnalysis status chain.
      if (result.llmStatus.isNotBlank())   put("llmStatus",     result.llmStatus)
      if (result.llmModel.isNotBlank())    put("llmModel",      result.llmModel)
      if (result.llmError.isNotBlank())    put("llmError",      result.llmError)
      if (result.llmDurationMs > 0)        put("llmDurationMs", result.llmDurationMs.toString())
      // formatGuideline is optional and may be absent (readers tolerate missing key).
      result.formatGuideline?.takeIf { it.isNotBlank() }?.let { put("formatGuideline", it) }
    }

    // publish result back to main app (NATS) — AP-M1 §3: a failure here propagates (not caught)
    // so the caller lets it bubble out to JobConsumer.handle(), which nak()s the job message
    // instead of acking. The export already succeeded; only the result-publish failed, so
    // redelivery — not re-exporting from scratch here — is the fix.
    resultPublisher.publishResult(job.id, "COMPLETED", resultRefs, result.kositReport, job.correlationId, job.resultSubject)

    log.info("Job {} processed successfully (correlation={})", job.id, job.correlationId)
  }

  /**
   * The engine gave a definitive rejection ([outcome]'s findings), not a transient fault:
   * publishes `FAILED` with [FacturXExportOutcome.Failed.reportJson] unchanged and returns
   * without retrying — retrying would only repeat the same engine answer.
   */
  fun publishFacturXEngineFailure(job: DocumentJobRequest, outcome: FacturXExportOutcome.Failed, llmOptimize: Boolean) {
    val reason = outcome.findings.joinToString("; ") { it.message }
    log.warn("Job {} Factur-X engine reported failure: {}", job.id, reason)
    // AP-M1 §3 — the engine's verdict is definitive and must not be swallowed on a publish
    // failure; propagates to JobConsumer.handle(), which nak()s and JetStream redelivers.
    resultPublisher.publishResult(job.id, "FAILED", mapOf(RESULT_KEY_ERROR to reason) + llmStatusMap(llmOptimize), outcome.reportJson, job.correlationId, job.resultSubject)
  }

  /** Publishes a plain-error `FAILED` result (no structured report — [e] is a thrown exception,
   *  not a validation finding), once a job's retry budget is exhausted on an unrelated
   *  infrastructure exception. */
  fun publishRetryExhaustedFailure(job: DocumentJobRequest, e: Exception, llmOptimize: Boolean) {
    // AP-M1 §3 — propagates on failure (not caught) so JobConsumer.handle() nak()s instead of
    // acking a lost FAILED result.
    resultPublisher.publishResult(job.id, "FAILED", mapOf(RESULT_KEY_ERROR to (e.message ?: "")) + llmStatusMap(llmOptimize), "", job.correlationId, job.resultSubject)
  }

  /**
   * An `engine` value this worker doesn't recognize (typo in the producer, or a value a newer
   * kotlin-invoice already offers that this worker hasn't shipped support for). Naming the
   * received value in the failure — rather than silently defaulting to Mustang — is the point.
   */
  fun publishUnknownEngine(job: DocumentJobRequest, engine: String, llmOptimize: Boolean) {
    val reason = "Unknown engine \"$engine\""
    log.error("Job {} requested unknown engine \"{}\"", job.id, engine)
    // AP-M1 §3 — a definitive verdict (a parseable, mappable job requesting an engine we don't
    // have), not a swallow-and-move-on situation; a publish failure propagates so the caller
    // nak()s and JetStream redelivers.
    resultPublisher.publishResult(job.id, "FAILED", mapOf(RESULT_KEY_ERROR to reason) + llmStatusMap(llmOptimize), "", job.correlationId, job.resultSubject)
  }

  /**
   * DSC001-2025-09-00001 — a job that fails during or before the deterministic stages
   * never reaches PdfExportServiceImpl's own llmStatus decision at all. Before this, that FAILED
   * result carried only `{"error": ...}`, silently losing whether an AI check had even been
   * requested. "FAILED" (not "PENDING") is used here deliberately: a job that already failed
   * before/inside those stages will never reach the async oMLX stage at all, so there is nothing
   * left pending — the outcome is already final.
   */
  private fun llmStatusMap(llmOptimize: Boolean): Map<String, String> =
    mapOf("llmStatus" to if (llmOptimize) "FAILED" else "NOT_REQUESTED")
}
