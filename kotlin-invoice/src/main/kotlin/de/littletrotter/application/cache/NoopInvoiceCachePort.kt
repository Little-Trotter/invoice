package de.littletrotter.application.cache

/**
 * No-op cache port — used in unit tests and secondary constructors where CDI is not available.
 * Every read returns null / false; every write is a no-op.
 */
class NoopInvoiceCachePort : InvoiceCachePort {
    override fun getCachedOverview(cacheKey: String): String? = null
    override fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long) = Unit
    override fun evictOverview(cacheKey: String) = Unit
    override fun evictAllOverview() = Unit

    override fun getCachedPdf(invoiceId: Long): ByteArray? = null
    override fun cachePdf(invoiceId: Long, pdf: ByteArray, ttlSeconds: Long) = Unit
    override fun evictPdf(invoiceId: Long) = Unit

    override fun getJobStatus(correlationId: String): String? = null
    override fun cacheJobStatus(correlationId: String, statusJson: String, ttlSeconds: Long) = Unit
    override fun evictJobStatus(correlationId: String) = Unit

    override fun isChecksumKnown(sha256: String): Boolean = false
    override fun markChecksumKnown(sha256: String) = Unit
    override fun evictChecksum(sha256: String) = Unit
}
