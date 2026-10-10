package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.FacturXExportOutcome
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.TripWorkbookRefDto
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.xpdf.readEnv

/**
 * Split out of `JobConsumer`: routes a decoded [DocumentJobRequest]/[InvoiceDto] pair to the
 * right export engine, resolves trip-workbook references beforehand, and retries a
 * transient export failure up to the configured budget. Every definitive outcome (success,
 * exhausted retries, engine rejection, unknown engine) is reported
 * through [JobResultReporter] — this class only ever decides *why*.
 */
@ApplicationScoped
class JobDispatcher @Inject constructor(
  private val pdfExportService: PdfExportPort,
  // Resolves trip workbook references before rendering.
  private val payloadExchange: PayloadExchange,
  private val resultReporter: JobResultReporter,
) {

  private val log = LoggerFactory.getLogger(javaClass)

  private val retryCount = (System.getProperty("job.retry.count") ?: readEnv("JOB_RETRY_COUNT") ?: "3").toInt()
  private val backoffMs = (System.getProperty("job.retry.backoff.ms") ?: readEnv("JOB_RETRY_BACKOFF_MS") ?: "1000").toLong()

  // The wire value DocumentJobPublisher already resolved.
  // `null`/"MUSTANG" is the original path and must stay byte-identical; every
  // other value gets its own explicit branch. There is deliberately no `else -> processMustangJob`
  // anywhere here: falling back to Mustang for an unrecognized value is exactly the
  // silent-fallback bug this fixes, not a safety net to keep.
  //
  // "FACTURX" reaches the real `FacturXEngine` port via
  // [processFacturXJob]. A transport/engine failure still ends in a clean `FAILED` result; it
  // just comes from the port's own [FacturXExportOutcome.Failed] now instead of a fixed reason.
  fun dispatch(job: DocumentJobRequest, invoice: InvoiceDto) {
    val resolvedInvoice = resolveTripWorkbookRefs(invoice)
    when (val engine = job.engine) {
      null, "MUSTANG" -> processMustangJob(job, resolvedInvoice)
      "FACTURX" -> processFacturXJob(job, resolvedInvoice)
      else -> resultReporter.publishUnknownEngine(job, engine, resolveOptions(job).first)
    }
  }

  /**
   * Resolves every `TRIP` item's [de.littletrotter.shared.TripExportDto.workbookRef] (if
   * any) into [de.littletrotter.shared.TripExportDto.resolvedWorkbookBase64], with a
   * "producer SETEXes, consumer GETs+verifies" shape, degrading
   * gracefully instead of failing the job: a missing/mismatched Valkey key here just means this
   * trip travels without its BG-24 workbook attachment, never a definitive FAILED result.
   */
  private fun resolveTripWorkbookRefs(invoice: InvoiceDto): InvoiceDto {
    if (invoice.items.all { it.trip?.workbookRef == null }) return invoice
    val resolvedItems = invoice.items.map { item ->
      val trip = item.trip
      val ref = trip?.workbookRef
      if (trip == null || ref == null) {
        item
      } else {
        item.copy(trip = trip.copy(resolvedWorkbookBase64 = resolveTripWorkbookBase64(ref)))
      }
    }
    return invoice.copy(items = resolvedItems)
  }

  private fun resolveTripWorkbookBase64(ref: TripWorkbookRefDto): String? {
    val stored = payloadExchange.get(ref.key)
    if (stored == null) {
      log.warn("Trip workbook ref expired/missing: key {}", ref.key)
      return null
    }
    if (PayloadExchange.sha256Hex(stored) != ref.sha256) {
      log.warn("Trip workbook ref mismatch: key {}", ref.key)
      return null
    }
    return stored
  }

  private fun processMustangJob(job: DocumentJobRequest, invoice: InvoiceDto) {
    val (llmOptimize, skipValidation, veraPdfCheck) = resolveOptions(job)
    val result = runWithRetry(job, "Job {} processing failed on attempt {}", llmOptimize) {
      pdfExportService.performExport(invoice, job.id, llmOptimize, skipValidation, veraPdfCheck, job.options?.outputIntent)
    } ?: return
    // Deliberately outside runWithRetry's try/catch: a failure exporting is retryable, but a
    // failure publishing an already-successful export's result is not the same kind of
    // problem — it must propagate so JobConsumer.handle() nak()s instead of this loop re-running
    // the export.
    resultReporter.publishCompleted(job, result)
  }

  /**
   * The Factur-X counterpart to [processMustangJob]. The engine's own accept/reject
   * verdict ([FacturXExportOutcome]) is a definitive, non-retryable answer and is handled as a
   * normal branch below; an unrelated infrastructure exception still goes through the same
   * retry loop as [processMustangJob] — only the engine's own verdict skips retrying.
   */
  private fun processFacturXJob(job: DocumentJobRequest, invoice: InvoiceDto) {
    val (llmOptimize, skipValidation, veraPdfCheck) = resolveOptions(job)
    val outcome = runWithRetry(job, "Job {} Factur-X processing failed on attempt {}", llmOptimize) {
      pdfExportService.performFacturXExport(invoice, job.id, llmOptimize, skipValidation, veraPdfCheck, job.options?.outputIntent)
    } ?: return
    // Deliberately outside runWithRetry's try/catch — same reasoning as processMustangJob: both
    // branches below publish a definitive result and must let a publish failure propagate.
    when (outcome) {
      is FacturXExportOutcome.Success -> resultReporter.publishCompleted(job, outcome.result)
      is FacturXExportOutcome.Failed -> resultReporter.publishFacturXEngineFailure(job, outcome, llmOptimize)
    }
  }

  /**
   * Shared retry wrapper for [processMustangJob] and [processFacturXJob]: both loop the same way
   * (retry [action] up to [retryCount] times, sleeping via [sleepBeforeRetry] between attempts,
   * giving up via [JobResultReporter.publishRetryExhaustedFailure] once attempts are exhausted)
   * and differ only in what they do with a successful [action] result — which stays the caller's
   * job. Returns `null` (never calls [action] again) once retries are exhausted or the sleep is
   * interrupted, so callers should treat `null` as "already handled, just return".
   *
   * A negative configured retry count means "skip entirely" — the one case the original
   * `while (attempt <= retryCount)` loop condition actually decided (attempt starts at 0, so
   * `0 <= retryCount` is false exactly when retryCount is negative). Pulling this one live case
   * out as an explicit guard lets the loop itself become `while(true)`.
   */
  private fun <T> runWithRetry(job: DocumentJobRequest, failureLogTemplate: String, llmOptimize: Boolean, action: () -> T): T? {
    if (retryCount < 0) return null
    var attempt = 0
    while (true) {
      attempt++
      try {
        return action()
      } catch (e: Exception) {
        log.warn(failureLogTemplate, job.id, attempt, e)
        if (attempt > retryCount) {
          log.error("Job {} failed after {} attempts", job.id, attempt, e)
          resultReporter.publishRetryExhaustedFailure(job, e, llmOptimize)
          return null
        }
        if (!sleepBeforeRetry(job, attempt)) {
          return null
        }
      }
    }
  }

  /** llmOptimize is the current option name; ollamaOptimize kept as fallback for older queued
   *  messages that were published before the rename. `when` instead of Elvis because Jackson
   *  deserializes missing booleans to `false`, not `null`. veraPdfCheck is a passthrough of what
   *  DocumentJobPublisher already resolved against `ValidationCapabilityId.VERAPDF_CHECK` — this
   *  worker never touches the switch itself. Shared by both engine paths so a producer-side
   *  option always means the same thing regardless of which engine processes the job. */
  private fun resolveOptions(job: DocumentJobRequest): Triple<Boolean, Boolean, Boolean> {
    val llmOptimize = when {
      job.options?.llmOptimize == true -> true
      job.options?.ollamaOptimize == true -> true
      else -> false
    }
    val skipValidation = job.options?.skipValidation ?: false
    val veraPdfCheck = job.options?.veraPdfCheck ?: false
    return Triple(llmOptimize, skipValidation, veraPdfCheck)
  }

  /** Sleeps the backoff window before the next retry attempt; returns `false` (caller must
   *  return immediately) when the sleep was interrupted. */
  private fun sleepBeforeRetry(job: DocumentJobRequest, attempt: Int): Boolean {
    return try {
      TimeUnit.MILLISECONDS.sleep(backoffMs * attempt)
      true
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      log.warn("Retry sleep interrupted for job {}", job.id)
      false
    }
  }
}
