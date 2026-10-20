package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PdfExportPort
import de.littletrotter.shared.TripWorkbookRefDto
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.xpdf.ExportSwitches
import de.littletrotter.xpdf.NonRetryableExportException
import de.littletrotter.xpdf.readEnv

/**
 * Split out of `JobConsumer`: routes a decoded [DocumentJobRequest]/[InvoiceDto] pair to the
 * right export engine, resolves trip-workbook references beforehand, and retries a
 * transient export failure up to the configured budget. Every definitive outcome (success,
 * exhausted retries, unknown engine) is reported
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
  // `null`/"MUSTANG" is the only supported path; every other value (including a legacy
  // "FACTURX" from a producer that still sends it) is reported as an unknown engine. There is
  // deliberately no `else -> processMustangJob`: falling back to Mustang for an unrecognized
  // value would be a silent fallback, not a safety net.
  fun dispatch(job: DocumentJobRequest, invoice: InvoiceDto) {
    val resolvedInvoice = resolveTripWorkbookRefs(invoice)
    when (val engine = job.engine) {
      null, "MUSTANG" -> processMustangJob(job, resolvedInvoice)
      else -> resultReporter.publishUnknownEngine(job, engine, resolveOptions(job).llmOptimize)
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
    val options = resolveOptions(job)
    val result = runWithRetry(job, "Job {} processing failed on attempt {}", options.llmOptimize) {
      // The KoSIT switch reaches the pipeline as per-job context, like the lane: it only matters
      // to one stage and the export port's signature stays the one every caller knows.
      ExportSwitches.withKositCheck(options.kositCheck) {
        pdfExportService.performExport(
          invoice, job.id, options.llmOptimize, options.skipValidation, options.veraPdfCheck, job.options?.outputIntent,
        )
      }
    } ?: return
    // Deliberately outside runWithRetry's try/catch: a failure exporting is retryable, but a
    // failure publishing an already-successful export's result is not the same kind of
    // problem — it must propagate so JobConsumer.handle() nak()s instead of this loop re-running
    // the export.
    resultReporter.publishCompleted(job, result)
  }

  /**
   * Retry wrapper for [processMustangJob]: retries [action] up to [retryCount] times,
   * sleeping via [sleepBeforeRetry] between attempts, giving up via
   * [JobResultReporter.publishRetryExhaustedFailure] once attempts are exhausted. What to do with
   * a successful [action] result stays the caller's job. Returns `null` (never calls [action] again) once retries are exhausted or the sleep is
   * interrupted, so callers should treat `null` as "already handled, just return".
   *
   * A negative configured retry count means "skip entirely" — the one case the original
   * `while (attempt <= retryCount)` loop condition actually decided (attempt starts at 0, so
   * `0 <= retryCount` is false exactly when retryCount is negative). Pulling this one live case
   * out as an explicit guard keeps the loop free of that special case.
   *
   * A [NonRetryableExportException] is reported on its first occurrence through the same failure
   * path: the same invoice would fail the same way again, and each retry would hold the worker
   * thread with a full render plus backoff sleep.
   */
  private fun <T> runWithRetry(job: DocumentJobRequest, failureLogTemplate: String, llmOptimize: Boolean, action: () -> T): T? {
    if (retryCount < 0) return null
    var attempt = 0
    var result: T? = null
    var running = true
    while (running) {
      attempt++
      try {
        result = action()
        running = false
      } catch (e: NonRetryableExportException) {
        log.error("Job {} failed with a non-retryable error", job.id, e)
        resultReporter.publishRetryExhaustedFailure(job, e, llmOptimize)
        running = false
      } catch (e: Exception) {
        log.warn(failureLogTemplate, job.id, attempt, e)
        running = if (attempt > retryCount) {
          log.error("Job {} failed after {} attempts", job.id, attempt, e)
          resultReporter.publishRetryExhaustedFailure(job, e, llmOptimize)
          false
        } else {
          sleepBeforeRetry(job, attempt)
        }
      }
    }
    return result
  }

  /** llmOptimize is the current option name; ollamaOptimize kept as fallback for older queued
   *  messages that were published before the rename. `when` instead of Elvis because Jackson
   *  deserializes missing booleans to `false`, not `null`. veraPdfCheck is a passthrough of what
   *  DocumentJobPublisher already resolved against `ValidationCapabilityId.VERAPDF_CHECK` — this
   *  worker never touches the switch itself. Shared by both engine paths so a producer-side
   *  option always means the same thing regardless of which engine processes the job. */
  private fun resolveOptions(job: DocumentJobRequest): ResolvedOptions {
    val llmOptimize = when {
      job.options?.llmOptimize == true -> true
      job.options?.ollamaOptimize == true -> true
      else -> false
    }
    val skipValidation = job.options?.skipValidation ?: false
    val veraPdfCheck = job.options?.veraPdfCheck ?: false
    // An absent option reads as on: a producer that predates it keeps validating with KoSIT.
    val kositCheck = job.options?.kositCheck ?: true
    return ResolvedOptions(llmOptimize, skipValidation, veraPdfCheck, kositCheck)
  }

  private data class ResolvedOptions(
    val llmOptimize: Boolean,
    val skipValidation: Boolean,
    val veraPdfCheck: Boolean,
    val kositCheck: Boolean,
  )

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
