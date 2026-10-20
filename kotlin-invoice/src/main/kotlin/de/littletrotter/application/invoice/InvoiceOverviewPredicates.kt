package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.shared.FILTER_PARAM
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Join
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import java.time.LocalDate

/** The three party-name paths the free-text filter searches, bundled into one type so
 *  [invoiceOverviewTextAndStatusPredicates] stays under detekt's `LongParameterList` threshold —
 *  same reasoning as this module's other `OverviewJoins` holders. */
class InvoiceOverviewPartyNames(
    val recipient: Path<String>,
    val seller: Path<String>,
    val senderProfile: Path<String>,
)

/** The entity-ID join paths the six dimension predicates need, bundled for the same
 *  `LongParameterList` reason. Callers holding the three party joins derive them through
 *  [of] — the single spelling of the `get("id")` convention. */
class InvoiceOverviewDimensionPaths(
    val recipientId: Expression<Long>,
    val selectedSellerId: Expression<Long>,
    val senderProfileId: Expression<Long>,
) {
    companion object {
        /** Qodana wk39/DuplicatedCode — the grid ([InvoiceOverviewQueryServiceImpl]) and the
         *  export ([de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl])
         *  both mapped their party joins to the id paths identically; the mapping now lives here
         *  so a changed join attribute lands in both query paths in one commit. */
        fun of(
            recipient: Join<Invoice, *>,
            selectedSeller: Join<Invoice, *>,
            senderProfile: Join<Invoice, *>,
        ) = InvoiceOverviewDimensionPaths(
            recipientId = recipient.get("id"),
            selectedSellerId = selectedSeller.get("id"),
            senderProfileId = senderProfile.get("id"),
        )
    }
}

/** [invoiceOverviewTextAndStatusPredicates]'s own free-text/status filter, bundled into one type
 *  to satisfy detekt's `LongParameterList` threshold — same reasoning as this file's other
 *  parameter-object holders. */
data class InvoiceOverviewTextStatusFilter(
    val filterText: String?,
    val status: InvoiceStatus?,
)

/** The six filter dimensions evaluated by [invoiceOverviewDimensionPredicates], bundled
 *  into one type to satisfy detekt's `LongParameterList` threshold. */
data class InvoiceOverviewDimensions(
    val reverseCharge: Boolean? = null,
    val validationStatus: ValidationStatus? = null,
    val recipientId: Long? = null,
    val senderProfileId: Long? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
)

/**
 * The invoice overview's shared `filterText`/`status` predicates: a free-text
 * OR-LIKE across the invoice number and the three party names, plus an exact status match. Both
 * overview query paths built the same predicates independently —
 * [InvoiceOverviewQueryServiceImpl.overviewFilterPredicates] (which layers more UI-driven filters
 * on top) and [de.littletrotter.infrastructure.persistence.PanacheInvoiceRepository
 * .overviewPredicates] (which needs no more) — this is the common prefix, extracted. Returns the
 * predicates collected so far in a mutable list, so a caller with further filter dimensions can
 * keep appending to it, alongside the bound `filterText` parameter, if any.
 */
fun invoiceOverviewTextAndStatusPredicates(
    cb: CriteriaBuilder,
    i: Path<Invoice>,
    partyNames: InvoiceOverviewPartyNames,
    filter: InvoiceOverviewTextStatusFilter,
): Pair<List<Predicate>, ParameterExpression<String>?> {
    val predicates = mutableListOf<Predicate>()
    var filterParam: ParameterExpression<String>? = null
    if (!filter.filterText.isNullOrBlank()) {
        val param = cb.parameter(String::class.java, FILTER_PARAM)
        filterParam = param
        predicates += cb.or(
            cb.like(cb.lower(i.get("invoiceNumber")), param, '\\'),
            cb.like(cb.lower(partyNames.recipient), param, '\\'),
            cb.like(cb.lower(partyNames.seller), param, '\\'),
            cb.like(cb.lower(partyNames.senderProfile), param, '\\'),
        )
    }
    if (filter.status != null) predicates += cb.equal(i.get<InvoiceStatus>("status"), filter.status)
    return predicates.toList() to filterParam
}

/** The six dimension predicates shared between [InvoiceOverviewQueryServiceImpl] and
 *  [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl], extracted so a
 *  new filter dimension lands in both the grid and the export in one commit. Callers append it to the
 *  list returned by [invoiceOverviewTextAndStatusPredicates]. */
fun invoiceOverviewDimensionPredicates(
    cb: CriteriaBuilder,
    i: Path<Invoice>,
    paths: InvoiceOverviewDimensionPaths,
    dimensions: InvoiceOverviewDimensions,
): List<Predicate> = buildList {
    dimensions.reverseCharge?.let { add(cb.equal(i.get<Boolean>("reverseCharge"), it)) }
    dimensions.validationStatus?.let { add(cb.equal(i.get<ValidationStatus>("validationStatus"), it)) }
    dimensions.recipientId?.let { add(cb.equal(paths.recipientId, it)) }
    dimensions.senderProfileId?.let { add(cb.equal(cb.coalesce(paths.selectedSellerId, paths.senderProfileId), it)) }
    dimensions.dateFrom?.let { add(cb.greaterThanOrEqualTo(i.get("invoiceDate"), it)) }
    dimensions.dateTo?.let { add(cb.lessThanOrEqualTo(i.get("invoiceDate"), it)) }
}
