package de.littletrotter.shared

import org.slf4j.LoggerFactory
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.RedisClient
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest
import java.time.Duration

/**
 * AP-M6 — thin Valkey-backed exchange channel
 * for large TEXT payloads that must not travel inline on NATS (target envelope < 32 KB, plan
 * §2.5). A producer `put`s a value under a namespaced `lt:…` key with a TTL and sends only the
 * key (plus, where the caller needs it, an integrity hash) on the wire; the consumer resolves it
 * once and forgets it. Valkey is a **transport buffer with TTL, never a source of truth** —
 * everything of record still lands in the DB/S3 exactly as before this AP.
 *
 * PDF/binary bytes never go through this class — those stay on S3/RustFS as `objectKey`
 * references (same doc, "S3/RustFS first for PDFs"); `maxmemory 64mb volatile-lru` on the Pi
 * would evict a multi-MB value before it is ever read.
 *
 * Deliberately a plain class, not a CDI bean: kotlin-shared carries no CDI/microprofile-config
 * dependency by design (see this module's `pom.xml` — "No JPA/CDI here"). Each consuming app
 * (`kotlin-invoice`, `kotlin-xpdf`) owns a small `@ApplicationScoped @Produces` wrapper that
 * resolves `VALKEY_HOST`/`VALKEY_PORT`/`VALKEY_PASSWORD` via `@ConfigProperty` — the exact same
 * convention `de.littletrotter.infrastructure.cache.ValkeyInvoiceCacheClient`/`CacheStatusService`
 * already use — and constructs one of these to hand out via CDI.
 *
 * Same Jedis 8 idiom as `ValkeyInvoiceCacheClient`: [RedisClient] (successor to the deprecated
 * `JedisPool`/`Jedis` pair) pools connections internally and hands out one per command, so the
 * database is baked into [DefaultJedisClientConfig] at construction time.
 *
 * Both operations degrade gracefully on a Valkey outage instead of throwing: [get] returns
 * `null` (indistinguishable from a genuine cache miss — every caller already treats a `null` as
 * "ref expired/missing", the documented failure path for the
 * KoSIT-report and workbook refs) and [put] silently drops the write after logging a warning — same
 * reasoning as `ValkeyInvoiceCacheClient`'s own KDoc.
 *
 * No `ByteArray` overload: nothing in this codebase offloads binary content through Valkey today
 * (PDFs stay on S3, per the doc above) and speculative surface for a caller that does not exist
 * yet is exactly what the AP-M6 spec's own "Out of scope" section warns against for the sibling
 * DTO-offload mechanism — add it when a real caller needs it, not before.
 */
class PayloadExchange private constructor(
    private val client: RedisClient,
) : AutoCloseable by client {

    constructor(host: String, port: Int, password: String, database: Int) :
        this(buildClient(host, port, password, database))

    private val log = LoggerFactory.getLogger(PayloadExchange::class.java)

    /**
     * `SETEX key ttlSeconds value`. [key] must already carry the `lt:…` namespace (callers build
     * it, e.g. `lt:tpl:{sha256}`/`lt:kosit:{jobId}`) — this class stays agnostic of what it
     * stores. Drops the write silently (after a warning log) on a Valkey outage; the caller's own
     * documented failure path (a resolver seeing a missing key later) is what surfaces this to an
     * operator, not an exception here.
     */
    fun put(key: String, value: String, ttlSeconds: Long) {
        try {
            client.set(key, value, SetParams.setParams().ex(ttlSeconds))
        } catch (e: JedisException) {
            log.warn("PayloadExchange write failed for key={}: {}", key, e.message)
        }
    }

    /**
     * `GET key`. Returns `null` both on a genuine cache miss and on a Valkey outage — every
     * caller already treats both the same way (see this class's own KDoc), so there is no value
     * in distinguishing them here.
     */
    fun get(key: String): String? = try {
        client.get(key)
    } catch (e: JedisException) {
        log.warn("PayloadExchange read failed for key={}: {}", key, e.message)
        null
    }

    companion object {
        /** Borrow/connect/socket timeout shared by [buildClient]'s pool and client config — short
         *  so callers fail fast (and degrade per [put]/[get]'s own KDoc) when Valkey is absent,
         *  instead of blocking the publish/consume path. */
        private const val REDIS_TIMEOUT_MS = 500

        /** Lower-case hex SHA-256 of [value], UTF-8 encoded — the integrity check
         *  [TripWorkbookRefDto.sha256] carries, computed identically on both the producing side
         *  (`DocumentJobPublisher`, before the `SETEX`) and the resolving side (`JobConsumer`,
         *  after the `GET`) so a Valkey eviction/rewrite race is detected rather than silently
         *  rendering with the wrong content. */
        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun buildClient(host: String, port: Int, password: String, database: Int): RedisClient {
            val poolConfig = ConnectionPoolConfig().apply {
                setMaxWait(Duration.ofMillis(REDIS_TIMEOUT_MS.toLong()))
            }
            val clientConfig = DefaultJedisClientConfig.builder()
                .database(database)
                .connectionTimeoutMillis(REDIS_TIMEOUT_MS)
                .socketTimeoutMillis(REDIS_TIMEOUT_MS)
                .apply { if (password.isNotBlank()) password(password) }
                .build()
            return RedisClient.builder()
                .hostAndPort(host, port)
                .clientConfig(clientConfig)
                .poolConfig(poolConfig)
                .build()
        }
    }
}
