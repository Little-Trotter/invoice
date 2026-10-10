package de.littletrotter.infrastructure.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import redis.clients.jedis.RedisClient
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.params.SetParams

/**
 * Unit tests for [ValkeyInvoiceCacheClient] with both [RedisClient]s mocked via the
 * constructor's test seam (`injectedInvoiceClient`/`injectedQueryClient`).
 * The real Valkey round trip is covered by `ValkeyInvoiceCacheClientIT`.
 */
class ValkeyInvoiceCacheClientTest {

    private lateinit var invoiceClient: RedisClient
    private lateinit var queryClient: RedisClient
    private lateinit var client: ValkeyInvoiceCacheClient

    @BeforeEach
    fun setup() {
        invoiceClient = mock()
        queryClient = mock()
        client = ValkeyInvoiceCacheClient(
            valkeyHost = "unused",
            valkeyPort = 0,
            valkeyPassword = "",
            invoiceDb = 1,
            queryDb = 2,
            injectedInvoiceClient = invoiceClient,
            injectedQueryClient = queryClient,
        )
        client.init()
    }

    // ── overview cache (query db) ────────────────────────────────────────────

    @Test
    fun `happy path - overview cache writes with the configured ttl and reads it back on a hit`() {
        whenever(queryClient.get("ov:1")).thenReturn("""{"a":1}""")

        client.cacheOverview("ov:1", """{"a":1}""", 30)
        val hit = client.getCachedOverview("ov:1")

        verify(queryClient).set(eq("ov:1"), eq("""{"a":1}"""), any<SetParams>())
        assertThat(hit).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `boundary - overview cache miss returns null`() {
        whenever(queryClient.get("ov:missing")).thenReturn(null)

        assertThat(client.getCachedOverview("ov:missing")).isNull()
    }

    @Test
    fun `boundary - overview cache read failure degrades to null, not an exception`() {
        whenever(queryClient.get(any<String>())).thenThrow(JedisException("down"))

        assertThat(client.getCachedOverview("ov:1")).isNull()
    }

    @Test
    fun `boundary - overview cache write failure is silently dropped`() {
        whenever(queryClient.set(any<String>(), any<String>(), any<SetParams>())).thenThrow(JedisException("down"))

        client.cacheOverview("ov:1", "{}", 30)
    }

    @Test
    fun `evictOverview deletes the key`() {
        client.evictOverview("ov:1")

        verify(queryClient).del("ov:1")
    }

    @Test
    fun `boundary - evictOverview failure is silently dropped`() {
        whenever(queryClient.del(any<String>())).thenThrow(JedisException("down"))

        client.evictOverview("ov:1")
    }

    @Test
    fun `evictAllOverview flushes the query db`() {
        client.evictAllOverview()

        verify(queryClient).flushDB()
    }

    @Test
    fun `boundary - evictAllOverview failure is silently dropped`() {
        whenever(queryClient.flushDB()).thenThrow(JedisException("down"))

        client.evictAllOverview()
    }

    // ── pdf cache (invoice db) ────────────────────────────────────────────────

    @Test
    fun `happy path - pdf cache writes and reads bytes back on a hit`() {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        whenever(invoiceClient.get("pdf:42".toByteArray())).thenReturn(pdf)

        client.cachePdf(42L, pdf, 900)
        val hit = client.getCachedPdf(42L)

        verify(invoiceClient).set(eq("pdf:42".toByteArray()), eq(pdf), any<SetParams>())
        assertThat(hit).isEqualTo(pdf)
    }

    @Test
    fun `boundary - pdf cache miss returns null`() {
        whenever(invoiceClient.get(any<ByteArray>())).thenReturn(null)

        assertThat(client.getCachedPdf(99L)).isNull()
    }

    @Test
    fun `boundary - pdf cache read failure degrades to null`() {
        whenever(invoiceClient.get(any<ByteArray>())).thenThrow(JedisException("down"))

        assertThat(client.getCachedPdf(1L)).isNull()
    }

    @Test
    fun `boundary - pdf cache write failure is silently dropped`() {
        whenever(invoiceClient.set(any<ByteArray>(), any<ByteArray>(), any<SetParams>())).thenThrow(JedisException("down"))

        client.cachePdf(1L, byteArrayOf(1), 900)
    }

    @Test
    fun `evictPdf deletes the key`() {
        client.evictPdf(7L)

        verify(invoiceClient).del("pdf:7")
    }

    @Test
    fun `boundary - evictPdf failure is silently dropped`() {
        whenever(invoiceClient.del(any<String>())).thenThrow(JedisException("down"))

        client.evictPdf(7L)
    }

    // ── job-status cache (invoice db) ────────────────────────────────────────

    @Test
    fun `happy path - job status cache writes and reads the status json back on a hit`() {
        whenever(invoiceClient.get("job:status:corr-1")).thenReturn("""{"status":"COMPLETED"}""")

        client.cacheJobStatus("corr-1", """{"status":"COMPLETED"}""", 3600)
        val hit = client.getJobStatus("corr-1")

        verify(invoiceClient).set(eq("job:status:corr-1"), eq("""{"status":"COMPLETED"}"""), any<SetParams>())
        assertThat(hit).isEqualTo("""{"status":"COMPLETED"}""")
    }

    @Test
    fun `boundary - job status cache read failure degrades to null`() {
        whenever(invoiceClient.get(any<String>())).thenThrow(JedisException("down"))

        assertThat(client.getJobStatus("corr-1")).isNull()
    }

    @Test
    fun `boundary - job status cache write failure is silently dropped`() {
        whenever(invoiceClient.set(any<String>(), any<String>(), any<SetParams>())).thenThrow(JedisException("down"))

        client.cacheJobStatus("corr-1", "{}", 3600)
    }

    @Test
    fun `evictJobStatus deletes the key`() {
        client.evictJobStatus("corr-1")

        verify(invoiceClient).del("job:status:corr-1")
    }

    @Test
    fun `boundary - evictJobStatus failure is silently dropped`() {
        whenever(invoiceClient.del(any<String>())).thenThrow(JedisException("down"))

        client.evictJobStatus("corr-1")
    }

    // ── receipt checksum cache (invoice db) ──────────────────────────────────

    @Test
    fun `happy path - checksum is known once marked`() {
        whenever(invoiceClient.exists("receipt:checksum:abc")).thenReturn(true)

        client.markChecksumKnown("abc")
        val known = client.isChecksumKnown("abc")

        verify(invoiceClient).set("receipt:checksum:abc", "1")
        assertThat(known).isTrue()
    }

    @Test
    fun `boundary - unmarked checksum is not known`() {
        whenever(invoiceClient.exists(any<String>())).thenReturn(false)

        assertThat(client.isChecksumKnown("missing")).isFalse()
    }

    @Test
    fun `boundary - checksum check failure degrades to false, not an exception`() {
        whenever(invoiceClient.exists(any<String>())).thenThrow(JedisException("down"))

        assertThat(client.isChecksumKnown("abc")).isFalse()
    }

    @Test
    fun `boundary - checksum mark failure is silently dropped`() {
        whenever(invoiceClient.set(any<String>(), any<String>())).thenThrow(JedisException("down"))

        client.markChecksumKnown("abc")
    }

    @Test
    fun `evictChecksum deletes the key`() {
        client.evictChecksum("abc")

        verify(invoiceClient).del("receipt:checksum:abc")
    }

    @Test
    fun `boundary - evictChecksum failure is silently dropped`() {
        whenever(invoiceClient.del(any<String>())).thenThrow(JedisException("down"))

        client.evictChecksum("abc")
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun `shutdown closes both clients once initialized`() {
        client.shutdown()

        verify(invoiceClient).close()
        verify(queryClient).close()
    }

    @Test
    fun `boundary - shutdown before init does not throw`() {
        val uninitialized = ValkeyInvoiceCacheClient(
            valkeyHost = "unused",
            valkeyPort = 0,
            valkeyPassword = "",
            invoiceDb = 1,
            queryDb = 2,
        )

        uninitialized.shutdown()
    }
}
