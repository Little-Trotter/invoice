package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.shared.masterdata.MasterdataStreams
import de.littletrotter.shared.masterdata.MasterdataSubjects
import io.nats.client.JetStreamManagement
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import java.time.Duration

/**
 * Creates the `MASTERDATA` JetStream stream.
 *
 * Limits retention: a replica keeps its own position and a new one reads the retained history, or
 * asks for a snapshot once the history has aged out. The one-hour duplicate window is far longer
 * than an outbox retry cycle, so a publish repeated after a crash is dropped by the stream.
 * Only the host that feeds the stream creates it, and only when a consuming module is enabled.
 */
object MasterdataStreamSetup {

    val MAX_AGE: Duration = MasterdataStreams.MAX_AGE
    const val MAX_BYTES: Long = MasterdataStreams.MAX_BYTES
    val DUPLICATE_WINDOW: Duration = MasterdataStreams.DUPLICATE_WINDOW

    fun configuration(): StreamConfiguration =
        StreamConfiguration.builder()
            .name(MasterdataStreams.MASTERDATA)
            .subjects(MasterdataSubjects.ALL)
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits)
            .maxAge(MAX_AGE)
            .maxBytes(MAX_BYTES)
            .discardPolicy(DiscardPolicy.Old)
            .duplicateWindow(DUPLICATE_WINDOW)
            .build()

    /** Creates the stream, or aligns an existing one with [configuration] (idempotent). */
    fun ensure(jsm: JetStreamManagement) {
        if (jsm.streamNames.contains(MasterdataStreams.MASTERDATA)) {
            jsm.updateStream(configuration())
        } else {
            jsm.addStream(configuration())
        }
    }
}
