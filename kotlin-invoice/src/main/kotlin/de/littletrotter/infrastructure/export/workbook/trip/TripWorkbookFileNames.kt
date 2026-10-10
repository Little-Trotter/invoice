package de.littletrotter.infrastructure.export.workbook.trip

import java.util.Locale

/**
 * `dienstreise_<invoice-number-or-id>_<position>.xlsx`: lower-case, `/` sanitised to `-`,
 * falling back to the invoice id when [invoiceNumber] is blank (a draft has none yet). The same
 * naming family `de.littletrotter.ui.view.invoice.invoiceExportFileName` already establishes for
 * the per-invoice workbook (`rechnung_<number-or-id><suffix>.<extension>`) — that function is
 * `internal` (module-visible) but hard-codes the `rechnung_` prefix and always writes `.xlsx`/
 * `.csv` for exactly one invoice, so it cannot itself produce this file's `dienstreise_…_<pos>`
 * shape; the download button and the BG-24 attachment are off limits for this package to
 * touch (parallel work), so the two functions stay separate rather than one reaching into the
 * other's file. Both apply the identical rule — lower-case, `/` → `-`, id fallback — on purpose.
 */
internal fun tripWorkbookFileName(invoiceNumber: String, invoiceId: Long, positionId: String): String {
    val invoiceSegment = invoiceNumber.replace("/", "-").ifBlank { null } ?: "id-$invoiceId"
    val positionSegment = positionId.replace("/", "-")
    return "dienstreise_${invoiceSegment}_$positionSegment.xlsx".lowercase(Locale.ROOT)
}
