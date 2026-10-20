package de.littletrotter.xpdf.infrastructure.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderSizingTest {

    private val mib = 1024L * 1024
    private val base = 150 * mib
    private val maxDocumentKib = 19 * 440

    private fun resolve(heapMib: Long, explicit: Int? = null, perThread: Long = base) =
        RenderSizing.resolve(heapMib * mib, explicit, perThread)

    @Test
    fun `START heap of 1536 MiB yields four bulk threads and 286 MiB receipt headroom`() {
        val sizing = resolve(1536)

        assertEquals(4, sizing.bulkThreads)
        assertEquals(1, sizing.interactiveThreads)
        assertEquals(286 * mib, sizing.receiptHeadroomBytes)
        assertEquals(maxDocumentKib * 286L / 256, sizing.weightBudgetKib.toLong())
    }

    @Test
    fun `START budget admits one maximum document and never two`() {
        val budget = resolve(1536).weightBudgetKib

        assertTrue(budget >= maxDocumentKib)
        assertTrue(budget < 2 * maxDocumentKib)
    }

    @Test
    fun `KLEIN heap of 1024 MiB yields one bulk thread and exactly one maximum document`() {
        val sizing = resolve(1024)

        assertEquals(1, sizing.bulkThreads)
        assertEquals(224 * mib, sizing.receiptHeadroomBytes)
        assertEquals(maxDocumentKib, sizing.weightBudgetKib)
    }

    @Test
    fun `an explicit thread count wins over the heap and the headroom follows it`() {
        val sizing = resolve(1536, explicit = 2)

        assertEquals(2, sizing.bulkThreads)
        assertEquals(586 * mib, sizing.receiptHeadroomBytes)
    }

    @Test
    fun `a large headroom is capped below two maximum documents`() {
        assertEquals(2 * maxDocumentKib - 1, resolve(1536, explicit = 1).weightBudgetKib)
    }

    @Test
    fun `a heap too small for the boot reserve keeps one bulk thread, no headroom and a one document budget`() {
        val sizing = resolve(300)

        assertEquals(1, sizing.bulkThreads)
        assertEquals(0, sizing.receiptHeadroomBytes)
        assertEquals(maxDocumentKib, sizing.weightBudgetKib)
    }

    @Test
    fun `a larger per-thread base lowers the thread count`() {
        assertEquals(1, resolve(1536, perThread = 400 * mib).bulkThreads)
    }

    @Test
    fun `resolve without arguments reads this JVM`() {
        val sizing = RenderSizing.resolve()

        assertTrue(sizing.bulkThreads >= JobConsumer.MIN_WORKER_THREADS)
        assertTrue(sizing.weightBudgetKib >= maxDocumentKib)
    }
}
