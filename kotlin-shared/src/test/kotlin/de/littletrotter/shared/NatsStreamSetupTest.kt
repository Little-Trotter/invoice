package de.littletrotter.shared

import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.ConsumerInfo
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.api.StreamInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.Duration

class NatsStreamSetupTest {

    // ── configuration builders ──────────────────────────────────────────────────

    @Test
    fun `configuration builds a WorkQueue-retained file stream with the AP-M3 limits`() {
        val config = NatsStreamSetup.configuration(listOf("document-jobs", "document-job-results"))

        assertThat(config.name).isEqualTo(NatsStreamSetup.STREAM_NAME)
        assertThat(config.subjects).containsExactly("document-jobs", "document-job-results")
        assertThat(config.storageType).isEqualTo(StorageType.File)
        assertThat(config.retentionPolicy).isEqualTo(RetentionPolicy.WorkQueue)
        assertThat(config.maxAge).isEqualTo(Duration.ofDays(7))
        assertThat(config.maxBytes).isEqualTo(256L * 1024 * 1024)
        assertThat(config.discardPolicy).isEqualTo(DiscardPolicy.Old)
        assertThat(config.duplicateWindow).isEqualTo(Duration.ofMinutes(2))
    }

    @Test
    fun `dlqConfiguration builds a Limits-retained file stream with the AP-M3 DLQ limits`() {
        val config = NatsStreamSetup.dlqConfiguration(listOf("document-jobs-dlq", "document-job-results-dlq"))

        assertThat(config.name).isEqualTo(NatsStreamSetup.DLQ_STREAM_NAME)
        assertThat(config.subjects).containsExactly("document-jobs-dlq", "document-job-results-dlq")
        assertThat(config.storageType).isEqualTo(StorageType.File)
        assertThat(config.retentionPolicy).isEqualTo(RetentionPolicy.Limits)
        assertThat(config.maxAge).isEqualTo(Duration.ofDays(30))
        assertThat(config.maxBytes).isEqualTo(64L * 1024 * 1024)
        assertThat(config.discardPolicy).isEqualTo(DiscardPolicy.Old)
    }

    // ── ensureStream / ensureDlqStream — creation and race tolerance ───────────────

    @Test
    fun `ensureStream creates the stream when it does not exist yet`() {
        val jsm = mock<JetStreamManagement>()
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenThrow(IOException("not found"))
        whenever(jsm.addStream(any())).thenReturn(mock<StreamInfo>())

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs"))

        val order = inOrder(jsm)
        order.verify(jsm).getStreamInfo(NatsStreamSetup.STREAM_NAME)
        order.verify(jsm).addStream(any())
        verify(jsm, never()).updateStream(any())
    }

