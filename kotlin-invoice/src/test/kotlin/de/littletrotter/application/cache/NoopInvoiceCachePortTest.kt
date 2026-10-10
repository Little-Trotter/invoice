package de.littletrotter.application.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test for the [NoopInvoiceCachePort] — validates that the no-op implementation
 * satisfies the [InvoiceCachePort] contract with safe defaults (null / false) without
 * requiring a real Valkey connection.
 *
 * Moved here from `infrastructure/cache/ValkeyInvoiceCacheClientTest.kt`, which had
 * carried these since it was created but never actually exercised [de.littletrotter.infrastructure.cache.ValkeyInvoiceCacheClient]
 * itself; that file now holds the real Valkey adapter tests.
 */
class NoopInvoiceCachePortTest {

    private val noop = NoopInvoiceCachePort()

    @Test
    fun `noop overview cache always returns null and does not throw`() {
        assertThat(noop.getCachedOverview("any-key")).isNull()
        noop.cacheOverview("any-key", "{}", 30)
        noop.evictAllOverview()
    }

    @Test
    fun `noop pdf cache always returns null and does not throw`() {
        assertThat(noop.getCachedPdf(1L)).isNull()
        noop.cachePdf(1L, byteArrayOf(0x25, 0x50, 0x44, 0x46), 900)
        noop.evictPdf(1L)
    }

    @Test
    fun `noop job status cache always returns null and does not throw`() {
        assertThat(noop.getJobStatus("corr-1")).isNull()
        noop.cacheJobStatus("corr-1", """{"status":"COMPLETED"}""", 3600)
        noop.evictJobStatus("corr-1")
    }

    @Test
    fun `noop checksum cache always returns false and does not throw`() {
        assertThat(noop.isChecksumKnown("abc123")).isFalse()
        noop.markChecksumKnown("abc123")
        // After mark, noop still returns false (no state held)
        assertThat(noop.isChecksumKnown("abc123")).isFalse()
        noop.evictChecksum("abc123")
    }
}
