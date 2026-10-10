package de.littletrotter.infrastructure.cache

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import redis.clients.jedis.RedisClient
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.params.ScanParams
import redis.clients.jedis.resps.ScanResult

/**
 * Unit tests for [ValkeyStatsServiceImpl] with [RedisClient] mocked via the
 * `clientFactory` test seam (the real service still dials Valkey directly
 * when that factory is not supplied).
 *
 * `scan` is dispatched through a single `thenAnswer` reading the actual [ScanParams.match]
 * pattern off the invocation at call time — chaining several `argThat` matchers across sibling
 * `whenever` calls on an overloaded method proved flaky (Mockito re-invoking a stale matcher with
 * a null argument while wiring up a later stub).
 */
class ValkeyStatsServiceImplTest {

    private fun scanResultOf(cursor: String, keys: List<String>) = ScanResult(cursor, keys)

    private fun stubScans(client: RedisClient, resultsByPattern: Map<String, ScanResult<String>>) {
        whenever(client.scan(any<String>(), any<ScanParams>())).thenAnswer { invocation ->
            val pattern = invocation.getArgument<ScanParams>(1).match()
            resultsByPattern[pattern] ?: scanResultOf("0", emptyList())
        }
    }

    private fun serviceWith(client: RedisClient) =
        ValkeyStatsServiceImpl(valkeyHost = "unused", valkeyPort = 0, valkeyPassword = "", clientFactory = { client })

    @Test
    fun `happy path - counts families across both databases, sums the payload db, and reports memory usage`() {
        val client = mock<RedisClient>()
        stubScans(
            client,
            mapOf(
                "pdf:*" to scanResultOf("0", listOf("pdf:1", "pdf:2")),
                "job:status:*" to scanResultOf("0", listOf("job:status:1")),
                "lt:tpl:*" to scanResultOf("0", listOf("lt:tpl:1")),
            ),
        )
        whenever(client.info("memory")).thenReturn("# Memory\r\nused_memory_human:1.23M\r\nmaxmemory:0\r\n")

        val stats = serviceWith(client).getStats()

        assertThat(stats.reachable).isTrue()
        // "pdf" is scanned once per db (1 and 2 in the loop) — 2 keys each time.
        assertThat(stats.families).extracting("family", "count")
            .containsExactlyInAnyOrder(
                tuple("pdf", 4L),
                tuple("job:status", 2L),
                tuple("lt:tpl", 1L),
            )
        assertThat(stats.totalKeys).isEqualTo(7L)
        assertThat(stats.usedMemoryHuman).isEqualTo("1.23M")
    }

    @Test
    fun `boundary - a scan failure for one pattern degrades that family to 0 without failing the whole probe`() {
        val client = mock<RedisClient>()
        whenever(client.scan(any<String>(), any<ScanParams>())).thenAnswer { invocation ->
            val pattern = invocation.getArgument<ScanParams>(1).match()
            if (pattern == "pdf:*") throw JedisException("connection reset")
            scanResultOf("0", emptyList())
        }
        whenever(client.info("memory")).thenReturn("used_memory_human:900.00K")

        val stats = serviceWith(client).getStats()

        assertThat(stats.reachable).isTrue()
        assertThat(stats.families).isEmpty()
        assertThat(stats.totalKeys).isEqualTo(0L)
    }

    @Test
    fun `boundary - a memory info failure degrades usedMemoryHuman to null, not an exception`() {
        val client = mock<RedisClient>()
        stubScans(client, emptyMap())
        whenever(client.info("memory")).thenThrow(JedisException("connection reset"))

        val stats = serviceWith(client).getStats()

        assertThat(stats.reachable).isTrue()
        assertThat(stats.usedMemoryHuman).isNull()
    }

    // Every other test injects `clientFactory` and never exercises the real
    // `buildClient()` body (pool/client config, `RedisClient.builder()`) or the CDI-facing
    // @Inject constructor, which delegates here with `clientFactory = null`.
    @Test
    fun `happy path - the CDI constructor builds a real RedisClient against an unreachable host without throwing`() {
        val service = ValkeyStatsServiceImpl(valkeyHost = "127.0.0.1", valkeyPort = 1, valkeyPassword = "")

        val stats = service.getStats()

        assertThat(stats).isNotNull()
    }

    @Test
    fun `boundary - a connection failure while building a client yields reachable false and empty families`() {
        val factoryFailingService = ValkeyStatsServiceImpl(
            valkeyHost = "unused",
            valkeyPort = 0,
            valkeyPassword = "",
            clientFactory = { throw JedisException("connection refused") },
        )

        val stats = factoryFailingService.getStats()

        assertThat(stats.reachable).isFalse()
        assertThat(stats.families).isEmpty()
        assertThat(stats.totalKeys).isEqualTo(0L)
        assertThat(stats.usedMemoryHuman).isNull()
    }

    @Test
    fun `boundary - a memory INFO response without a used_memory_human line degrades usedMemoryHuman to null`() {
        val client = mock<RedisClient>()
        stubScans(client, emptyMap())
        whenever(client.info("memory")).thenReturn("# Memory\r\nmaxmemory:0\r\n")

        val stats = serviceWith(client).getStats()

        assertThat(stats.reachable).isTrue()
        assertThat(stats.usedMemoryHuman).isNull()
    }

    @Test
    fun `boundary - a configured non-blank password is applied to the real client config without throwing`() {
        val service = ValkeyStatsServiceImpl(valkeyHost = "127.0.0.1", valkeyPort = 1, valkeyPassword = "secret")

        val stats = service.getStats()

        assertThat(stats).isNotNull()
    }

    @Test
    fun `boundary - a cursor that never returns to 0 is bounded to MAX_SCAN_ITERATIONS, never scans forever`() {
        val client = mock<RedisClient>()
        // A cursor that never settles at "0" — the bounded SCAN must still terminate.
        stubScans(client, mapOf("pdf:*" to scanResultOf("still-more", listOf("k"))))
        whenever(client.info("memory")).thenReturn("used_memory_human:1K")

        val stats = serviceWith(client).getStats()

        // 50 bounded iterations per db, scanned in both db 1 and db 2 => 100 total keys counted.
        assertThat(stats.families).extracting("family", "count").containsExactly(tuple("pdf", 100L))
        verify(client, times(100)).scan(any<String>(), argThat { params: ScanParams -> params.match() == "pdf:*" })
    }
}