    @Test
    fun `ensureStream tolerates a concurrent creation by the other app`() {
        val jsm = mock<JetStreamManagement>()
        val info = mock<StreamInfo>()
        // first getStreamInfo: not found; addStream: race lost; second getStreamInfo: now found
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME))
            .thenThrow(IOException("not found"))
            .thenReturn(info)
        whenever(jsm.addStream(any())).thenThrow(IOException("already exists (race)"))

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs"))

        verify(jsm, times(2)).getStreamInfo(NatsStreamSetup.STREAM_NAME)
        verify(jsm).addStream(any())
    }

    @Test
    fun `ensureStream treats a JetStream API error on lookup as a missing stream and tolerates a lost creation race`() {
        val jsm = mock<JetStreamManagement>()
        val info = mock<StreamInfo>()
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME))
            .thenThrow(mock<JetStreamApiException>())
            .thenReturn(info)
        whenever(jsm.addStream(any())).thenThrow(mock<JetStreamApiException>())

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs"))

        verify(jsm, times(2)).getStreamInfo(NatsStreamSetup.STREAM_NAME)
        verify(jsm).addStream(any())
        verify(jsm, never()).updateStream(any())
    }

    @Test
    fun `ensureDlqStream creates the DLQ stream when it does not exist yet`() {
        val jsm = mock<JetStreamManagement>()
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenThrow(IOException("not found"))
        whenever(jsm.addStream(any())).thenReturn(mock<StreamInfo>())

        NatsStreamSetup.ensureDlqStream(jsm, listOf("document-jobs-dlq", "document-job-results-dlq"))

        verify(jsm).addStream(any())
    }

    // ── drift reconciliation — reached once getStreamInfo returns an existing config ───────────

    @Test
    fun `ensureStream leaves a matching stream untouched`() {
        val jsm = mock<JetStreamManagement>()
        val desired = NatsStreamSetup.configuration(listOf("document-jobs", "document-job-results"))
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(desired)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs", "document-job-results"))

        verify(jsm, never()).addStream(any())
        verify(jsm, never()).updateStream(any())
    }

    @Test
    fun `ensureStream updates a drifted stream's limits`() {
        val jsm = mock<JetStreamManagement>()
        val stale = StreamConfiguration.builder()
            .name(NatsStreamSetup.STREAM_NAME)
            .subjects(listOf("document-jobs", "document-job-results"))
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.WorkQueue)
            .maxAge(Duration.ofDays(1))
            .maxBytes(1024)
            .discardPolicy(DiscardPolicy.New)
            .duplicateWindow(Duration.ZERO)
            .build()
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(stale)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs", "document-job-results"))

        val captor = org.mockito.kotlin.argumentCaptor<StreamConfiguration>()
        verify(jsm).updateStream(captor.capture())
        assertThat(captor.firstValue.maxAge).isEqualTo(Duration.ofDays(7))
        assertThat(captor.firstValue.maxBytes).isEqualTo(256L * 1024 * 1024)
        assertThat(captor.firstValue.duplicateWindow).isEqualTo(Duration.ofMinutes(2))
        verify(jsm, never()).addStream(any())
    }

    @Test
    fun `ensureStream updates a stream whose subjects drifted`() {
        val jsm = mock<JetStreamManagement>()
        val stale = NatsStreamSetup.configuration(listOf("document-jobs"))
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(stale)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs", "document-job-results"))

        verify(jsm).updateStream(any())
    }

    @Test
    fun `ensureStream logs ERROR and does not update when the retention policy itself drifted`() {
        val jsm = mock<JetStreamManagement>()
        val staleRetention = StreamConfiguration.builder()
            .name(NatsStreamSetup.STREAM_NAME)
            .subjects(listOf("document-jobs", "document-job-results"))
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits) // desired is WorkQueue
            .maxAge(Duration.ofDays(7))
            .maxBytes(256L * 1024 * 1024)
            .discardPolicy(DiscardPolicy.Old)
            .duplicateWindow(Duration.ofMinutes(2))
            .build()
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(staleRetention)
        whenever(jsm.getStreamInfo(NatsStreamSetup.STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureStream(jsm, listOf("document-jobs", "document-job-results"))

        verify(jsm, never()).updateStream(any())
        verify(jsm, never()).addStream(any())
    }

    @Test
    fun `ensureDlqStream leaves a matching DLQ stream untouched`() {
        val jsm = mock<JetStreamManagement>()
        val desired = NatsStreamSetup.dlqConfiguration(listOf("document-jobs-dlq", "document-job-results-dlq"))
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(desired)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureDlqStream(jsm, listOf("document-jobs-dlq", "document-job-results-dlq"))

        verify(jsm, never()).addStream(any())
        verify(jsm, never()).updateStream(any())
    }

    @Test
    fun `ensureDlqStream does not update every start just because the server normalized the unset duplicateWindow to its own default`() {
        // dlqConfiguration() deliberately never calls .duplicateWindow(...), so the desired config's
        // value is the client builder's own default, Duration.ZERO ("use the server default", per
        // StreamConfiguration.getDuplicateWindow's KDoc) — but nats-server reports back its actual
        // default (2 minutes) on every getStreamInfo, once the stream exists. Comparing those two
        // raw values as literal drift would call updateStream on every single startup.
        val jsm = mock<JetStreamManagement>()
        val subjects = listOf("document-jobs-dlq", "document-job-results-dlq")
        val serverReported = StreamConfiguration.builder()
            .name(NatsStreamSetup.DLQ_STREAM_NAME)
            .subjects(subjects)
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits)
            .maxAge(Duration.ofDays(30))
            .maxBytes(64L * 1024 * 1024)
            .discardPolicy(DiscardPolicy.Old)
            .duplicateWindow(Duration.ofMinutes(2))
            .build()
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(serverReported)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureDlqStream(jsm, subjects)

        verify(jsm, never()).updateStream(any())
        verify(jsm, never()).addStream(any())
    }

    @Test
    fun `ensureDlqStream treats a defensively-null existing duplicateWindow the same as the server's zero-normalized default`() {
        // The nats client's own getDuplicateWindow() never returns null in practice (see
        // normalizedDuplicateWindow's KDoc), but the null branch still exists defensively and must
        // be exercised: a mocked existing config lets duplicateWindow be null directly, rather than
        // relying on Duration.ZERO (the builder's actual unset value, covered by the sibling test
        // above).
        val jsm = mock<JetStreamManagement>()
        val subjects = listOf("document-jobs-dlq", "document-job-results-dlq")
        val existing = mock<StreamConfiguration>()
        whenever(existing.name).thenReturn(NatsStreamSetup.DLQ_STREAM_NAME)
        whenever(existing.subjects).thenReturn(subjects)
        whenever(existing.retentionPolicy).thenReturn(RetentionPolicy.Limits)
        whenever(existing.maxAge).thenReturn(Duration.ofDays(30))
        whenever(existing.maxBytes).thenReturn(64L * 1024 * 1024)
        whenever(existing.discardPolicy).thenReturn(DiscardPolicy.Old)
        whenever(existing.duplicateWindow).thenReturn(null)
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(existing)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureDlqStream(jsm, subjects)

        verify(jsm, never()).updateStream(any())
        verify(jsm, never()).addStream(any())
    }

    @Test
    fun `ensureDlqStream updates a drifted DLQ stream`() {
        val jsm = mock<JetStreamManagement>()
        val stale = NatsStreamSetup.dlqConfiguration(listOf("document-jobs-dlq"))
        val info = mock<StreamInfo>()
        whenever(info.configuration).thenReturn(stale)
        whenever(jsm.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME)).thenReturn(info)

        NatsStreamSetup.ensureDlqStream(jsm, listOf("document-jobs-dlq", "document-job-results-dlq"))

        verify(jsm).updateStream(any())
    }

    // ── AP-M4 — consumerConfiguration / ensureConsumer ──────────────────────────

    @Test
    fun `consumerConfiguration sets durable, filterSubject and maxAckPending and leaves ackWait unset when omitted`() {
        val config = NatsStreamSetup.consumerConfiguration(
            durableName = "little-trotter-invoice-result-consumer",
            filterSubject = "document-job-results",
            maxAckPending = 16,
        )

        assertThat(config.durable).isEqualTo("little-trotter-invoice-result-consumer")
        assertThat(config.filterSubject).isEqualTo("document-job-results")
        assertThat(config.maxAckPending).isEqualTo(16L)
        assertThat(config.ackWait).isNull()
    }

    @Test
    fun `consumerConfiguration sets ackWait when provided`() {
        val config = NatsStreamSetup.consumerConfiguration(
            durableName = "kotlin-xpdf-group",
            filterSubject = "document-jobs",
            maxAckPending = 8,
            ackWaitSeconds = 120,
        )

        assertThat(config.durable).isEqualTo("kotlin-xpdf-group")
        assertThat(config.filterSubject).isEqualTo("document-jobs")
        assertThat(config.maxAckPending).isEqualTo(8L)
        assertThat(config.ackWait).isEqualTo(Duration.ofSeconds(120))
    }

    @Test
    fun `ensureConsumer delegates to addOrUpdateConsumer with the given stream and config`() {
        val jsm = mock<JetStreamManagement>()
        val info = mock<ConsumerInfo>()
        val config = NatsStreamSetup.consumerConfiguration(
            durableName = "kotlin-xpdf-group",
            filterSubject = "document-jobs",
            maxAckPending = 8,
            ackWaitSeconds = 120,
        )
        whenever(jsm.addOrUpdateConsumer(eq(NatsStreamSetup.STREAM_NAME), any())).thenReturn(info)

        val result = NatsStreamSetup.ensureConsumer(jsm, NatsStreamSetup.STREAM_NAME, config)

        verify(jsm).addOrUpdateConsumer(NatsStreamSetup.STREAM_NAME, config)
        assertThat(result).isSameAs(info)
    }
}
