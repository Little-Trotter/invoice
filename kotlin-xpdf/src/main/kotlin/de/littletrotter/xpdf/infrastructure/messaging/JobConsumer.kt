package de.littletrotter.xpdf.infrastructure.messaging

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.nats.client.Connection
import io.nats.client.ConsumeOptions
import io.nats.client.JetStream
import io.nats.client.Message
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.documentjob.DocumentJobRequest
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.QuoteDocumentJob
import de.littletrotter.shared.documentjob.QuoteDocumentPayloadCodec
import de.littletrotter.xpdf.readEnv

/**
 * Consumes the document jobs of one [JobLane]. Not a CDI bean itself: [JobConsumerLanes] creates
 * and subscribes one instance per lane, so each lane has its own durable consumer, worker pool and
 * heartbeat registration while all processing logic stays shared.
 */
class JobConsumer(
  private val connection: Connection,
  private val jetStream: JetStream,
  // Decode-failure/poison-message handling (unparseable JSON, unmappable invoice,
  // excessive redelivery, DLQ archival) lives in its own unit; see its own KDoc.
  private val poisonMessageHandler: PoisonMessageHandler,
  // Job-type routing, trip-ref resolution and the retry loop live in their own
  // unit; see its own KDoc.
  private val jobDispatcher: JobDispatcher,
  // Offer (`QUOTE_PDF`) jobs carry no invoice and are routed here before any invoice mapping.
  private val quoteJobProcessor: QuoteJobProcessor,
  // The lane carries subject, durable name and worker threads. The default only serves the many
  // direct-construction tests/E2E helpers that never override them ("test seam default, never
  // reached in production": [JobConsumerLanes] always passes an explicit lane).
  private val lane: JobLane = JobLane.bulk(),
) {

  private val log = LoggerFactory.getLogger(javaClass)

  // A message from a not-yet-upgraded producer (rolling deploy) may still carry a
  // field this consumer's DTOs no longer declare (e.g. the removed sender headline); it must
  // be ignored, not rejected outright. Same tolerant-reader precedent as
  // de.littletrotter.shared.KositReportPayload's own reader.
  private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  // Was a hardcoded 10-minute
  // ackWait ("guard against hung export threads ... triggering redelivery"); now that [receive]/
  // [handle] send a periodic in-progress heartbeat for the full duration of a job, from the moment
  // it is handed to the executor (see [heartbeatExecutor]/[scheduleHeartbeat] below), the ack
  // window no longer has to be wide enough to cover a single slow oMLX run — it only has to be
  // wide enough that a *missed* heartbeat (a genuinely dead/hung worker) gets redelivered
  // promptly. Configurable so an operator can tune it without a rebuild; default 120s, low enough
  // that ackWait itself can safely stay at 2 minutes.
  //
  // kotlin-invoice's own stale-DELEGATED recovery scan no longer depends on this value
  // being kept in sync: it checks the message's real existence in the stream instead of a bare DB
  // timestamp, so a false-positive candidate (this worker's lease legitimately still alive) simply
  // resolves to "message still exists" and is skipped, never reset.
  private val ackWaitSeconds = (System.getProperty("nats.consumer.ack-wait-seconds") ?: readEnv("NATS_CONSUMER_ACK_WAIT_SECONDS") ?: "120").toLong()

  // "Fixed rate = ackWait / 3": three heartbeats must land inside every ack window
  // before it could lapse, so a single dropped/delayed heartbeat send never risks a spurious
  // redelivery of a worker that is, in fact, still alive.
  private val heartbeatIntervalSeconds = ackWaitSeconds / 3

  // An open pull request (batchSize > 1) can still hand this JVM a message that only
  // becomes redeliverable *while the request is open* (ackWait lapse, nak) — no new pull needed.
  // But once that request's own window ends without every requested slot being filled, jnats's
  // client waits until its next scheduled pull before asking again; overriding that window to a
  // short, fixed value (instead of the jnats default of 30s, which nobody here chose on purpose)
  // bounds how stale this consumer's outstanding pull can get, so a single genuinely-hung job
  // with nothing else queued behind it is never left waiting needlessly long for its own
  // redelivered copy to actually reach this JVM.
  private val consumeExpiresInMillis = 10_000L

  // Both the executor below and subscribe()'s ConsumeOptions/maxAckPending
  // (workerThreads / 2*workerThreads) use the lane's one worker count.
  private val workerThreads = lane.workerThreads

  // jnats's simplified consume() re-pulls once `pendingProcessedMessages < batchSize -
  // max(1, batchSize * thresholdPercent / 100)`. At batchSize = 1 that threshold is 0 and is never
  // crossed: the single delivery fulfils the pull request, no new one is sent, and only jnats's
  // own heartbeat alarm restarts pulling — one job per ~30 s. [defaultWorkerThreads] legitimately
  // yields 1 on a small heap, so the pull batch is floored independently of [workerThreads];
  // `maxAckPending = 2 * workerThreads` still caps what the broker hands this JVM unacked.
  private val pullBatchSize = maxOf(MIN_PULL_BATCH_SIZE, workerThreads)

  /**
   * The pull consumer's own consume-thread delivers messages one batch at a time. Job
   * processing (PDF rendering, and especially the oMLX LLM step, which can legitimately take
   * minutes on a local model) must not run on that thread — otherwise one slow job blocks every
   * other queued job behind it. `receive()` only parses+dispatches; the actual work happens here.
   *
   * Was an unbounded `Executors.newFixedThreadPool`: a push consumer could hand this
   * queue up to `maxAckPending` (previously the 1000 default) messages, all sitting in the heap
   * at once. Now a bounded `ThreadPoolExecutor` (queue capacity `2 * workerThreads`, matching the
   * pull consumer's own `maxAckPending`) with
   * `CallerRunsPolicy`: once the queue is full, the *next* pull delivery itself blocks running the
   * rejected task inline — so pull pacing and executor pacing agree, and the JVM never holds more
   * unprocessed jobs than the broker itself is willing to hand out unacked. `internal` so tests can
   * swap in a synchronous executor for deterministic assertions.
   */
  internal var executor: Executor = ThreadPoolExecutor(
    workerThreads,
    workerThreads,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(2 * workerThreads),
    ThreadPoolExecutor.CallerRunsPolicy(),
  )

  /**
   * A single shared scheduler backing every in-flight job's heartbeat, not one
   * scheduler per message. Registration (in [scheduleHeartbeat], called from [handle]) and
   * deregistration (`finally` in [handle]) both operate purely on the [Message] itself — no
   * subscription/dispatcher internals — so this scheme carried over unchanged through the later
   * push→pull consumer migration. `internal`
   * so tests can substitute a mock and assert scheduling/cancellation deterministically instead
   * of racing a real timer.
   */
  internal var heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "job-consumer-heartbeat-${lane.name}").apply { isDaemon = true }
  }

  /**
   * Migrated from a push `Dispatcher`/`PushSubscribeOptions` subscription to
   * jnats's simplified pull API (jnats >= 2.26: `StreamContext` -> `ConsumerContext` ->
   * `consume(ConsumeOptions, handler)`). The durable consumer itself is created/updated
   * explicitly first, via the same shared helper [NatsStreamSetup] already uses for the streams
   * (`ensureConsumer`) — this replaces the implicit consumer creation the old
   * `PushSubscribeOptions.configuration(...)` buried, and gives config drift (ackWait,
   * maxAckPending) the same update-on-startup treatment the streams already get.
   * `maxAckPending = 2 * workerThreads` caps how many unacked messages the broker will ever hand
   * this JVM at once; `ConsumeOptions.batchSize = pullBatchSize` paces how many it pulls at a
   * time — both sized off the same [workerThreads] the bounded [executor] above uses, so pull
   * pacing and executor pacing agree ([pullBatchSize] only floors the single-thread case).
   */
  fun subscribe() {
    val jsm = connection.jetStreamManagement()
    NatsStreamSetup.ensureConsumer(
      jsm,
      NatsStreamSetup.STREAM_NAME,
      NatsStreamSetup.consumerConfiguration(
        durableName = lane.durableName,
        filterSubject = lane.subject,
        maxAckPending = 2 * workerThreads,
        // Was a fixed 10 minutes; now the configurable, heartbeat-backed
        // ackWaitSeconds (see its own KDoc above for why 2 minutes is now safe).
        ackWaitSeconds = ackWaitSeconds,
      ),
    )

    val consumerContext = jetStream.getStreamContext(NatsStreamSetup.STREAM_NAME).getConsumerContext(lane.durableName)
    consumerContext.consume(consumeOptions()) { message -> receive(message) }
  }

  /** `internal` so the real-NATS ITs consume with exactly the options production subscribes with. */
  internal fun consumeOptions(): ConsumeOptions = ConsumeOptions.builder()
    .batchSize(pullBatchSize)
    .expiresIn(consumeExpiresInMillis)
    .build()

  /**
   * Lease protection is scheduled here, the moment a message is handed to the bounded
   * [executor], not at the top of `handle()` once a worker thread actually starts running it.
   * Under a burst that queues more work than [executor] can immediately start (its queue capacity
   * `2 * workerThreads`), a message used to sit queued for up to that long *before its heartbeat
   * ever fired* — both the JetStream lease itself and kotlin-invoice's independent
   * `staleCutoffMinutes`-based recovery scan (`GenerationJobProcessor.resetStaleDelegatedJobs`,
   * whose own KDoc documents this exact relationship from the other side) could lapse for a job
   * that was never hung, only waiting its turn behind a full worker pool — the root cause of the
   * 2026-09-17 incident (a demo-seed burst printing every invoice at once) where genuinely
   * successful jobs were reset to PENDING out from under their own still-running worker attempt,
   * then discarded as an illegal `PENDING -> COMPLETED` transition once that attempt finished.
   * Scheduling here means queueing time is covered by the exact same heartbeat mechanism as
   * processing time — there is no longer a gap between them at all.
   */
  fun receive(message: Message) {
    val heartbeat = scheduleHeartbeat(message)
    executor.execute { handle(message, heartbeat) }
  }

  /**
   * Schedules a periodic `message.inProgress()` call (JetStream's own ack-window
   * reset) at [heartbeatIntervalSeconds], starting after the same delay. A failure sending one
   * heartbeat is logged and swallowed, not propagated: it must never abort the job itself, and
   * the next scheduled tick tries again on its own.
   *
   * Called from [receive], not from the top of [handle] anymore (see that method's own
   * KDoc for why). The "three heartbeats land inside every ack window" invariant
   * ([heartbeatIntervalSeconds]'s own KDoc) is unaffected by moving the start point earlier: it
   * only depends on the fixed interval derived from [ackWaitSeconds], never on how much of the
   * window has already elapsed once the first tick fires. Starting earlier only ever adds margin,
   * never removes it.
   */
  private fun scheduleHeartbeat(message: Message): ScheduledFuture<*> =
    heartbeatExecutor.scheduleAtFixedRate(
      {
        try {
          message.inProgress()
        } catch (e: Exception) {
          log.warn("Failed to send in-progress heartbeat for job message", e)
        }
      },
      heartbeatIntervalSeconds,
      heartbeatIntervalSeconds,
      TimeUnit.SECONDS,
    )

  /**
   * [heartbeat] is now scheduled by the caller ([receive]), before this method is even
   * dispatched onto [executor], instead of being scheduled at the top of this method's own body.
   * That is what makes it cover queueing time under a full worker pool, in addition to the
   * parsing/processing time it already covered (both long-running engine paths, and an
   * unexpectedly slow parse/map step). The `finally` still wraps the whole body, so it
   * deregisters the heartbeat on every exit path below — including the two early poison-job
   * `return`s.
   *
   * Decode failures delegate to [poisonMessageHandler]; a decoded job delegates to
   * [jobDispatcher]. Both units already publish their own definitive result on every failure
   * path; this method's own `catch` only ever fires for a genuinely unexpected failure that
   * escaped both of them (e.g. `message.ack()` itself throwing).
   *
   * [poisonMessageHandler]'s own methods no longer catch anything themselves
   * (no `TooGenericExceptionCaught` boundary duplicated per new unit); this is the one place that
   * still guarantees "a poison message is never redelivered forever" when one of them throws
   * (e.g. the FAILED-result publish or the DLQ archival itself failing) — see [handlePoisonPath].
   */
  private fun handle(message: Message, heartbeat: ScheduledFuture<*>) {
    try {
      if (poisonMessageHandler.isExcessivelyRedelivered(message)) {
        handlePoisonPath(message) { poisonMessageHandler.handleExcessiveRedelivery(message) }
        return
      }
      val raw = String(message.data, Charsets.UTF_8)
      log.info("Received job message: {}", raw)
      val job = try {
        DocumentJobWireCodec.decodeRequest(raw)
      } catch (e: Exception) {
        handlePoisonPath(message) { poisonMessageHandler.handleUnparseableMessage(message, e) }
        return
      }

      processDecodedJob(message, job)
    } finally {
      // Cancelling with mayInterruptIfRunning=false: the heartbeat task itself is a
      // single quick inProgress() call, never the job's own work, so there is nothing here that
      // ever needs interrupting — only "don't schedule another one".
      heartbeat.cancel(false)
    }
  }

  /** A decoded job is either an offer job or an invoice job; the two never share a payload shape. */
  private fun processDecodedJob(message: Message, job: DocumentJobRequest) {
    if (job.type == QuoteDocumentJob.TYPE) {
      handleQuoteJob(message, job)
      return
    }

    val invoice = try {
      // payload may contain full invoice under 'payload.invoice' or directly 'payload'
      val node = job.payload
      val invoiceNode = requireNotNull(if (node != null && node.containsKey("invoice")) node["invoice"] else node) { "No invoice in payload" }
      mapper.convertValue(invoiceNode, InvoiceDto::class.java)
    } catch (e: Exception) {
      handlePoisonPath(message) { poisonMessageHandler.handleUnmappableInvoice(message, job, e) }
      return
    }

    try {
      jobDispatcher.dispatch(job, invoice)
      message.ack()
    } catch (e: Exception) {
      // Every result-publish call site in JobDispatcher/JobResultReporter now lets a
      // final publish failure propagate instead of swallowing it (the export may have already
      // succeeded, or the FAILED verdict is definitive) so it lands here: nak instead of ack,
      // so JetStream redelivers and the result gets a second chance once NATS is reachable
      // again. Also catches genuinely unexpected failures (e.g. message.ack() itself
      // throwing), same as before.
      log.error("Unexpected failure while processing job message", e)
      message.nak()
    }
  }

  /**
   * An offer job: the payload must decode to an offer, otherwise the job is poison and fails
   * definitively; a decoded job is processed by [quoteJobProcessor], and a failure that escapes it
   * (the result publish) naks so JetStream redelivers, exactly like the invoice path.
   */
  private fun handleQuoteJob(message: Message, job: DocumentJobRequest) {
    val payload = try {
      QuoteDocumentPayloadCodec.fromMap(job.payload)
    } catch (e: Exception) {
      handlePoisonPath(message) { poisonMessageHandler.handleUnmappableInvoice(message, job, e) }
      return
    }
    try {
      quoteJobProcessor.process(job, payload)
      message.ack()
    } catch (e: Exception) {
      log.error("Unexpected failure while processing offer job message", e)
      message.nak()
    }
  }

  /**
   * [poisonMessageHandler]'s own decode-failure/redelivery-budget methods no
   * longer swallow their own internal failures (publishing the FAILED result, archiving to the
   * DLQ): this is the single boundary that still does, for exactly the reason
   * [PoisonMessageHandler]'s own KDoc always documented — nak'ing a message that is fundamentally
   * poison would only make JetStream redeliver the same poison payload forever, so [action]'s
   * failure is logged and the message is still acked here instead.
   */
  private fun handlePoisonPath(message: Message, action: () -> Unit) {
    try {
      action()
    } catch (e: Exception) {
      log.error("Unexpected failure in the poison-message path -- acking anyway to avoid endless redelivery", e)
      message.ack()
    }
  }

  companion object {
    // The "boot + threads × render" formula, arithmetic estimates (no per-call
    // heap profile exists yet), sized conservatively from the 2026-09-18 stress finding's own
    // numbers (755 MiB observed boot peak inside a 768 MiB limit, at the previous fixed
    // -Xmx512m/threads=4 that this formula replaces): BOOT_RESERVE_BYTES covers the KoSIT scenario
    // compile's own peak (EmbeddedKositValidator, now paid once — lazily/asynchronously, see that
    // class's and RenderWarmup's own KDoc for point 2) plus JVM/Quarkus baseline residency;
    // PER_THREAD_RENDER_BYTES is one concurrent Typst + Mustang + KoSIT-check run's peak
    // transient allocation, the same order of magnitude as kotlin-invoice's own
    // DocumentJobDispatchGate sizing comment (~100 MiB per concurrent call) next to
    // LT_INVOICE_MEMORY_LIMIT. See docker-compose.yml's own LT_XPDF_MEMORY_LIMIT comment for the
    // paired container-memory default this is meant to fit inside, with headroom.
    internal const val BOOT_RESERVE_BYTES = 500L * 1024 * 1024
    internal const val PER_THREAD_RENDER_BYTES = 150L * 1024 * 1024
    internal const val MIN_WORKER_THREADS = 1
    internal const val MAX_WORKER_THREADS = 12
    internal const val MIN_PULL_BATCH_SIZE = 2

    /**
     * `internal`/defaulted `maxHeapBytes` so a test can drive this with an arbitrary heap ceiling
     * without needing to actually launch a JVM at that size (see `JobConsumerDefaultThreadsTest`).
     * `Runtime.getRuntime().maxMemory()` already reflects whatever `-Xmx`/`-XX:MaxRAMPercentage`
     * `JAVA_TOOL_OPTIONS` resolved this JVM to — no separate "container memory limit" config needs
     * to be read here at all, so this can never drift from the actual heap ceiling the JVM itself
     * is enforcing.
     */
    internal fun defaultWorkerThreads(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int {
      // The interactive lane's threads render concurrently with the bulk ones, so their share of
      // the heap is reserved first and only the rest is available to bulk threads.
      val renderBudget = maxHeapBytes - BOOT_RESERVE_BYTES - JobLane.DEFAULT_INTERACTIVE_THREADS * PER_THREAD_RENDER_BYTES
      if (renderBudget <= 0) return MIN_WORKER_THREADS
      // Clamped in Long space BEFORE the narrowing `.toInt()`: an extreme (e.g. test-only,
      // near-Long.MAX_VALUE) heap ceiling divided by PER_THREAD_RENDER_BYTES can itself exceed
      // Int.MAX_VALUE, and `.toInt()` on that silently truncates/overflows instead of throwing —
      // coercing first means the value `.toInt()` ever sees is always already inside
      // [MIN_WORKER_THREADS, MAX_WORKER_THREADS], so the narrowing conversion itself is lossless.
      return (renderBudget / PER_THREAD_RENDER_BYTES)
        .coerceIn(MIN_WORKER_THREADS.toLong(), MAX_WORKER_THREADS.toLong())
        .toInt()
    }
  }
}
