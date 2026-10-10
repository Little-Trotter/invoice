package de.littletrotter.application.invoice

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.domain.event.DomainEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject

/**
 * Evicts the cached invoice-overview pages on every invoice domain event. Before this observer the
 * cache was evicted only by the generation-job handlers, so creating an invoice or changing its
 * status left the list stale for the cache's whole TTL — a freshly created invoice could be
 * missing from (or mis-ordered in) the very next list view. Eviction is deliberately coarse
 * (evictAllOverview): the overview keys span every filter/sort/page combination, and a wholesale
 * drop of a short-lived read cache is cheaper than tracking which keys a mutation touches.
 */
@ApplicationScoped
class InvoiceOverviewCacheEvictor @Inject constructor(
    private val cache: InvoiceCachePort,
) {
    // No defensive catch: InvoiceCachePort implementations swallow their own infrastructure
    // errors (the Valkey client logs and absorbs JedisException; the noop port cannot fail), so
    // eviction is contractually non-throwing and a stale entry merely expires via TTL. The
    // parameter is what makes this an observer method; the body never reads it — named `ignored`
    // (not `event`) so detekt's UnusedParameter allowedNames convention recognizes the omission
    // as deliberate, same idiom as DefaultLocaleInitializer/
    // DevDataFixtures' own @Observes parameters.
    fun onInvoiceEvent(@ObservesAsync ignored: DomainEvent) {
        cache.evictAllOverview()
    }
}
