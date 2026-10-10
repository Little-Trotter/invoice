package de.littletrotter.application.dto

/**
 * One bulk detach request for `ReceiptLinkService.detachReceipts`: the invoice-level receipt
 * ids to unlink, plus a map of invoice item id to the receipt ids to unlink from that item. Either
 * or both may be non-empty; a selection that touches no receipt at all is a no-op.
 */
data class ReceiptDetachSelection(
    val invoiceLevelReceiptIds: Set<Long> = emptySet(),
    val itemReceiptIds: Map<Long, Set<Long>> = emptyMap(),
)
