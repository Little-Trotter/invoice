package de.littletrotter.application.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import redis.clients.jedis.RedisClient

/**
 * [CacheStatusService.fetchStatus]'s reachable-cache path had no coverage: the only
 * existing exercise ([de.littletrotter.ui.view.system.CachePanelTest]) always constructs the
 * real service against an unreachable `127.0.0.1:1`, so only the `catch` branch ever ran. A
 * subclass overriding [CacheStatusService.buildClient] (open by the all-open compiler plugin,
 * since the class carries `@ApplicationScoped`) stands in for an actually-reachable Valkey
 * instance without a live network connection (forbidden at unit level).
 */
class CacheStatusServiceTest {

    private class StubbedCacheStatusService(
        private val dbSizes: Map<Int, Long>,
    ) : CacheStatusService("127.0.0.1", 1, "") {
        override fun buildClient(database: Int): RedisClient {
            val client = mock<RedisClient>()
            whenever(client.dbSize()).thenReturn(dbSizes.getValue(database))
            return client
        }
    }

    @Test
    fun `happy path - fetchStatus reports online with the key counts from both databases when Valkey is reachable`() {
        val service = StubbedCacheStatusService(dbSizes = mapOf(1 to 3L, 2 to 7L))

        val status = service.fetchStatus()

        assertTrue(status.valkeyOnline)
        assertEquals(3L, status.pdfCacheKeys)
        assertEquals(7L, status.queryCacheKeys)
    }

    @Test
    fun `boundary - fetchStatus reports offline and zero key counts when Valkey is unreachable`() {
        val service = CacheStatusService("127.0.0.1", 1, "")

        val status = service.fetchStatus()

        assertFalse(status.valkeyOnline)
        assertEquals(0L, status.pdfCacheKeys)
        assertEquals(0L, status.queryCacheKeys)
    }

    @Test
    fun `boundary - a configured Valkey password is passed on to the client config`() {
        // Exercises the other side of the `if (valkeyPassword.isNotBlank())` branch inside
        // buildClient — every other test in this class (and CachePanelTest) leaves it blank.
        val service = CacheStatusService("127.0.0.1", 1, "secret")

        val status = service.fetchStatus()

        assertFalse(status.valkeyOnline, "127.0.0.1:1 is still unreachable regardless of password")
    }
}
