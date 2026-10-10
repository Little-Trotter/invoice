package de.littletrotter.xpdf

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache for S3 presigned URLs (RustFS).
 *
 * Presigned URLs are valid for a configurable TTL (default 15 min).
 * We store them with a conservative expiry of [DEFAULT_CACHE_TTL_MS] (14 min)
 * so that a cached URL is never served after its actual expiry.
 *
 * The cache is intentionally process-local: no external dependency is required in
 * the kotlin-xpdf worker. Size stays small because only recently-accessed documents
 * are requested repeatedly (e.g. UI preview, download).
 *
 * Eviction is lazy: stale entries are removed when [get] or [put] is called.
 * For a low-traffic service this is sufficient; add a scheduled sweep if needed.
 *
 * A Kotlin `object` singleton cannot itself be a CDI bean and so cannot have a [TimeSource]
 * injected into it — every method that needs "now" takes it as a [now] parameter instead,
 * supplied by the caller ([DocumentController]), which does have one injected.
 */
object PresignCache {

    const val DEFAULT_CACHE_TTL_MS: Long = 14 * 60 * 1000L   // 14 minutes

    private data class Entry(val url: String, val expiresAt: Instant)

    private val store = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the cached presigned URL for [bucket]/[objectKey], or null on miss / expiry.
     */
    fun get(bucket: String, objectKey: String, now: Instant): String? {
        val key = cacheKey(bucket, objectKey)
        val entry = store[key] ?: return null
        if (!now.isBefore(entry.expiresAt)) {
            store.remove(key)
            return null
        }
        return entry.url
    }

    /**
     * Stores [url] for [bucket]/[objectKey] with [ttlMs] validity.
     */
    fun put(bucket: String, objectKey: String, url: String, now: Instant, ttlMs: Long = DEFAULT_CACHE_TTL_MS) {
        evictExpired(now)
        store[cacheKey(bucket, objectKey)] = Entry(url, now.plusMillis(ttlMs))
    }

    /**
     * Removes a specific entry (call when the underlying object is deleted).
     */
    fun evict(bucket: String, objectKey: String) {
        store.remove(cacheKey(bucket, objectKey))
    }

    /** Clears the entire cache (for testing). */
    fun clear() = store.clear()

    /** Returns the number of non-expired entries (for testing / metrics). */
    fun size(now: Instant): Int = store.values.count { it.expiresAt > now }

    private fun cacheKey(bucket: String, objectKey: String) = "$bucket:$objectKey"

    /** Lazy eviction: remove all expired entries in a single pass. */
    private fun evictExpired(now: Instant) {
        store.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }
}
