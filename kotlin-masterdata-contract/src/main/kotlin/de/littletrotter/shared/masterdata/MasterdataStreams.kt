package de.littletrotter.shared.masterdata

import java.time.Duration

/**
 * The JetStream stream of the master data replication.
 *
 * Owner: kotlin-invoice, the only writer of master data; it creates the stream, aligns an existing
 * one with the shape below (update), and does so only when a consuming module is enabled, so an
 * instance without one carries no empty stream. A replica may create the stream with the identical
 * shape, and only when it is missing, so start order does not matter; it never alters an existing
 * one. Limits retention: every consumer keeps its own position and a restarted or new replica
 * reads the history again, or asks for a snapshot when the history is gone.
 */
object MasterdataStreams {

    const val MASTERDATA: String = "MASTERDATA"

    /** History kept for replicas that start late; older state is rebuilt through a snapshot. */
    val MAX_AGE: Duration = Duration.ofDays(30)

    const val MAX_BYTES: Long = 256L * 1024 * 1024

    /** Longer than an outbox retry cycle, so a publish repeated after a crash is dropped by the stream. */
    val DUPLICATE_WINDOW: Duration = Duration.ofHours(1)
}
