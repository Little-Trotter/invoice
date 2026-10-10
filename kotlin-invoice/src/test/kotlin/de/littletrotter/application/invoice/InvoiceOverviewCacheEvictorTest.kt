package de.littletrotter.application.invoice

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.domain.event.InvoiceCreated
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class InvoiceOverviewCacheEvictorTest {

    @Test
    fun `any invoice domain event drops the whole overview cache`() {
        val cache = mock<InvoiceCachePort>()
        InvoiceOverviewCacheEvictor(cache).onInvoiceEvent(InvoiceCreated(1L, "INV-1"))
        verify(cache).evictAllOverview()
    }
}
