package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.shared.SortDirection

interface PaymentTermRepository {
    fun findAll(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<PaymentTerm>
    fun count(filterText: String? = null): Long
    /** Count of terms with `isActive = false`, same [filterText] semantics as
     *  [count]; the header counter's "N inaktiv" half. */
    fun countInactive(filterText: String? = null): Long
    fun findDefault(): PaymentTerm?
    fun findDefaults(): List<PaymentTerm>
    fun findById(id: Long): PaymentTerm?

    /** Demotes every default except [keepId] (`null` = a not yet saved term) in one statement,
     *  serialised against concurrent callers until the surrounding transaction ends, so the
     *  single-default index cannot be hit by two users promoting different terms at once. */
    fun releaseDefaultsExcept(keepId: Long?)
    fun save(term: PaymentTerm): PaymentTerm
    fun delete(id: Long)

    /** Whether any [de.littletrotter.domain
     *  .invoice.Invoice] or [de.littletrotter.domain.masterdata.SenderProfile] default still
     *  references this term via its live FK. Guards a hard delete with a clear German error
     *  instead of a raw constraint violation — mirrors `TaxRateRepository.isInUse`. */
    fun isInUse(id: Long): Boolean

    /** Usage counts across every entity that can
     *  reference a payment term (invoices, sender-profile defaults), grouped in one query and
     *  keyed by payment_term id; a term absent from the map is unused. Backs the tab's
     *  "Verwendung" column and the deactivate confirmation — never a per-row query. */
    fun usageCounts(): Map<Long, Long>
}
