package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Branch coverage tests for PresignCache.
 * Tests cache hit/miss, expiry, and eviction logic — all driven by an explicit `now` [Instant]
 * the test passes in directly (see [PresignCache]'s own class KDoc on why it takes "now" as a
 * parameter rather than reading a clock itself), so expiry is proven deterministically instead
 * of racing a real `Thread.sleep`.
 */
class PresignCacheTest {

    private val start: Instant = Instant.parse("2026-01-15T10:30:00Z")

    @BeforeEach
    fun setUp() {
        PresignCache.clear()
    }

    @Test
    fun `get returns null on cache miss`() {
        assertNull(PresignCache.get("bucket1", "object1", start), "Should return null on miss")
    }

    @Test
    fun `put and get stores and retrieves URL`() {
        val url = "https://s3.example.com/presigned"

        PresignCache.put("bucket1", "object1", url, start)
        val retrieved = PresignCache.get("bucket1", "object1", start)

        assertEquals(url, retrieved, "Should retrieve stored URL")
    }

    @Test
    fun `get returns null when entry has expired`() {
        val url = "https://s3.example.com/presigned"
        val ttlMs = 100L

        PresignCache.put("bucket1", "object1", url, start, ttlMs)

        val retrieved = PresignCache.get("bucket1", "object1", start.plusMillis(150))
        assertNull(retrieved, "Should return null for expired entry")
    }

    @Test
    fun `entry is still valid one millisecond before its TTL expires`() {
        val url = "https://s3.example.com/presigned"

        PresignCache.put("bucket1", "object1", url, start, ttlMs = 1000)

        assertEquals(
            url, PresignCache.get("bucket1", "object1", start.plusMillis(999)),
            "Just before expiry must still be a cache hit",
        )
    }

    @Test
    fun `get removes expired entry from cache`() {
        val url = "https://s3.example.com/presigned"
        val ttlMs = 50L

        PresignCache.put("bucket1", "object1", url, start, ttlMs)
        val afterExpiry = start.plusMillis(100)

        // This should remove the expired entry
        PresignCache.get("bucket1", "object1", afterExpiry)

        // Verify it was removed by checking size
        val sizeBefore = PresignCache.size(afterExpiry)
        assertEquals(0, sizeBefore, "Expired entry should be removed")
    }

    @Test
    fun `multiple entries can coexist`() {
        PresignCache.put("bucket1", "object1", "url1", start)
        PresignCache.put("bucket1", "object2", "url2", start)
        PresignCache.put("bucket2", "object1", "url3", start)

        assertEquals("url1", PresignCache.get("bucket1", "object1", start))
        assertEquals("url2", PresignCache.get("bucket1", "object2", start))
        assertEquals("url3", PresignCache.get("bucket2", "object1", start))
    }

    @Test
    fun `put with custom TTL respects expiry`() {
        val url = "https://s3.example.com/presigned"
        val shortTtl = 50L

        PresignCache.put("bucket1", "object1", url, start, shortTtl)

        assertNull(PresignCache.get("bucket1", "object1", start.plusMillis(100)), "Short TTL entry should expire")
    }

    @Test
    fun `put with default TTL`() {
        val url = "https://s3.example.com/presigned"

        PresignCache.put("bucket1", "object1", url, start)
        val retrieved = PresignCache.get("bucket1", "object1", start)

        assertEquals(url, retrieved, "Should use default TTL")
    }

    @Test
    fun `evict removes specific entry`() {
        PresignCache.put("bucket1", "object1", "url1", start)
        PresignCache.put("bucket1", "object2", "url2", start)

        PresignCache.evict("bucket1", "object1")

        assertNull(PresignCache.get("bucket1", "object1", start), "Evicted entry should be removed")
        assertEquals("url2", PresignCache.get("bucket1", "object2", start), "Other entry should remain")
    }

    @Test
    fun `size counts only non-expired entries`() {
        PresignCache.put("bucket1", "object1", "url1", start, 10000) // Long TTL
        PresignCache.put("bucket1", "object2", "url2", start, 50) // Short TTL
        PresignCache.put("bucket1", "object3", "url3", start, 10000) // Long TTL

        val later = start.plusMillis(100)

        val size = PresignCache.size(later)
        assertEquals(2, size, "Size should count only non-expired entries")
    }

    @Test
    fun `put evicts expired entries before adding new one`() {
        PresignCache.put("bucket1", "object1", "url1", start, 50)
        val later = start.plusMillis(100)

        // This put should trigger eviction of the expired entry
        PresignCache.put("bucket1", "object2", "url2", later)

        val size = PresignCache.size(later)
        assertEquals(1, size, "Should evict expired entries during put")
    }

    @Test
    fun `clear removes all entries`() {
        PresignCache.put("bucket1", "object1", "url1", start)
        PresignCache.put("bucket1", "object2", "url2", start)

        PresignCache.clear()

        assertEquals(0, PresignCache.size(start), "Clear should remove all entries")
        assertNull(PresignCache.get("bucket1", "object1", start))
        assertNull(PresignCache.get("bucket1", "object2", start))
    }

    @Test
    fun `cache key properly separates buckets and objects`() {
        PresignCache.put("bucket1", "object1", "url1", start)
        PresignCache.put("bucket2", "object1", "url2", start)

        assertEquals("url1", PresignCache.get("bucket1", "object1", start))
        assertEquals("url2", PresignCache.get("bucket2", "object1", start))
    }
}
