package de.littletrotter.infrastructure.cache

import de.littletrotter.application.cache.InvoiceCachePort
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.RedisClient
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.params.SetParams
import java.time.Duration

/**
 * Valkey-backed implementation of [InvoiceCachePort].
 *
 * Two logical databases, one [RedisClient] each — Jedis 8's `RedisClient` (successor to the
 * deprecated `JedisPool`/`Jedis` pair) pools connections internally and hands out one per command,
 * so a shared client can no longer switch database via `SELECT` between calls: the database is
 * baked into the client's [DefaultJedisClientConfig] at construction time instead.
 *  - [invoiceClient] (db [invoiceDb], default 1) — PDF bytes, job-status, receipt checksums
 *  - [queryClient]   (db [queryDb], default 2) — invoice overview query results
 *
 * All Valkey operations are wrapped in try/catch so that a missing or unreachable
 * Valkey instance degrades gracefully: reads return null (cache miss), writes are
 * silently dropped. This keeps the application functional during Valkey outages and
 * allows tests without a running Valkey to pass without extra test configuration.
 *
 * Every catch below is narrowed to [JedisException] — the only type any Jedis call
 * can throw — instead of a blanket `Exception`, so a genuine bug elsewhere in a method body
 * would still propagate instead of being folded into "cache miss". All still log at warn with
 * the operation and key so a Valkey outage remains visible in the logs even though callers
 * never see it fail.
 */
