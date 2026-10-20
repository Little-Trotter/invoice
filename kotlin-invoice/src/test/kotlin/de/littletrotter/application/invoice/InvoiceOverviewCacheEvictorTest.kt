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
import de.littletrotter.domain.invoice.InvoiceStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import de.littletrotter.shared.time.TimeSource
import java.time.Instant

class InvoiceOverviewCacheEvictorTest {

    private lateinit var cache: InvoiceCachePort
    private var now = 1_000L
    private val clock = mock<TimeSource> { on { instant() } doAnswer { Instant.ofEpochMilli(now) } }
    private lateinit var evictor: InvoiceOverviewCacheEvictor

    @BeforeEach
    fun setup() {
        cache = mock()
        now = 1_000L
        evictor = InvoiceOverviewCacheEvictor(cache, clock)
    }

    @Test
    fun `every overview relevant event type drops the overview pages`() {
        val handlers = listOf<() -> Unit>(
            { evictor.onCreated(InvoiceCreated(1L, "INV-1")) },
            { evictor.onCreatedFromQuote(InvoiceCreatedFromQuote(1L, "Q-1", "INV-1", emptyList())) },
            { evictor.onStatusChanged(InvoiceStatusChanged(1L, "INV-1", InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW)) },
            { evictor.onFinalized(InvoiceFinalized(1L, "INV-1")) },
            { evictor.onCancelled(InvoiceCancelled(2L, "CN-1", 1L, "INV-1")) },
            { evictor.onItemAdded(InvoiceItemAdded(1L, "INV-1", "p1")) },
            { evictor.onItemRemoved(InvoiceItemRemoved(1L, "INV-1", "p1")) },
            { evictor.onPartySnapshotsRefreshed(PartySnapshotsRefreshed(1L, "INV-1", listOf("name"))) },
            { evictor.onDocumentGenerated(DocumentGenerated(1L, "INV-1", 5L, null)) },
            { evictor.onJobFailed(GenerationJobFailed(1L, "INV-1", 5L, "x")) },
            { evictor.onJobExhausted(GenerationJobExhausted(1L, "INV-1", 5L, 3, "x")) },
            { evictor.onJobResultProcessingFailed(GenerationJobResultProcessingFailed(1L, "INV-1", 5L, "x")) },
        )
        handlers.forEach {
            now += 2_000L
            it()
        }
        verify(cache, times(handlers.size)).evictOverviewPages()
    }

    @Test
    fun `a burst inside the debounce window evicts once`() {
        evictor.onItemAdded(InvoiceItemAdded(1L, "INV-1", "p1"))
        now += 1_999L
        evictor.onItemAdded(InvoiceItemAdded(1L, "INV-1", "p2"))
        verify(cache, times(1)).evictOverviewPages()
    }

    @Test
    fun `an event after the debounce window evicts again`() {
        evictor.onItemAdded(InvoiceItemAdded(1L, "INV-1", "p1"))
        now += 2_000L
        evictor.onItemAdded(InvoiceItemAdded(1L, "INV-1", "p2"))
        verify(cache, times(2)).evictOverviewPages()
    }

    @Test
    fun `the very first event evicts even when the clock is negative`() {
        now = -5_000L
        evictor.onCreated(InvoiceCreated(1L, "INV-1"))
        verify(cache, times(1)).evictOverviewPages()
    }
}
