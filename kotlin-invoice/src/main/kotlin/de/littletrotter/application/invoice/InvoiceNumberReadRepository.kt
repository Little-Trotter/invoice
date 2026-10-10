package de.littletrotter.application.invoice

/**
 * CQRS read side for resolving invoice numbers to ids without loading invoices. Lives in
 * `application` for the same reason as [InvoiceReadRepository]: it returns a UI-shaped projection.
 */
fun interface InvoiceNumberReadRepository {
    /** Ids of the invoices whose number is exactly one of [numbers]; [numbers] is never empty. */
    fun findIdsByNumbers(numbers: Set<String>): Map<String, Long>
}
