package de.littletrotter.xpdf.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * AP-M1 — [ResultPublisher] no longer opens its own
 * NATS connection (its no-arg constructor and `natsUrl()` are gone) and `publishResult` is now a
 * blocking call with bounded retry instead of `publishAsync`. This file replaces the previous
 * `CompletableFuture`-based tests and the `natsUrl()`/default-constructor coverage that used to
 * live here and in `ResultPublisherDefaultConstructorIT` (deleted — nothing left to cover: the
 * class no longer has a code path that opens a connection itself).
 */
private fun Any?.asStringKeyedMap(): Map<String, Any?>? {
    if (this !is Map<*, *>) return null
    return entries.associate { (key, value) -> key.toString() to value }
}

class ResultPublisherTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `publishResult sends expected message and returns the PublishAck on the first try`() {
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(ack)

        val pub = ResultPublisher(jetStream)
        val resultRefs = mapOf("pdf" to "invoices/123.pdf", "xml" to "invoices/123.xml", "kosit" to "invoices/123.kosit.json")
        val returned = pub.publishResult("job-1", "COMPLETED", resultRefs, "kosit-report", "corr-1")

        assertSame(ack, returned)
        val subjectCaptor = argumentCaptor<String>()
        val headersCaptor = argumentCaptor<Headers>()
        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream, times(1)).publish(subjectCaptor.capture(), headersCaptor.capture(), bodyCaptor.capture(), any<PublishOptions>())

        assertEquals("document-job-results", subjectCaptor.firstValue)
        val payload: Map<String, Any?> = mapper.readValue(bodyCaptor.firstValue)
        assertEquals("job-1", payload["id"])
        assertEquals("COMPLETED", payload["status"])
        // AP-M6 — the report no longer travels inline; only a {key} reference does.
        val ref = payload["kositReportRef"].asStringKeyedMap()
        assertEquals("lt:kosit:job-1", ref?.get("key"))
        assertEquals("corr-1", payload["correlationId"])
        assertEquals(1, headersCaptor.firstValue.get("correlationId")?.size ?: 0)
    }

    @Test
    fun `publishResult uses the job's own result subject when one is given`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        ResultPublisher(jetStream).publishResult("job-q", "COMPLETED", null, "", "corr-q", "quote-job-results")

        val subjectCaptor = argumentCaptor<String>()
        verify(jetStream).publish(subjectCaptor.capture(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertEquals("quote-job-results", subjectCaptor.firstValue)
    }

    @Test
    fun `publishResult keeps the configured subject when the job names none`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        ResultPublisher(jetStream, "configured-results").publishResult("job-c", "COMPLETED", null, "", null, null)

        val subjectCaptor = argumentCaptor<String>()
        verify(jetStream).publish(subjectCaptor.capture(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertEquals("configured-results", subjectCaptor.firstValue)
    }

    @Test
    fun `publishResult omits correlation header when not provided`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        val pub = ResultPublisher(jetStream)
        pub.publishResult("job-2", "FAILED", mapOf("error" to "boom"), "", null)

        val headersCaptor = argumentCaptor<Headers>()
        verify(jetStream, times(1)).publish(any<String>(), headersCaptor.capture(), any<ByteArray>(), any<PublishOptions>())
        assertEquals(0, headersCaptor.firstValue.get("correlationId")?.size ?: 0)
    }

    @Test
    fun `publishResult never includes llmReport in payload`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        val pub = ResultPublisher(jetStream)
        pub.publishResult("job-3", "COMPLETED", mapOf("pdf" to "s3://bucket/3.pdf"), "kosit-report", "corr-3")

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream, times(1)).publish(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val payload: Map<String, Any?> = mapper.readValue(bodyCaptor.firstValue)
        assertNull(payload["llmReport"])
    }

    @Test
    fun `publishResult uses the default empty kositReport when the caller omits it`() {
        // Kotlin compiles the default-valued kositReport parameter into a synthetic
        // publishResult$default bridge, only reachable by a caller that omits the argument —
        // JobConsumer always passes it explicitly, so a direct call here is the only way to
        // exercise that generated bridge.
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        val pub = ResultPublisher(jetStream)
        pub.publishResult(jobId = "job-default-kosit", status = "COMPLETED", resultRefs = emptyMap(), correlationId = "corr")

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publish(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val payload: Map<String, Any?> = mapper.readValue(bodyCaptor.firstValue)
        // AP-M6 — a blank report (the default) never gets a ref at all: nothing to store,
        // nothing to reference.
        assertNull(payload["kositReportRef"])
    }

    // ── AP-M6 — KoSIT report offload via PayloadExchange ──────────────────────────────────

    @Test
    fun `a non-blank kositReport is stored in PayloadExchange under lt-kosit-jobId with a 1h TTL`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()

        val pub = ResultPublisher(jetStream, "document-job-results", payloadExchange)
        pub.publishResult("job-kosit", "COMPLETED", emptyMap(), "<xml>the report</xml>", "corr-kosit")

        verify(payloadExchange).put("lt:kosit:job-kosit", "<xml>the report</xml>", 3_600L)
    }

    @Test
    fun `a blank kositReport never touches PayloadExchange`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()

        val pub = ResultPublisher(jetStream, "document-job-results", payloadExchange)
        pub.publishResult("job-no-kosit", "COMPLETED", emptyMap(), "", "corr")

        verify(payloadExchange, org.mockito.kotlin.never()).put(any<String>(), any<String>(), any<Long>())
    }

    @Test
    fun `a null PayloadExchange (test wiring) still builds a ref, it just never lands anywhere`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        // 2-arg constructor -> payloadExchange defaults to null.
        val pub = ResultPublisher(jetStream, "document-job-results")
        pub.publishResult("job-null-exchange", "COMPLETED", emptyMap(), "some report", null)

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publish(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val payload: Map<String, Any?> = mapper.readValue(bodyCaptor.firstValue)
        val ref = payload["kositReportRef"].asStringKeyedMap()
        assertEquals("lt:kosit:job-null-exchange", ref?.get("key"))
    }

    @Test
    fun `the 3-arg constructor with a real PayloadExchange uses the default results subject`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())
        val payloadExchange: de.littletrotter.shared.PayloadExchange = mock()

        val pub = ResultPublisher(jetStream, payloadExchange)
        pub.publishResult("job-3arg-default-subject", "COMPLETED", emptyMap(), "", null)

        verify(jetStream).publish(eq("document-job-results"), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `the results subject comes from the injected config value, not a hardcoded literal`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(mock<PublishAck>())

        val pub = ResultPublisher(jetStream, "custom-results-subject")
        pub.publishResult("job-subject", "COMPLETED", emptyMap(), "", null)

        verify(jetStream).publish(eq("custom-results-subject"), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `a transient publish failure is retried and succeeds on the second attempt`() {
        val jetStream: JetStream = mock()
        val ack: PublishAck = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenThrow(IOException("nats down"))
            .thenReturn(ack)

        val pub = ResultPublisher(jetStream)
        val started = System.nanoTime()
        val returned = pub.publishResult("job-retry-ok", "COMPLETED", emptyMap(), "", null)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertSame(ack, returned)
        verify(jetStream, times(2)).publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertTrue(elapsedMs >= 250, "must have backed off ~250ms before the second attempt, was ${elapsedMs}ms")
    }

    @Test
    fun `publishResult throws after exhausting all attempts and never sleeps after the last one`() {
        val jetStream: JetStream = mock()
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenThrow(IOException("attempt 1"))
            .thenThrow(IOException("attempt 2"))
            .thenThrow(IOException("attempt 3 - final"))

        val pub = ResultPublisher(jetStream)
        val started = System.nanoTime()
        val thrown = assertThrows(IOException::class.java) {
            pub.publishResult("job-retry-exhausted", "FAILED", mapOf("error" to "boom"), "", null)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("attempt 3 - final", thrown.message)
        verify(jetStream, times(3)).publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        // two backoffs (250ms + 1000ms), no third sleep after the final attempt fails
        assertTrue(elapsedMs in 1_250..4_000, "expected ~1.25s of backoff, was ${elapsedMs}ms")
    }

    @Test
    fun `an interrupted backoff sleep restores the interrupt flag and rethrows the original failure`() {
        val jetStream: JetStream = mock()
        val originalFailure = IOException("nats down")
        whenever(jetStream.publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenThrow(originalFailure)

        val pub = ResultPublisher(jetStream)
        Thread.currentThread().interrupt()
        try {
            val thrown = assertThrows(IOException::class.java) {
                pub.publishResult("job-interrupted", "FAILED", mapOf("error" to "boom"), "", null)
            }
            assertSame(originalFailure, thrown)
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored, not swallowed")
        }
        // only the first attempt runs: the backoff sleep before a second attempt throws
        // InterruptedException immediately because the thread was already interrupted.
        verify(jetStream, times(1)).publish(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }
}
