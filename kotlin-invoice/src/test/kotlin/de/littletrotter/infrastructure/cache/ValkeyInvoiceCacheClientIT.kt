package de.littletrotter.infrastructure.cache

import de.littletrotter.test.ValkeyTestContainersExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import redis.clients.jedis.Jedis

/**
 * Integration test for [ValkeyInvoiceCacheClient] using a real Valkey Testcontainer.
 *
 * Covers:
 *  - Overview cache: read/write/evict (DB 2)
 *  - PDF cache: read/write/evict (DB 1)
 *  - Job-status cache: terminal state caching + eviction (DB 1)
 *  - Receipt checksum cache: mark/check/evict (DB 1)
 *  - Resilience: graceful null return when key absent
 */
@ExtendWith(ValkeyTestContainersExtension::class)
class ValkeyInvoiceCacheClientIT {

    private lateinit var client: ValkeyInvoiceCacheClient

    @BeforeEach
    fun setup() {
        val host = ValkeyTestContainersExtension.host()
        val port = ValkeyTestContainersExtension.port()

        // Flush both DBs before each test for isolation
        Jedis(host, port).use { jedis ->
            jedis.select(1); jedis.flushDB()
            jedis.select(2); jedis.flushDB()
        }

        client = ValkeyInvoiceCacheClient(host, port, "", invoiceDb = 1, queryDb = 2)
        client.init()
    }

    // ── Overview cache ────────────────────────────────────────────────────────

    @Test
    fun `overview cache miss returns null`() {
        assertThat(client.getCachedOverview("overview:missing")).isNull()
    }

    @Test
    fun `overview cache stores and retrieves JSON`() {
        val json = """[{"id":1,"invoiceNumber":"RE-2024-001"}]"""
        client.cacheOverview("overview:p0:10", json, ttlSeconds = 60)

        assertThat(client.getCachedOverview("overview:p0:10")).isEqualTo(json)
    }

    @Test
    fun `evictAllOverview removes all overview keys from DB 2`() {
        client.cacheOverview("overview:p0:10", "[]", ttlSeconds = 60)
        client.cacheOverview("overview:p1:10", "[]", ttlSeconds = 60)

        client.evictAllOverview()

        assertThat(client.getCachedOverview("overview:p0:10")).isNull()
        assertThat(client.getCachedOverview("overview:p1:10")).isNull()
    }

    @Test
    fun `overview cache does not bleed into invoice DB`() {
        client.cacheOverview("overview:p0:10", "[{\"id\":1}]", ttlSeconds = 60)

        // Overview is in DB 2 — invoice DB 1 must not contain this key
        Jedis(ValkeyTestContainersExtension.host(), ValkeyTestContainersExtension.port()).use { jedis ->
            jedis.select(1)
            assertThat(jedis.get("overview:p0:10")).isNull()
        }
    }

    // ── PDF cache ─────────────────────────────────────────────────────────────

    @Test
    fun `PDF cache miss returns null`() {
        assertThat(client.getCachedPdf(99L)).isNull()
    }

    @Test
    fun `PDF cache stores and retrieves binary data`() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)
        client.cachePdf(1L, pdfBytes, ttlSeconds = 900)

        assertThat(client.getCachedPdf(1L)).isEqualTo(pdfBytes)
    }

    @Test
    fun `evictPdf removes PDF from cache`() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        client.cachePdf(42L, pdfBytes)
        client.evictPdf(42L)

        assertThat(client.getCachedPdf(42L)).isNull()
    }

    // ── Job-status cache ──────────────────────────────────────────────────────

    @Test
    fun `job status cache miss returns null`() {
        assertThat(client.getJobStatus("corr-not-exists")).isNull()
    }

    @Test
    fun `job status cache stores and retrieves terminal state`() {
        val payload = """{"status":"COMPLETED","resultRefs":{"pdf":"invoices/RE-001.pdf"}}"""
        client.cacheJobStatus("corr-abc123", payload, ttlSeconds = 3600)

        assertThat(client.getJobStatus("corr-abc123")).isEqualTo(payload)
    }

    @Test
    fun `evictJobStatus removes cached status`() {
        client.cacheJobStatus("corr-del", """{"status":"FAILED"}""")
        client.evictJobStatus("corr-del")

        assertThat(client.getJobStatus("corr-del")).isNull()
    }

    @Test
    fun `job status uses invoice DB 1 not query DB 2`() {
        client.cacheJobStatus("corr-dbcheck", """{"status":"COMPLETED"}""")

        Jedis(ValkeyTestContainersExtension.host(), ValkeyTestContainersExtension.port()).use { jedis ->
            jedis.select(1)
            assertThat(jedis.get("job:status:corr-dbcheck")).isNotNull()
            jedis.select(2)
            assertThat(jedis.get("job:status:corr-dbcheck")).isNull()
        }
    }

    // ── Receipt checksum cache ────────────────────────────────────────────────

    @Test
    fun `unknown checksum returns false`() {
        assertThat(client.isChecksumKnown("deadbeef")).isFalse()
    }

    @Test
    fun `markChecksumKnown stores checksum permanently`() {
        val sha = "a".repeat(64)
        client.markChecksumKnown(sha)

        assertThat(client.isChecksumKnown(sha)).isTrue()
    }

    @Test
    fun `evictChecksum removes known checksum`() {
        val sha = "b".repeat(64)
        client.markChecksumKnown(sha)
        client.evictChecksum(sha)

        assertThat(client.isChecksumKnown(sha)).isFalse()
    }

    @Test
    fun `checksum stored without TTL persists across flushDB on other DB`() {
        val sha = "c".repeat(64)
        client.markChecksumKnown(sha)

        // Flushing query DB 2 must not affect checksum in DB 1
        client.evictAllOverview()

        assertThat(client.isChecksumKnown(sha)).isTrue()
    }
}
