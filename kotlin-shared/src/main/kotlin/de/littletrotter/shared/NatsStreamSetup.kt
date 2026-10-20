package de.littletrotter.shared

import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.ConsumerInfo
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration

/**
 * Single source of truth for the two JetStream streams shared by little-trotter-invoice and
 * kotlin-xpdf. Stream names, covered subjects, storage type, retention and limits are a *shared
 * contract* between the two apps. Both race to create each stream at startup; an [JetStreamManagement.addStream]
 * failure is treated as "another instance already created it" as long as [JetStreamManagement.getStreamInfo] then
 * succeeds. If the two apps ever drifted on this definition, the first one to create the stream
 * would win silently — keeping it here prevents that, and [ensureStream]/[ensureDlqStream] now
 * also reconcile drift on every startup, not just on first creation (AP-M3).
 */
object NatsStreamSetup {

    private val log = LoggerFactory.getLogger(NatsStreamSetup::class.java)

    const val STREAM_NAME = "LITTLE_TROTTER_JOBS"
    const val DLQ_STREAM_NAME = "LITTLE_TROTTER_DLQ"

    /**
     * The result subject of the offer service's document jobs. The offer service publishes its
     * jobs on the shared jobs subject but receives its results on this one, so the work-queue
     * stream must cover it; the document job contract pins that both names stay identical.
     */
    const val OFFER_RESULTS_SUBJECT = "quote-document-job-results"

    /**
     * Subjects of the asynchronous PDF conformance check: the request travels invoice to xpdf, the
     * result back. Mirrors the names in the document job contract, which pins that both stay
     * identical.
     */
    const val PDF_CHECK_SUBJECT = "pdf-checks"
    const val PDF_CHECK_RESULT_SUBJECT = "pdf-check-results"
    const val PDF_CHECK_DLQ_SUBJECT = "pdf-checks-dlq"

    private val MAIN_MAX_AGE: Duration = Duration.ofDays(7)
    private const val MAIN_MAX_BYTES: Long = 256L * 1024 * 1024
    private val MAIN_DUPLICATE_WINDOW: Duration = Duration.ofMinutes(2)

    private val DLQ_MAX_AGE: Duration = Duration.ofDays(30)
    private const val DLQ_MAX_BYTES: Long = 64L * 1024 * 1024

    /**
     * `StreamConfiguration.Builder.duplicateWindow` defaults to [Duration.ZERO] when a caller
     * (like [dlqConfiguration], deliberately) never calls `.duplicateWindow(...)` — per its own
     * KDoc, `Duration.ZERO` means "duplicate checking is not enabled", i.e. "use the server's
     * default", not literally zero. The nats-server itself normalizes that `0` in the create/update
     * request to its own default window, [NATS_SERVER_DEFAULT_DUPLICATE_WINDOW], and reports that
     * value back in every subsequent `getStreamInfo`. Comparing the raw values would therefore see
     * `desired = PT0S` vs. `existing = PT2M` as drift forever and call `updateStream` on every
     * startup even though nothing ever changed — [normalizedDuplicateWindow] maps both sides onto
     * the same "unset means the server default" value before [snapshot] compares them.
     */
    private val NATS_SERVER_DEFAULT_DUPLICATE_WINDOW: Duration = Duration.ofMinutes(2)

