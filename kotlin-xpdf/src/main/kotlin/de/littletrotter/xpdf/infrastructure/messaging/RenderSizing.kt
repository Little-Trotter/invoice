package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.receipt.ReceiptSizePolicy

/**
 * How this JVM's heap is divided between render threads and receipt work, computed once at
 * startup and logged so a stress run shows the effective values.
 *
 * Threads carry the base every job needs; receipt pages are not multiplied into every thread but
 * admitted by receipt bytes ([JobWeightBudget]). The heap left after boot and all threads is the
 * [receiptHeadroomBytes] those admitted receipts share. The default [weightBudgetKib] lets exactly
 * one maximum combined document (19 receipts at the target size) run, plus small ones next to it,
 * and never two maximum documents together: two would need twice the rasterised pages the
 * headroom was sized for. A host with more room raises `xpdf.job-weight-budget-kib` explicitly.
 */
internal data class RenderSizing(
    val bulkThreads: Int,
    val interactiveThreads: Int,
    val perThreadBytes: Long,
    val receiptHeadroomBytes: Long,
    val weightBudgetKib: Int,
) {
    companion object {
        private const val BYTES_PER_KIB = 1024L

        /** Weight of one maximum combined document, in the unit [JobWeightBudget] counts. */
        const val MAX_DOCUMENT_WEIGHT_KIB: Int =
            (ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE * (ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES / BYTES_PER_KIB)).toInt()

        /**
         * Resolves the sizing for [maxHeapBytes]; [explicitBulkThreads] (`JOB_WORKER_THREADS`) wins over
         * the heap-derived count. The budget scales with the headroom relative to the receipt reserve
         * one maximum document was sized for, but stays between one and just under two such documents.
         */
        fun resolve(
            maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
            explicitBulkThreads: Int? = JobLane.configuredBulkThreads(),
            perThreadBytes: Long = JobConsumer.configuredPerThreadRenderBytes(),
        ): RenderSizing {
            val bulk = explicitBulkThreads ?: JobConsumer.defaultWorkerThreads(maxHeapBytes, perThreadBytes)
            val interactive = JobLane.DEFAULT_INTERACTIVE_THREADS
            val headroom = (maxHeapBytes - JobConsumer.BOOT_RESERVE_BYTES - (bulk + interactive) * perThreadBytes)
                .coerceAtLeast(0)
            val scaled = MAX_DOCUMENT_WEIGHT_KIB.toLong() * headroom / JobConsumer.RECEIPT_RESERVE_BYTES
            val budget = scaled.coerceIn(MAX_DOCUMENT_WEIGHT_KIB.toLong(), 2L * MAX_DOCUMENT_WEIGHT_KIB - 1).toInt()
            return RenderSizing(bulk, interactive, perThreadBytes, headroom, budget)
        }
    }
}
