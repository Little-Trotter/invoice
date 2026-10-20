package de.littletrotter.xpdf.infrastructure.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * AP-M2 — "Publishing the same
 * jobId twice within 2 minutes results in one message in the stream ..., and the second
 * PublishAck reports duplicate == true."
 *
 * [ResultPublisher.publishResult] now sets `PublishOptions.messageId("result-$jobId-$status")`
 * (AP-M2 §4). This IT proves the dedup end to end against a real JetStream broker
 * (Testcontainers) using JetStream's own client-default duplicate window (the stream config here,
 * `NatsTestContainersExtension`'s `TEST_STREAM`, does not set `duplicateWindow` explicitly, so the
 * broker applies its own default — measured empirically to comfortably cover two publishes made
 * back-to-back in a test). AP-M3 (owned separately, not touched here — see [NatsStreamSetup]'s
 * own KDoc) is expected to set `duplicateWindow = 2 min` explicitly on the *production* stream;
 * this test does not depend
 * on that value, only on a dedup window existing at all.
 */
@ExtendWith(NatsTestContainersExtension::class)
class ResultPublisherDedupIT {

    @Test
    fun `publishing the same jobId and status twice is deduped to a single stream message`() {
        val jobId = "dedup-${UUID.randomUUID()}"
        val publisher = ResultPublisher(NatsTestContainersExtension.jetStream())

        val firstAck = publisher.publishResult(jobId, "COMPLETED", mapOf("objectKey" to "invoices/$jobId.pdf"), "kosit-ok", "corr-1")
        val secondAck = publisher.publishResult(jobId, "COMPLETED", mapOf("objectKey" to "invoices/$jobId.pdf"), "kosit-ok", "corr-1")

        assertFalse(firstAck.isDuplicate, "the first publish of a fresh Nats-Msg-Id must not be reported as a duplicate")
        assertTrue(secondAck.isDuplicate, "the second publish with the same Nats-Msg-Id must be reported as a duplicate")
        // Both PublishAcks reference the SAME stored stream sequence: proof that only one message
        // was ever actually appended to the stream, not two.
        assertEquals(firstAck.seqno, secondAck.seqno)
    }

    @Test
    fun `a different status for the same jobId is not deduped - it is a distinct Nats-Msg-Id`() {
        val jobId = "dedup-status-${UUID.randomUUID()}"
        val publisher = ResultPublisher(NatsTestContainersExtension.jetStream())

        val completedAck = publisher.publishResult(jobId, "COMPLETED", emptyMap(), "", null)
        val failedAck = publisher.publishResult(jobId, "FAILED", mapOf("error" to "boom"), "", null)

        assertFalse(completedAck.isDuplicate)
        assertFalse(failedAck.isDuplicate, "\"result-\$jobId-FAILED\" is a different Nats-Msg-Id than \"result-\$jobId-COMPLETED\" - both must land in the stream")
        assertTrue(failedAck.seqno > completedAck.seqno, "the second, non-duplicate publish must have advanced the stream sequence")
    }
}
