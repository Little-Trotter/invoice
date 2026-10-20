package de.littletrotter.application.invoice.port.inbound

/**
 * Input port of the "which invoice carries this number" lookup used by screens that show an invoice
 * number as plain text (e.g. a mail reference) and want to link it to the invoice.
 */
fun interface InvoiceIdByNumberQueryPort {
    /**
     * Invoice ids keyed by the exact invoice number, resolved in one query. Numbers without a
     * matching invoice are absent from the result; unassigned (blank) numbers never match, since a
     * draft has no number yet. An invoice number is issued once across all sender profiles, so each
     * number maps to at most one invoice.
     */
    fun findIdsByNumbers(numbers: Set<String>): Map<String, Long>
}
