package de.littletrotter.infrastructure.cache

import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PayloadExchangeHolder
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Hands out the single shared [PayloadExchange] instance for this app via CDI.
 * `PayloadExchange` itself is a plain class (kotlin-shared carries no CDI dependency by design,
 * see its own KDoc); this producer reads `VALKEY_HOST`/`VALKEY_PORT`/`VALKEY_PASSWORD` the exact
 * same way [ValkeyInvoiceCacheClient]/[de.littletrotter.application.system.CacheStatusService]
 * already do, and constructs one to inject wherever a [PayloadExchange] is needed
 * (`DocumentJobPublisher`, `GenerationJobResultConsumer`).
 *
 * A dedicated database (`VALKEY_PAYLOAD_DB`, default 0) — distinct from
 * [ValkeyInvoiceCacheClient]'s DB 1 (invoice)/DB 2 (query) — so a payload-exchange key can never
 * collide with an unrelated cache key even though both share one Valkey instance.
 *
 * The construct-plus-close plumbing lives in [PayloadExchangeHolder], shared with
 * kotlin-xpdf's own producer; this class only supplies the CDI wiring and this app's config
 * property names.
 */
@ApplicationScoped
class PayloadExchangeProducer @Inject constructor(
    @ConfigProperty(name = "VALKEY_HOST", defaultValue = "valkey")
    valkeyHost: String,
    @ConfigProperty(name = "VALKEY_PORT", defaultValue = "6379")
    valkeyPort: Int,
    @ConfigProperty(name = "VALKEY_PASSWORD", defaultValue = "")
    valkeyPassword: String,
    @ConfigProperty(name = "VALKEY_PAYLOAD_DB", defaultValue = "0")
    valkeyPayloadDb: Int,
) : PayloadExchangeHolder(valkeyHost, valkeyPort, valkeyPassword, valkeyPayloadDb) {

    @Produces
    fun producePayloadExchange(): PayloadExchange = payloadExchange

    @PreDestroy
    fun closePayloadExchange() = close()
}
