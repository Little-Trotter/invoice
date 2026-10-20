package de.littletrotter.application.cache

import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.cache.port.outbound.ReferenceListCachePort

/**
 * No-op cache port — used in unit tests and secondary constructors where CDI is not available.
 * Every read returns null / false; every write is a no-op.
 */
class NoopInvoiceCachePort : InvoiceCachePort, ReferenceListCachePort {
    override fun getCachedOverview(cacheKey: String): String? = null
    override fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long) = Unit
    override fun evictOverview(cacheKey: String) = Unit
    override fun evictOverviewPages() = Unit
    override fun getCachedReferenceList(key: String): String? = null
    override fun cacheReferenceList(key: String, json: String, ttlSeconds: Long) = Unit
    override fun evictReferenceList(key: String) = Unit

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
