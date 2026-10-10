package de.littletrotter.application.system

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.RedisClient

@ApplicationScoped
class CacheStatusService(
    @param:ConfigProperty(name = "VALKEY_HOST", defaultValue = "valkey")
    private val valkeyHost: String,
    @param:ConfigProperty(name = "VALKEY_PORT", defaultValue = "6379")
    private val valkeyPort: Int,
    @param:ConfigProperty(name = "VALKEY_PASSWORD", defaultValue = "")
    private val valkeyPassword: String,
) {
    data class Status(
        val valkeyOnline: Boolean,
        val pdfCacheKeys: Long,
        val queryCacheKeys: Long,
    )

    companion object {
        private const val CONNECTION_TIMEOUT_MILLIS = 2_000
    }

    // Jedis 8's RedisClient (successor to the deprecated JedisPool/Jedis pair) pools
    // connections internally and hands out one per command, so it can no longer switch
    // database via SELECT between calls — one short-lived client per database instead.
    // Internal (not private) so a unit test can subclass and stub a reachable cache: the
    // class is opened for CDI proxying by the all-open compiler plugin (jakarta.enterprise.
    // context.ApplicationScoped), which makes this member overridable without an explicit
    // `open` keyword — every existing test only reaches this service through an unreachable
    // 127.0.0.1 port, so fetchStatus's success path (valkeyOnline = true) had no coverage at all.
    internal fun buildClient(database: Int): RedisClient {
        val clientConfig = DefaultJedisClientConfig.builder()
            .database(database)
            .connectionTimeoutMillis(CONNECTION_TIMEOUT_MILLIS)
            .apply { if (valkeyPassword.isNotBlank()) password(valkeyPassword) }
            .build()
        return RedisClient.builder()
            .hostAndPort(valkeyHost, valkeyPort)
            .clientConfig(clientConfig)
            .build()
    }

    fun fetchStatus(): Status {
        var valkeyOnline: Boolean
        var pdfCacheKeys: Long
        var queryCacheKeys: Long

        try {
            buildClient(1).use { pdfCacheKeys = it.dbSize() }
            buildClient(2).use { queryCacheKeys = it.dbSize() }
            valkeyOnline = true
        } catch (_: Exception) {
            valkeyOnline = false
            pdfCacheKeys = 0
            queryCacheKeys = 0
        }

        return Status(
            valkeyOnline = valkeyOnline,
            pdfCacheKeys = pdfCacheKeys,
            queryCacheKeys = queryCacheKeys,
        )
    }
}
