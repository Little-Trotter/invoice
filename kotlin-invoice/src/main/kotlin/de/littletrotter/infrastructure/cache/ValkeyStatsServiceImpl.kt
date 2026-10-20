package de.littletrotter.infrastructure.cache

import de.littletrotter.application.system.port.outbound.ValkeyStatsService
import de.littletrotter.application.dto.ValkeyStatsStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import org.eclipse.microprofile.config.inject.ConfigProperty
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.RedisClient
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.params.ScanParams
import java.time.Duration

/**
 * Queries Valkey for usage by key family across all configured databases.
 * Uses non-blocking SCAN with pattern matching, bounded to avoid full scans.
 * Returns family counts plus total (used in system view).
 * Exceptions caught and degraded (reachable=false, empty families).
 */
@ApplicationScoped
class ValkeyStatsServiceImpl(
    @param:ConfigProperty(name = "VALKEY_HOST", defaultValue = "valkey")
    private val valkeyHost: String,
    @param:ConfigProperty(name = "VALKEY_PORT", defaultValue = "6379")
    private val valkeyPort: Int,
    @param:ConfigProperty(name = "VALKEY_PASSWORD", defaultValue = "")
    private val valkeyPassword: String,
    // Test seam: when set, replaces buildClient() so unit tests can inject a mocked
    // RedisClient per database number instead of dialling a real Valkey instance. No `= null`
    // default: every call site already passes it explicitly — the CDI-facing secondary
    // constructor below always forwards an explicit `null` — so a default here only grew an
    // unreachable `<init>$default` bridge that no caller could ever actually invoke.
    private val clientFactory: ((Int) -> RedisClient)?,
) : ValkeyStatsService {

    @Inject
    constructor(
        @ConfigProperty(name = "VALKEY_HOST", defaultValue = "valkey")
        valkeyHost: String,
        @ConfigProperty(name = "VALKEY_PORT", defaultValue = "6379")
        valkeyPort: Int,
        @ConfigProperty(name = "VALKEY_PASSWORD", defaultValue = "")
        valkeyPassword: String,
    ) : this(valkeyHost, valkeyPort, valkeyPassword, null)

    private val log = Logger.getLogger(javaClass)

    override fun getStats(): ValkeyStatsStatus = try {
        val families = mutableMapOf<String, Long>()
        var usedMemoryHuman: String? = null

        // Scan each database for key families
        for (db in listOf(1, 2)) {
            buildClient(db).use { client ->
                families["pdf"] = (families["pdf"] ?: 0) + scanPatternCount(client, "pdf:*")
                families["job:status"] = (families["job:status"] ?: 0) + scanPatternCount(client, "job:status:*")
                families["receipt:checksum"] = (families["receipt:checksum"] ?: 0) + scanPatternCount(client, "receipt:checksum:*")
            }
        }

        // Scan payload exchange database for lt:* keys
        buildClient(0).use { payloadClient ->
            families["lt:tpl"] = scanPatternCount(payloadClient, "lt:tpl:*")
            families["lt:kosit"] = scanPatternCount(payloadClient, "lt:kosit:*")
            usedMemoryHuman = fetchMemoryUsage(payloadClient)
        }

        val totalKeys = families.values.sum()
        ValkeyStatsStatus(
            reachable = true,
            families = families
                .filter { it.value > 0 }
                .map { ValkeyStatsStatus.KeyFamilyCount(it.key, it.value) }
                .sortedByDescending { it.count },
            totalKeys = totalKeys,
            usedMemoryHuman = usedMemoryHuman,
        )
    } catch (e: JedisException) {
        // Narrowed — every Jedis call site below wraps its own failures as JedisException;
        // the two inner scan/info helpers already catch and degrade internally, so what reaches here
        // is realistically buildClient()/connection setup failing. This is a stats probe for the
        // admin view (class KDoc: "Exceptions caught and degraded"), so it stays a catch — an
        // unreachable Valkey must not break that view — but narrowed so a genuine bug elsewhere in
        // this method is not silently folded into "reachable=false". Already logged.
        log.warnf("Valkey stats query failed: %s", e.message)
        ValkeyStatsStatus(reachable = false)
    }

    /**
     * Fetch human-readable memory usage from INFO memory command.
     * Parses the used_memory_human: line; null on any failure (degrade gracefully).
     */
    private fun fetchMemoryUsage(client: RedisClient): String? = try {
        val info = client.info("memory")
        // Filter-then-map instead of a safe-call chain: `find { … }?.substringAfter(…)?.trim()` would
        // add a second null check on a value that is never null, a branch jacoco can never cover.
        info.lines()
            .filter { it.startsWith(USED_MEMORY_HUMAN_PREFIX) }
            .map { it.substringAfter(USED_MEMORY_HUMAN_PREFIX).trim() }
            .firstOrNull()
    } catch (e: JedisException) {
        // Narrowed — client.info() only fails via JedisException. Best-effort (usedMemory
        // stays null on failure); already logged at debug, matching the KDoc's "null on any failure".
        log.debugf("Memory info fetch failed: %s", e.message)
        null
    }

    /**
     * Scan keys matching pattern using SCAN, bounded to ~5000 iterations max
     * to avoid full DB scans on large instances.
     *
     * `return@repeat` inside `repeat(N) { }` only skips the rest of that one lambda
     * invocation, it does not stop the loop: every pattern used to run the full
     * [MAX_SCAN_ITERATIONS] iterations even after `cursor` already came back to `"0"`, re-scanning
     * (and re-counting) the same page up to [MAX_SCAN_ITERATIONS] times and inflating every
     * family's reported count by as much as that factor. A counted `while` loop actually breaks.
     */
    private fun scanPatternCount(client: RedisClient, pattern: String): Long = try {
        var count = 0L
        val params = ScanParams().match(pattern).count(100)
        var cursor = "0"

        var iterationsLeft = MAX_SCAN_ITERATIONS
        while (iterationsLeft > 0) {
            val scanResult = client.scan(cursor, params)
            count += scanResult.result.size
            cursor = scanResult.cursor
            iterationsLeft--
            if (cursor == "0") break
        }
        count
    } catch (e: JedisException) {
        // Narrowed — client.scan() only fails via JedisException. Best-effort per-pattern
        // count (degrades that one family to 0 rather than failing the whole stats view); already
        // logged at debug.
        log.debugf("Pattern scan failed for %s: %s", pattern, e.message)
        0L
    }

    private fun buildClient(database: Int): RedisClient = clientFactory?.invoke(database) ?: run {
        val poolConfig = ConnectionPoolConfig().apply {
            setMaxWait(Duration.ofMillis(VALKEY_TIMEOUT_MS))
        }
        val clientConfig = DefaultJedisClientConfig.builder()
            .database(database)
            .connectionTimeoutMillis(VALKEY_TIMEOUT_MS.toInt())
            .socketTimeoutMillis(VALKEY_TIMEOUT_MS.toInt())
            .apply { if (valkeyPassword.isNotBlank()) password(valkeyPassword) }
            .build()
        RedisClient.builder()
            .hostAndPort(valkeyHost, valkeyPort)
            .clientConfig(clientConfig)
            .poolConfig(poolConfig)
            .build()
    }

    private companion object {
        /** Bounded SCAN: at most this many cursor pages per pattern — a stats probe must never full-scan. */
        const val MAX_SCAN_ITERATIONS = 50
        /** One short budget for connect/socket/pool-wait — a stats probe fails fast, never blocks the view. */
        const val VALKEY_TIMEOUT_MS = 500L
        /** The `INFO memory` line [fetchMemoryUsage] reads human-readable used-memory off. */
        const val USED_MEMORY_HUMAN_PREFIX = "used_memory_human:"
    }
}