@ApplicationScoped
class ValkeyInvoiceCacheClient(
    @param:ConfigProperty(name = "VALKEY_HOST", defaultValue = "valkey")
    private val valkeyHost: String,
    @param:ConfigProperty(name = "VALKEY_PORT", defaultValue = "6379")
    private val valkeyPort: Int,
    @param:ConfigProperty(name = "VALKEY_PASSWORD", defaultValue = "")
    private val valkeyPassword: String,
    @param:ConfigProperty(name = "VALKEY_INVOICE_DB", defaultValue = "1")
    private val invoiceDb: Int,
    @param:ConfigProperty(name = "VALKEY_QUERY_CACHE_DB", defaultValue = "2")
    private val queryDb: Int,
    // Test seam: a non-null pair short-circuits buildClient() in init(), so unit tests can
    // inject a mocked RedisClient instead of dialling a real (or Testcontainers) Valkey instance.
    private val injectedInvoiceClient: RedisClient? = null,
    private val injectedQueryClient: RedisClient? = null,
) : InvoiceCachePort {

    private companion object {
        private const val CLIENT_TIMEOUT_MILLIS = 500
    }

    private val log = Logger.getLogger(javaClass)
    private lateinit var invoiceClient: RedisClient
    private lateinit var queryClient: RedisClient

    @Inject
    constructor(
        @ConfigProperty(name = "VALKEY_HOST", defaultValue = "valkey")
        valkeyHost: String,
        @ConfigProperty(name = "VALKEY_PORT", defaultValue = "6379")
        valkeyPort: Int,
        @ConfigProperty(name = "VALKEY_PASSWORD", defaultValue = "")
        valkeyPassword: String,
        @ConfigProperty(name = "VALKEY_INVOICE_DB", defaultValue = "1")
        invoiceDb: Int,
        @ConfigProperty(name = "VALKEY_QUERY_CACHE_DB", defaultValue = "2")
        queryDb: Int,
    ) : this(valkeyHost, valkeyPort, valkeyPassword, invoiceDb, queryDb, null, null)

    @PostConstruct
    fun init() {
        invoiceClient = injectedInvoiceClient ?: buildClient(invoiceDb)
        queryClient = injectedQueryClient ?: buildClient(queryDb)
    }

    private fun buildClient(database: Int): RedisClient {
        val poolConfig = ConnectionPoolConfig().apply {
            // Short borrow timeout so tests fail fast when Valkey is absent
            setMaxWait(Duration.ofMillis(CLIENT_TIMEOUT_MILLIS.toLong()))
        }
        val clientConfig = DefaultJedisClientConfig.builder()
            .database(database)
            .connectionTimeoutMillis(CLIENT_TIMEOUT_MILLIS)
            .socketTimeoutMillis(CLIENT_TIMEOUT_MILLIS)
            .apply { if (valkeyPassword.isNotBlank()) password(valkeyPassword) }
            .build()
        return RedisClient.builder()
            .hostAndPort(valkeyHost, valkeyPort)
            .clientConfig(clientConfig)
            .poolConfig(poolConfig)
            .build()
    }

    @PreDestroy
    fun shutdown() {
        if (::invoiceClient.isInitialized) invoiceClient.close()
        if (::queryClient.isInitialized) queryClient.close()
    }

    // ── Key builders ──────────────────────────────────────────────────────────

    private fun keyPdf(id: Long) = "pdf:$id"
    private fun keyJobStatus(id: String) = "job:status:$id"
    private fun keyChecksum(sha: String) = "receipt:checksum:$sha"

    // ── Overview cache (DB 2) ─────────────────────────────────────────────────

    override fun getCachedOverview(cacheKey: String): String? = try {
        queryClient.get(cacheKey)
    } catch (e: JedisException) {
        log.warnf("Valkey overview cache miss (get) for key=%s: %s", cacheKey, e.message)
        null
    }

    override fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long) {
        try {
            queryClient.set(cacheKey, json, SetParams.setParams().ex(ttlSeconds))
        } catch (e: JedisException) {
            log.warnf("Valkey overview cache write failed for key=%s: %s", cacheKey, e.message)
        }
    }

    override fun evictOverview(cacheKey: String) {
        try {
            queryClient.del(cacheKey)
        } catch (e: JedisException) {
            log.warnf("Valkey overview cache eviction failed for key=%s: %s", cacheKey, e.message)
        }
    }

    override fun evictAllOverview() {
        try {
            queryClient.flushDB()
        } catch (e: JedisException) {
            log.warnf("Valkey overview cache flush failed: %s", e.message)
        }
    }

    // ── PDF cache (DB 1) ──────────────────────────────────────────────────────

    override fun getCachedPdf(invoiceId: Long): ByteArray? = try {
        invoiceClient.get(keyPdf(invoiceId).toByteArray())
    } catch (e: JedisException) {
        log.warnf("Valkey PDF cache miss (get) for invoiceId=%d: %s", invoiceId, e.message)
        null
    }

    override fun cachePdf(invoiceId: Long, pdf: ByteArray, ttlSeconds: Long) {
        try {
            invoiceClient.set(keyPdf(invoiceId).toByteArray(), pdf, SetParams.setParams().ex(ttlSeconds))
        } catch (e: JedisException) {
            log.warnf("Valkey PDF cache write failed for invoiceId=%d: %s", invoiceId, e.message)
        }
    }

    override fun evictPdf(invoiceId: Long) {
        try {
            invoiceClient.del(keyPdf(invoiceId))
        } catch (e: JedisException) {
            log.warnf("Valkey PDF cache eviction failed for invoiceId=%d: %s", invoiceId, e.message)
        }
    }

    // ── Job-status cache (DB 1) ───────────────────────────────────────────────

    override fun getJobStatus(correlationId: String): String? = try {
        invoiceClient.get(keyJobStatus(correlationId))
    } catch (e: JedisException) {
        log.warnf("Valkey job-status cache miss for correlationId=%s: %s", correlationId, e.message)
        null
    }

    override fun cacheJobStatus(correlationId: String, statusJson: String, ttlSeconds: Long) {
        try {
            invoiceClient.set(keyJobStatus(correlationId), statusJson, SetParams.setParams().ex(ttlSeconds))
        } catch (e: JedisException) {
            log.warnf("Valkey job-status cache write failed for correlationId=%s: %s", correlationId, e.message)
        }
    }

    override fun evictJobStatus(correlationId: String) {
        try {
            invoiceClient.del(keyJobStatus(correlationId))
        } catch (e: JedisException) {
            log.warnf("Valkey job-status eviction failed for correlationId=%s: %s", correlationId, e.message)
        }
    }

    // ── Receipt checksum cache (DB 1) ─────────────────────────────────────────

    override fun isChecksumKnown(sha256: String): Boolean = try {
        invoiceClient.exists(keyChecksum(sha256))
    } catch (e: JedisException) {
        log.warnf("Valkey checksum check failed for sha256=%s: %s", sha256, e.message)
        false
    }

    override fun markChecksumKnown(sha256: String) {
        try {
            // No TTL – receipt checksums are permanent data
            invoiceClient.set(keyChecksum(sha256), "1")
        } catch (e: JedisException) {
            log.warnf("Valkey checksum mark failed for sha256=%s: %s", sha256, e.message)
        }
    }

    override fun evictChecksum(sha256: String) {
        try {
            invoiceClient.del(keyChecksum(sha256))
        } catch (e: JedisException) {
            log.warnf("Valkey checksum eviction failed for sha256=%s: %s", sha256, e.message)
        }
    }
}