    /**
     * Builds the main WorkQueue stream configuration. Subjects are passed explicitly because they
     * come from each app's configuration, but the name, storage, retention and limits live here.
     * `duplicateWindow` backs AP-M2's `Nats-Msg-Id` publish dedup — this AP only wires the stream
     * setting; the header itself is AP-M2's territory (DocumentJobPublisher/ResultPublisher).
     */
    fun configuration(subjects: List<String>): StreamConfiguration =
        StreamConfiguration.builder()
            .name(STREAM_NAME)
            .subjects(subjects)
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.WorkQueue)
            .maxAge(MAIN_MAX_AGE)
            .maxBytes(MAIN_MAX_BYTES)
            .discardPolicy(DiscardPolicy.Old)
            .duplicateWindow(MAIN_DUPLICATE_WINDOW)
            .build()

    /**
     * Builds the DLQ stream configuration. Limits retention (not WorkQueue) — reading the DLQ for
     * SystemView visibility must not consume it. No duplicate window: poison messages are
     * distinguishable by content/headers, not by an idempotency key a producer sets.
     */
    fun dlqConfiguration(subjects: List<String>): StreamConfiguration =
        StreamConfiguration.builder()
            .name(DLQ_STREAM_NAME)
            .subjects(subjects)
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits)
            .maxAge(DLQ_MAX_AGE)
            .maxBytes(DLQ_MAX_BYTES)
            .discardPolicy(DiscardPolicy.Old)
            .build()

    /** Ensures the main stream exists (and matches [configuration]), tolerating a concurrent
     *  creation by the other app and reconciling any config drift. */
    fun ensureStream(jsm: JetStreamManagement, subjects: List<String>) {
        ensure(jsm, STREAM_NAME, configuration(subjects))
    }

    /** Same as [ensureStream], for the DLQ stream. */
    fun ensureDlqStream(jsm: JetStreamManagement, subjects: List<String>) {
        ensure(jsm, DLQ_STREAM_NAME, dlqConfiguration(subjects))
    }

    /**
     * AP-M4 §2/F8 — builds a durable pull-consumer config. [filterSubject] is required: the main
     * stream ([STREAM_NAME]) carries both apps' subjects (jobs and results) multiplexed together
     * (see [configuration]'s `subjects` list), so without a filter this durable would receive the
     * *other* app's messages too — the old push consumer got this filtering for free from the
     * `subject` argument of `JetStream.subscribe(subject, ...)`, which the pull API has no
     * equivalent of; it has to live in the consumer config itself instead.
     *
     * [ackWaitSeconds] is optional: only `kotlin-xpdf`'s job consumer configures it explicitly
     * (AP-M2's heartbeat-backed window); `little-trotter-invoice`'s result consumer has no
     * long-running heartbeat and keeps the server's own default ack-wait, exactly as it did before
     * this AP (its old `PushSubscribeOptions` never set one either).
     */
    fun consumerConfiguration(
        durableName: String,
        filterSubject: String,
        maxAckPending: Int,
        ackWaitSeconds: Long? = null,
        maxDeliver: Int? = null,
    ): ConsumerConfiguration {
        val builder = ConsumerConfiguration.builder()
            .durable(durableName)
            .filterSubject(filterSubject)
            .maxAckPending(maxAckPending.toLong())
        if (ackWaitSeconds != null) {
            builder.ackWait(Duration.ofSeconds(ackWaitSeconds))
        }
        if (maxDeliver != null) {
            builder.maxDeliver(maxDeliver.toLong())
        }
        return builder.build()
    }

    /**
     * AP-M4 §2 — creates/updates a durable consumer, next to [ensureStream]/[ensureDlqStream].
     * Unlike streams — `addStream` fails outright on an existing stream, so `ensureStream`'s own
     * [reconcile] must fetch, compare and explicitly `updateStream` on drift — JetStream's own
     * `addOrUpdateConsumer` already performs a server-side create-or-update
     * (`ConsumerCreateRequest.Action.CreateOrUpdate`): one call, every startup, both creates a
     * missing durable and reconciles `ackWait`/`maxAckPending` drift on an existing one. No
     * client-side fetch+compare is needed here the way it is for streams. This is what replaces
     * the implicit consumer creation `PushSubscribeOptions` used to bury.
     */
    fun ensureConsumer(jsm: JetStreamManagement, streamName: String, config: ConsumerConfiguration): ConsumerInfo {
        log.info(
            "Ensuring durable consumer [{}] on stream [{}] (ackWait={}, maxAckPending={})",
            config.durable, streamName, config.ackWait, config.maxAckPending,
        )
        return jsm.addOrUpdateConsumer(streamName, config)
    }

    private fun ensure(jsm: JetStreamManagement, name: String, desired: StreamConfiguration) {
        val existing = try {
            jsm.getStreamInfo(name).configuration
        } catch (e: IOException) {
            log.debug("Stream [{}] not reachable yet, assuming it does not exist: {}", name, e.message)
            null
        } catch (e: JetStreamApiException) {
            log.debug("Stream [{}] not found (or not queryable), assuming it does not exist: {}", name, e.message)
            null
        }

        if (existing == null) {
            try {
                jsm.addStream(desired)
            } catch (raceEx: IOException) {
                // The other app instance (e.g. kotlin-xpdf / little-trotter-invoice) may have
                // created it concurrently at startup.
                log.debug("addStream [{}] unreachable, retrying via getStreamInfo: {}", name, raceEx.message)
                jsm.getStreamInfo(name)
            } catch (raceEx: JetStreamApiException) {
                log.debug("addStream [{}] rejected by the server, retrying via getStreamInfo: {}", name, raceEx.message)
                jsm.getStreamInfo(name)
            }
            return
        }

        reconcile(jsm, name, existing, desired)
    }

    /**
     * AP-M3 §3 — compares the live stream config against the desired one and updates on drift,
     * logging what changed at INFO. Retention policy cannot be changed on an existing stream (the
     * NATS server rejects it); that case is logged at ERROR with a manual-fix instruction instead
     * of throwing — a control-plane quirk here must not fail app startup.
     *
     * The subjects/limits/duplicateWindow comparison is done via a single [DriftSnapshot] equality
     * check rather than a chain of `||`-combined field comparisons: same drift semantics (any
     * covered field differing counts as drift), but as one boolean branch instead of several,
     * which keeps the 100 %-branch-coverage gate on this class reachable with a handful of cases
     * instead of exhaustively enumerating every combination of fields that could differ.
     */
    private fun reconcile(
        jsm: JetStreamManagement,
        name: String,
        existing: StreamConfiguration,
        desired: StreamConfiguration,
    ) {
        if (existing.retentionPolicy != desired.retentionPolicy) {
            log.error(
                "Stream [{}] retention policy is {} but should be {} — this cannot be changed on an " +
                    "existing stream. Delete it manually (e.g. `nats stream rm {}`) and restart both " +
                    "apps so it is recreated with the correct policy.",
                name, existing.retentionPolicy, desired.retentionPolicy, name,
            )
            return
        }

        if (snapshot(existing) == snapshot(desired)) {
            return
        }

        log.info(
            "Stream [{}] config drifted from the desired contract — updating live stream " +
                "(subjects {} -> {}, maxAge {} -> {}, maxBytes {} -> {}, discardPolicy {} -> {}, " +
                "duplicateWindow {} -> {})",
            name, existing.subjects, desired.subjects, existing.maxAge, desired.maxAge,
            existing.maxBytes, desired.maxBytes, existing.discardPolicy, desired.discardPolicy,
            existing.duplicateWindow, desired.duplicateWindow,
        )
        jsm.updateStream(desired)
    }

    private data class DriftSnapshot(
        val subjects: Set<String>,
        val maxAge: Duration?,
        val maxBytes: Long,
        val discardPolicy: DiscardPolicy?,
        val duplicateWindow: Duration?,
    )

    private fun snapshot(config: StreamConfiguration) = DriftSnapshot(
        subjects = config.subjects.toSet(),
        maxAge = config.maxAge,
        maxBytes = config.maxBytes,
        discardPolicy = config.discardPolicy,
        duplicateWindow = normalizedDuplicateWindow(config.duplicateWindow),
    )

    /** Zero/unset (`null` defensively, though the client never returns that) both mean "server
     *  default" — see [NATS_SERVER_DEFAULT_DUPLICATE_WINDOW]'s KDoc. */
    private fun normalizedDuplicateWindow(duplicateWindow: Duration?): Duration =
        if (duplicateWindow == null || duplicateWindow.isZero) {
            NATS_SERVER_DEFAULT_DUPLICATE_WINDOW
        } else {
            duplicateWindow
        }
}
