package de.littletrotter.application.invoice

import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.GenerationJobExhausted
import de.littletrotter.domain.event.GenerationJobFailed
import de.littletrotter.domain.event.GenerationJobResultProcessingFailed
import de.littletrotter.domain.event.InvoiceCancelled
import de.littletrotter.domain.event.InvoiceCreated
import de.littletrotter.domain.event.InvoiceCreatedFromQuote
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceItemAdded
import de.littletrotter.domain.event.InvoiceItemRemoved
import de.littletrotter.domain.event.InvoiceStatusChanged
import de.littletrotter.domain.event.PartySnapshotsRefreshed
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.ObservesAsync
import jakarta.inject.Inject
import java.util.concurrent.atomic.AtomicLong

/**
 * Evicts the cached invoice-overview pages when an invoice event changes what the list shows:
 * creation, status, finalization, cancellation, item changes (the list shows the gross
 * amount), refreshed party snapshots (recipient and seller name) and document-job outcomes
 * (validation status, document-failed marker). Without it a freshly created invoice could be missing from the next list view for the
 * cache's whole TTL. Events that do not alter any overview row (offer rejections, LLM analysis,
 * message-center traffic) are deliberately not observed. Eviction drops all overview pages (the
 * keys span every filter/sort/page combination) but never the reference lists.
 *
 * Eviction is debounced: a burst of events (one per added position) triggers at most one eviction
 * per [DEBOUNCE_MILLIS]. An eviction skipped inside the window is not retried, so the pages
 * cached right after the last evicting event can be stale; that staleness is bounded by the
 * 30-second TTL of the overview cache, and the polling UI re-reads every 2 seconds anyway.
 * No timer or thread is involved, only a timestamp compare-and-set.
 */
@ApplicationScoped
class InvoiceOverviewCacheEvictor @Inject constructor(
    private val cache: InvoiceCachePort,
    private val time: TimeSource,
) {
    private val lastEviction = AtomicLong(NEVER)

    // No defensive catch: InvoiceCachePort implementations swallow their own infrastructure
    // errors, so eviction is contractually non-throwing and a stale entry merely expires via TTL.
    // The event parameters are what make these observer methods; the bodies never read them.
    fun onCreated(@ObservesAsync ignored: InvoiceCreated) = evictDebounced()

    fun onCreatedFromQuote(@ObservesAsync ignored: InvoiceCreatedFromQuote) = evictDebounced()

    fun onStatusChanged(@ObservesAsync ignored: InvoiceStatusChanged) = evictDebounced()

    fun onFinalized(@ObservesAsync ignored: InvoiceFinalized) = evictDebounced()

    fun onCancelled(@ObservesAsync ignored: InvoiceCancelled) = evictDebounced()

    fun onItemAdded(@ObservesAsync ignored: InvoiceItemAdded) = evictDebounced()

    fun onItemRemoved(@ObservesAsync ignored: InvoiceItemRemoved) = evictDebounced()

    // Party names and the validation/document-failure columns also change without a status event.
    fun onPartySnapshotsRefreshed(@ObservesAsync ignored: PartySnapshotsRefreshed) = evictDebounced()

    fun onDocumentGenerated(@ObservesAsync ignored: DocumentGenerated) = evictDebounced()

    fun onJobFailed(@ObservesAsync ignored: GenerationJobFailed) = evictDebounced()

    fun onJobExhausted(@ObservesAsync ignored: GenerationJobExhausted) = evictDebounced()

    fun onJobResultProcessingFailed(@ObservesAsync ignored: GenerationJobResultProcessingFailed) = evictDebounced()

    private fun evictDebounced() {
        val now = time.instant().toEpochMilli()
        val last = lastEviction.get()
        if (last != NEVER && now - last in 0 until DEBOUNCE_MILLIS) return
        if (!lastEviction.compareAndSet(last, now)) return
        cache.evictOverviewPages()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
        const val NEVER = Long.MIN_VALUE
    }
}
