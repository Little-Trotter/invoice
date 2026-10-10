package de.littletrotter.xpdf

import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PayloadExchangeHolder
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Hands out the single shared [PayloadExchange] instance for `kotlin-xpdf` via CDI.
 * `PayloadExchange` itself is a plain class (kotlin-shared carries no CDI dependency by design,
 * see its own KDoc); this producer reads `VALKEY_HOST`/`VALKEY_PORT`/`VALKEY_PASSWORD` — the
 * same connection convention `kotlin-invoice`'s `ValkeyInvoiceCacheClient`/
 * `PayloadExchangeProducer` already use — and constructs one to inject wherever a
 * [PayloadExchange] is needed (JobConsumer, ResultPublisher).
 *
 * `VALKEY_PAYLOAD_DB` (default `0`) must match `kotlin-invoice`'s own `PayloadExchangeProducer`:
 * both apps read/write the same keys (`lt:tpl:{sha256}` written by `kotlin-invoice`'s
 * `DocumentJobPublisher`, read here by JobConsumer; `lt:kosit:{jobId}` written here by
 * ResultPublisher, read by `kotlin-invoice`'s `GenerationJobResultConsumer`) — a mismatched
 * database would put producer and consumer in two disjoint keyspaces on the same Valkey
 * instance.
 *
 * The construct-plus-close plumbing lives in [PayloadExchangeHolder], shared with
 * kotlin-invoice's own producer; this class only supplies the CDI wiring and this app's config
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
