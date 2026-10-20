package de.littletrotter.application.cache.port.outbound

/**
 * Cache for slowly changing reference lists (tax rates, per diem rates). Kept apart from the
 * overview pages: the lists live under their own key prefix, so dropping all overview pages
 * after an invoice change never forces a reference list to be re-read from the database.
 */
interface ReferenceListCachePort {
    /** Returns the cached JSON of reference list [key] or null on a miss. */
    fun getCachedReferenceList(key: String): String?
    fun cacheReferenceList(key: String, json: String, ttlSeconds: Long)
    fun evictReferenceList(key: String)
}
