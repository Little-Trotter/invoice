package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.Receipt

/** Attaches [receipt] to the invoice as a whole; a receipt already attached stays single. */
fun Invoice.linkReceipt(receipt: Receipt) {
    if (receiptList.all { it !== receipt && (it.id == null || it.id != receipt.id) }) receiptRows.add(receipt)
}

/** Detaches the receipts with the given ids from the invoice as a whole; `true` when one was attached. */
fun Invoice.unlinkReceipts(receiptIds: Set<Long?>): Boolean = receiptRows.removeIf { it.id in receiptIds }

/** Replaces the receipts attached to the invoice as a whole. */
fun Invoice.replaceReceipts(newReceipts: List<Receipt>) = receiptRows.replaceAll(newReceipts)

/**
 * Takes over the receipts attached to [original] as a whole. Only for a document that is still
 * being composed in memory (a credit note copy, which is read-only from birth), like
 * [Invoice.addInitialItems].
 */
fun Invoice.adoptReceiptsOf(original: Invoice) {
    check(id == null) { "Receipts can only be adopted before the invoice is persisted." }
    receiptRows.compose(original.receiptList.filter { receipt -> receiptList.none { it === receipt } })
}

/** Replaces the receipts attached to this position; the owning invoice must still be editable. */
fun InvoiceItem.replaceReceipts(newReceipts: List<Receipt>) = receiptRows.replaceAll(newReceipts)

/** Detaches the receipts with the given ids from this position; `true` when at least one was attached. */
fun InvoiceItem.unlinkReceipts(receiptIds: Set<Long?>): Boolean = receiptRows.removeIf { it.id in receiptIds }
