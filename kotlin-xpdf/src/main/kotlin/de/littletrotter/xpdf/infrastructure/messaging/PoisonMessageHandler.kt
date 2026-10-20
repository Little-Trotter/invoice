package de.littletrotter.xpdf.infrastructure.messaging

import io.nats.client.JetStream
import io.nats.client.Message
import io.nats.client.impl.Headers
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.slf4j.LoggerFactory
import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.xpdf.readEnv

private const val RESULT_KEY_ERROR = "error"
private const val NATS_HEADER_JOB_ID = "jobId"

/**
 * Split out of `JobConsumer`: every case where a NATS job message can never be turned
 * into a durable result (unparseable JSON, an invoice payload that doesn't map, or a redelivery
 * count over budget). Each case is archived raw to the DLQ stream and reported as a definitive
 * `FAILED` result, then acked so JetStream never redelivers it forever.
 */
@ApplicationScoped
class PoisonMessageHandler @Inject constructor(
  private val resultPublisher: ResultPublisher,
  private val jetStream: JetStream,
  // Configuration via constructor injection instead of a `lateinit`/`@ConfigProperty`
  // field: CDI always resolves the real value here; the Kotlin default only serves the many
  // direct-construction tests/E2E helpers that never override the DLQ subject — see
  // [JobConsumer]'s own KDoc on its equivalent parameters. Poison messages (unparseable JSON,
  // unmappable invoice) are archived raw on this subject, which the DLQ stream
  // (de.littletrotter.shared.NatsStreamSetup.DLQ_STREAM_NAME) owns.
  @param:ConfigProperty(name = "nats.subject.document-jobs-dlq", defaultValue = DocumentJobSubjects.JOBS_DLQ)
  private val dlqSubject: String = DocumentJobSubjects.JOBS_DLQ,
) {

  private val log = LoggerFactory.getLogger(javaClass)

  // Same "System property, then env, then default" fallback every JobConsumer config field uses
  // (see JobConsumer's own KDoc for why: routed through readEnv/EnvConfig so tests can intercept
  // the env-set branch, since Mockito refuses to mock java.lang.System statically).
  private val retryCount = (System.getProperty("job.retry.count") ?: readEnv("JOB_RETRY_COUNT") ?: "3").toInt()

  /**
   * `max_deliver -1` (unlimited redelivery) plus the app-side stale-DELEGATED
   * reset only ever firing for a message that is genuinely gone means a job that keeps failing to
   * have its RESULT durably published would otherwise redeliver — and re-run its whole export —
   * forever. Bounded here: once a redelivery count exceeds the same budget the retry loop already
   * uses, this delivery is treated as poison instead of processed again.
   *
   * `(retryCount + 1).coerceAtLeast(1)`, not a bare `retryCount + 1`: retryCount can be configured
   * negative ("skip entirely"), which would otherwise make even a message's very first delivery
   * (`deliveredCount() == 1`) read as already over-budget — the coercion keeps this check inert on
   * a first delivery no matter how retryCount is configured; only an ACTUAL redelivery can ever
   * trigger it.
   */
  fun isExcessivelyRedelivered(message: Message): Boolean =
    deliveredCountOf(message) > (retryCount + 1).coerceAtLeast(1)

  /** `metaData()` throws for a message that isn't actually a JetStream delivery and a plain test
   *  double's mocked [Message] answers `null` for an unstubbed call — both read as "first
   *  delivery" (`1`), the only value that can never itself look over-budget, rather than risk
   *  misreading a metadata failure as poison. */
  private fun deliveredCountOf(message: Message): Long =
    try {
      message.metaData()?.deliveredCount() ?: 1L
    } catch (e: IllegalStateException) {
      // not a JetStream delivery at all
      firstDelivery(e)
    } catch (e: IllegalArgumentException) {
      // JetStream reply subject that the metadata parser cannot read
      firstDelivery(e)
    }

  private fun firstDelivery(cause: RuntimeException): Long {
    log.debug("metaData() lookup failed -- treating this delivery as a first delivery", cause)
    return 1L
  }

  /**
   * Same shape as [handleUnparseableMessage]: a best-effort parse just to recover
   * [DocumentJobRequest.id]/[DocumentJobRequest.correlationId] for the poison report (this redelivery may be the
   * very first time this JVM ever sees the payload — unlike [handleUnparseableMessage], the JSON
   * is not assumed to already be known-bad), falling back to the NATS `jobId` header the same way.
   * No id anywhere at all means nothing more can be done beyond dropping it.
   */
  fun handleExcessiveRedelivery(message: Message) {
    val raw = String(message.data, Charsets.UTF_8)
    val deliveredCount = deliveredCountOf(message)
    log.error(
      "Job message redelivered {} time(s) (budget {}) without a durable result -- giving up: {}",
      deliveredCount, retryCount + 1, raw,
    )
    val job = runCatching { DocumentJobWireCodec.decodeRequest(raw) }.getOrNull()
    val jobId = job?.id ?: message.headers?.getFirst(NATS_HEADER_JOB_ID)
    if (jobId == null) {
      log.error("Excessively redelivered job message also carries no jobId header — nothing more can be done, dropping it")
      message.ack()
      return
    }
    publishPoisonFailureAndAck(
      message, jobId, job,
      "redelivered $deliveredCount time(s) without a durable result -- giving up",
    )
  }

  /**
   * A message whose JSON never parsed has no [DocumentJobRequest] to read an id from; the
   * only thing that survives a garbage payload is the NATS `jobId` header `DocumentJobPublisher`
   * already attaches to every job message. Always acks (never nak's): nak'ing a message whose
   * payload is *permanently* unparseable would only make JetStream redeliver the exact same
   * poison payload forever.
   */
  fun handleUnparseableMessage(message: Message, cause: Exception) {
    log.error("Failed to parse job message", cause)
    val jobId = message.headers?.getFirst(NATS_HEADER_JOB_ID)
    if (jobId == null) {
      log.error("Unparseable job message also carries no jobId header — nothing more can be done, dropping it")
      message.ack()
      return
    }
    publishPoisonFailureAndAck(message, jobId, null, "unparseable job payload: ${cause.message}")
  }

  /** Same reasoning as [handleUnparseableMessage], but the JSON parsed fine so [job] already
   *  carries the jobId (and correlationId) — no header lookup needed. */
  fun handleUnmappableInvoice(message: Message, job: DocumentJobRequest, cause: Exception) {
    log.error("Failed to map invoice payload", cause)
    publishPoisonFailureAndAck(message, job.id, job, "unmappable job payload: ${cause.message}")
  }

  /**
   * No local `catch` here anymore: a publish failure propagates to
   * [JobConsumer]'s own per-message boundary catch, which still guarantees the ack (see that
   * method's own KDoc) — "never redelivered forever" now holds one level up instead of twice
   * over. DLQ archival and the ack itself are skipped when the FAILED-result publish throws
   * (the caller's catch takes over both); this is unchanged from before for the DLQ-publish
   * failure case below, which still runs after a successful FAILED-result publish.
   */
  private fun publishPoisonFailureAndAck(
    message: Message,
    jobId: String,
    job: DocumentJobRequest?,
    reason: String,
  ) {
    resultPublisher.publishResult(jobId, "FAILED", mapOf(RESULT_KEY_ERROR to reason), "", job?.correlationId, job?.resultSubject)
    publishRawToDlq(message, jobId, reason)
    message.ack()
  }

  /**
   * Alongside the FAILED result above, the raw, still-unparsed/unmappable message
   * bytes are archived on the DLQ stream too, tagged with jobId/reason/origSubject headers so a
   * poison message can be triaged later (e.g. via `nats` CLI) without needing to reconstruct it
   * from logs.
   *
   * No local `catch` here anymore either: a DLQ-publish failure propagates to
   * [JobConsumer]'s own per-message boundary catch, which still guarantees the ack — "poison
   * messages are never redelivered forever" now holds via that one caller-side guarantee instead
   * of a second, duplicated one here.
   */
  private fun publishRawToDlq(message: Message, jobId: String, reason: String) {
    val headers = Headers()
      .add(NATS_HEADER_JOB_ID, jobId)
      // NATS header values reject CR/LF (Jackson's own parse-error messages routinely contain
      // embedded newlines) — collapsed to spaces so the header itself never fails to encode.
      .add("reason", reason.replace(Regex("[\\r\\n]+"), " "))
      .add("origSubject", message.subject)
    jetStream.publish(dlqSubject, headers, message.data)
  }
}
