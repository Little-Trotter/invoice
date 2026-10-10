package de.littletrotter.application.cache

/**
 * Port for invoice caching (overview, PDF, job status, receipt checksum).
 * Implementations may use Valkey or a no-op adapter for dev/test.
 * Defined in the application layer so application services can depend on it
 * without violating the hexagonal architecture constraint.
 */
interface InvoiceCachePort {

    // ── Overview / query cache (query DB) ────────────────────────────────────
    fun getCachedOverview(cacheKey: String): String?
    fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long = 30)
    /** Evicts a single key from the query cache DB (targeted invalidation). */
    fun evictOverview(cacheKey: String)
    /** Flushes the entire query cache DB — use only when all cached queries must be invalidated. */
    fun evictAllOverview()

    // ── PDF cache (invoice DB) ────────────────────────────────────────────────
    fun getCachedPdf(invoiceId: Long): ByteArray?
    fun cachePdf(invoiceId: Long, pdf: ByteArray, ttlSeconds: Long = 900)
    fun evictPdf(invoiceId: Long)

    // ── Job-status cache (invoice DB) ─────────────────────────────────────────
    /** Returns cached JSON status payload or null on cache miss. */
    fun getJobStatus(correlationId: String): String?
    /** Cache terminal job status (COMPLETED / FAILED) for [ttlSeconds]. */
    fun cacheJobStatus(correlationId: String, statusJson: String, ttlSeconds: Long = 3600)
    fun evictJobStatus(correlationId: String)

    // ── Receipt checksum cache (invoice DB) ───────────────────────────────────
    /** Returns true when the checksum was previously stored via [markChecksumKnown]. */
    fun isChecksumKnown(sha256: String): Boolean
    /** Marks a receipt SHA-256 checksum as known (no TTL – permanent data). */
    fun markChecksumKnown(sha256: String)
    fun evictChecksum(sha256: String)
}
