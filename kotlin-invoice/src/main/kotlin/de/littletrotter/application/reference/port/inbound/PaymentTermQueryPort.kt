package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.domain.shared.SortDirection

/**
 * Input port for reading the payment-term catalogue: the catalogue tab's paged grid and usage
 * column, and the payment-term selectors of the seller form and the invoice header.
 */
interface PaymentTermQueryPort {
    /** Every payment term, capped at the reference-data lookup page size. */
    fun findAllPaymentTerms(): List<PaymentTermDto>

    fun findDefaultPaymentTerm(): PaymentTermDto?

    fun findPaymentTermById(id: Long): PaymentTermDto?

    fun findPaymentTerms(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<PaymentTermDto>

    fun countPaymentTerms(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactivePaymentTerms(filterText: String? = null): Long

    /** Backs the tab's "Verwendung" column and its deactivate confirmation; one grouped query,
     *  no per-row calls. */
    fun findPaymentTermUsageCounts(): Map<Long, Long>
}
