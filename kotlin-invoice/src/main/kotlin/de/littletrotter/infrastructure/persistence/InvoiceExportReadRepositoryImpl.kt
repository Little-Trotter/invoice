package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.InvoiceHeaderExportRow
import de.littletrotter.application.export.InvoiceHeaderRowMapper
import de.littletrotter.application.export.InvoiceJournalExportRow
import de.littletrotter.application.export.InvoiceJournalRowMapper
import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.application.export.InvoicePositionRowMapper
import de.littletrotter.application.export.PerDiemDayRowInput
import de.littletrotter.application.export.TripExpenseRowInput
import de.littletrotter.application.export.port.outbound.InvoiceExportReadRepository
import de.littletrotter.application.invoice.InvoiceOverviewDimensionPaths
import de.littletrotter.application.invoice.InvoiceOverviewDimensions
import de.littletrotter.application.invoice.InvoiceOverviewPartyNames
import de.littletrotter.application.invoice.InvoiceOverviewTextStatusFilter
import de.littletrotter.application.invoice.invoiceOverviewDimensionPredicates
import de.littletrotter.application.invoice.invoiceOverviewTextAndStatusPredicates
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.invoice.InvoiceReferences
import de.littletrotter.domain.invoice.InvoiceTexts
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.invoice.PartyRole
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.shared.likePattern
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Join
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import jakarta.persistence.criteria.Selection
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [InvoiceExportReadRepository] backed by Criteria scalar projections, following the same
 * pattern as [de.littletrotter.application.invoice.InvoiceOverviewQueryServiceImpl]: every filter
 * dimension is a [Predicate], `filterText` travels as a [ParameterExpression], no string-
 * concatenated JPQL.
 *
 * The one deliberate exception is [rawInvoiceNumbers]: [de.littletrotter.domain.invoice
 * .InvoiceNumberConverter] throws [InvoiceValidationException] on a malformed stored value, and it
 * throws **during hydration** of any Criteria/JPQL path typed to
 * [de.littletrotter.domain.invoice.InvoiceNumber] — including a scalar tuple projection, since
 * Hibernate applies the attribute converter regardless of how narrow the selected path is. A bulk
 * export cannot let one bad row fail the whole batch, so the raw `invoice_number` column is read
 * through a native SQL query instead, which never touches the converter (the same reasoning that
 * already justifies every other native query in this package, e.g. [PostgresNumberJournalRepository]).
 */
@ApplicationScoped
class InvoiceExportReadRepositoryImpl @Inject constructor(
    private val em: EntityManager,
    private val b2bFieldValueStore: B2bFieldValueStore,
    private val invoiceAuditJournalRepository: InvoiceAuditJournalRepository,
) : InvoiceExportReadRepository {

    /** Mode A — see [InvoiceExportReadRepository.findHeaderRow]'s own KDoc. Shares every trap
     *  guard, join and selection list with [findHeaderRows]; only the predicate (one id instead
     *  of a filter plus keyset page) and the lack of pagination differ. */
    override fun findHeaderRow(invoiceId: Long): InvoiceHeaderExportRow? {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val i = cq.from(Invoice::class.java)
        val joins = HeaderJoins(i, cb)
        cq.select(cb.tuple(headerSelections(cb, i, joins))).where(cb.equal(i.get<Long>("id"), invoiceId))

        val tuple = em.createQuery(cq).resultList.firstOrNull() ?: return null
        val raw = rawInvoiceNumbers(listOf(invoiceId))[invoiceId]
        return InvoiceHeaderRowMapper.fromRow(tuple.toArray(), raw, isMalformed(raw))
    }

    override fun findHeaderRows(filter: InvoiceExportFilter, afterId: Long?, batchSize: Int): List<InvoiceHeaderExportRow> {
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val i = cq.from(Invoice::class.java)
        val joins = HeaderJoins(i, cb)

        val predicates = headerPredicates(joins, filter, afterId)
        cq.select(cb.tuple(headerSelections(cb, i, joins))).where(*predicates.predicates).orderBy(cb.asc(i.get<Long>("id")))

        val query = em.createQuery(cq)
        predicates.filterParam?.let { query.setParameter(it, likePattern(filter.filterText!!)) }
        query.maxResults = batchSize

        val tuples = query.resultList
        if (tuples.isEmpty()) return emptyList()

        val ids = tuples.map { (it.get(0) as Number).toLong() }
        val rawNumbers = rawInvoiceNumbers(ids)

        return tuples.map { tuple ->
            val id = (tuple.get(0) as Number).toLong()
            val raw = rawNumbers[id]
            InvoiceHeaderRowMapper.fromRow(tuple.toArray(), raw, isMalformed(raw))
        }
    }

    /** Mode A — see [InvoiceExportReadRepository.findPositionRows]'s own KDoc. */
    override fun findPositionRows(invoiceId: Long): List<InvoicePositionExportRow> =
        findPositionRows(listOf(invoiceId))[invoiceId].orEmpty()

    /** Batch variant — see [InvoiceExportReadRepository.findPositionRows]'s own KDoc. One Criteria
     *  tuple query for every position of [invoiceIds] (LEFT-JOINing `articleRef`/`taxRateRef` so
     *  neither lazy association is ever walked per row, the owning invoice id appended as the last
     *  column), then a fixed number of further batch queries for the whole page of items — never
     *  one per item or per invoice: [b2bIdentifiersByItem] for BT-155/BT-156 and [receiptsByItem]
     *  for the per-item receipt file names. Grouped per invoice in memory. */
    override fun findPositionRows(invoiceIds: List<Long>): Map<Long, List<InvoicePositionExportRow>> {
        if (invoiceIds.isEmpty()) return emptyMap()
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val ii = cq.from(InvoiceItem::class.java)
        val joins = PositionJoins(ii)
        val owningInvoiceId = ii.get<Invoice>("invoice").get<Long>("id")
        cq.select(cb.tuple(positionSelections(ii, joins) + owningInvoiceId))
            .where(owningInvoiceId.`in`(invoiceIds))
            .orderBy(cb.asc(ii.get<Int>(ATTR_SORT_ORDER)))

        val tuples = em.createQuery(cq).resultList
        if (tuples.isEmpty()) return emptyMap()

        val itemIds = tuples.map { InvoicePositionRowMapper.itemId(it.toArray()) }
        val b2b = b2bIdentifiersByItem(itemIds)
        val receipts = receiptsByItem(itemIds)
        val invoiceNumbers = rawInvoiceNumbers(invoiceIds)
        // Batched once for the whole page of items, never per trip (same discipline as
        // b2bIdentifiersByItem/receiptsByItem above); harmless (empty) for a non-TRIP item's id.
        val tripExpenses = tripExpensesByItem(itemIds)
        val perDiemDays = perDiemDaysByItem(itemIds)

        return tuples.groupBy({ (it.toArray().last() as Number).toLong() }) { tuple ->
            val array = tuple.toArray()
            val itemId = InvoicePositionRowMapper.itemId(array)
            val invoiceId = (array.last() as Number).toLong()
            val position = InvoicePositionRowMapper.fromRow(
                array,
                InvoicePositionRowMapper.InvoiceIdentity(invoiceId, invoiceNumbers[invoiceId].orEmpty()),
                b2b[itemId] ?: (null to null),
                receipts[itemId].orEmpty(),
            )
            if (position.category != InvoiceItemCategory.TRIP) {
                listOf(position)
            } else {
                listOf(position) + InvoicePositionRowMapper.tripDetailRows(
                    position,
                    tripExpenses[itemId].orEmpty(),
                    perDiemDays[itemId].orEmpty(),
                )
            }
        }.mapValues { (_, rows) -> rows.flatten() }
    }

    /** One [de.littletrotter.application.export.TripExpenseRowInput] per [TripExpense],
     *  batched for the whole page of [itemIds] (never one query per trip), ordered by the expense's
     *  own [TripExpense.sortOrder] so [InvoicePositionRowMapper.tripDetailRows] numbers them in the
     *  same order the trip dialog/PDF already show (rk-2's own `ORDER BY sortOrder` convention). */
    // `internal`, not `private`: findPositionRows (this class's only caller) always
    // passes a non-empty itemIds derived from already-fetched tuples, so the empty-list guard
    // below is unreachable through any current call path. It stays (an empty `IN ()` predicate
    // is invalid JPQL, not merely wasteful) and is driven directly instead, the same
    // internal-for-direct-testing precedent PostgresInvoiceAuditJournalRepository.toOffsetDateTime
    // and PostgresNumberJournalRepository.toOffsetDateTime already set.
    internal fun tripExpensesByItem(itemIds: List<Long>): Map<Long, List<TripExpenseRowInput>> {
        if (itemIds.isEmpty()) return emptyMap()
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val te = cq.from(TripExpense::class.java)
        val invoiceItem = te.get<InvoiceItem>("invoiceItem")
        val receipt = te.join<TripExpense, Receipt>("receipt", JoinType.LEFT)
        cq.select(
            cb.tuple(
                invoiceItem.get<Long>("id"),
                te.get<TripExpenseKind>("kind"),
                te.get<String>("description"),
                te.get<LocalDate>(ATTR_TRAVEL_DATE),
                te.get<BigDecimal>("quantity"),
                te.get<InvoiceItemUnit>("unit"),
                te.get<Money>("unitPriceNet"),
                te.get<Money>(ATTR_NET_AMOUNT),
                receipt.get<String>("fileName"),
            ),
        ).where(invoiceItem.get<Long>("id").`in`(itemIds)).orderBy(cb.asc(te.get<Int>(ATTR_SORT_ORDER)))

        return em.createQuery(cq).resultList.map { tuple ->
            val array = tuple.toArray()
            // description/quantity/unitPriceNet/netAmount read straight from
            // TripExpense's own non-nullable columns (@Column(nullable = false) / a non-null
            // Kotlin property type), so the `?:` fallback these used to carry was unreachable for
            // any persisted row; only travelDate/receiptFileName are genuinely optional here.
            (array[0] as Number).toLong() to TripExpenseRowInput(
                kind = array[1] as TripExpenseKind,
                description = array[2] as String,
                travelDate = array[3] as LocalDate?,
                quantity = array[4] as BigDecimal,
                unit = array[5] as InvoiceItemUnit,
                unitPriceNet = array[6] as Money,
                netAmount = array[7] as Money,
                receiptFileName = array[8] as String?,
            )
        }.groupBy({ it.first }, { it.second })
    }

    /** The per-diem-day counterpart of [tripExpensesByItem], one
     *  [de.littletrotter.application.export.PerDiemDayRowInput] per [TravelExpenseEntry], ordered
     *  by its own travel date. */
    // See tripExpensesByItem's own KDoc note just above.
    internal fun perDiemDaysByItem(itemIds: List<Long>): Map<Long, List<PerDiemDayRowInput>> {
        if (itemIds.isEmpty()) return emptyMap()
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val te = cq.from(TravelExpenseEntry::class.java)
        val invoiceItem = te.get<InvoiceItem>("invoiceItem")
        cq.select(
            cb.tuple(
                invoiceItem.get<Long>("id"),
                te.get<LocalDate>(ATTR_TRAVEL_DATE),
                te.get<Boolean>("isArrivalDeparture"),
                te.get<Boolean>("breakfastDeduction"),
                te.get<Boolean>("lunchDeduction"),
                te.get<Boolean>("dinnerDeduction"),
                te.get<Money>("employerContribution"),
                te.get<Money>("calculatedAmount"),
            ),
        ).where(invoiceItem.get<Long>("id").`in`(itemIds)).orderBy(cb.asc(te.get<LocalDate>(ATTR_TRAVEL_DATE)))

        return em.createQuery(cq).resultList.map { tuple ->
            val array = tuple.toArray()
            // isArrivalDeparture/breakfastDeduction/lunchDeduction/dinnerDeduction are
            // non-nullable Kotlin Boolean columns and employerContribution/calculatedAmount are
            // non-nullable Money columns on TravelExpenseEntry, so their `?:` fallback was
            // unreachable for any persisted row; only date is genuinely optional here.
            (array[0] as Number).toLong() to PerDiemDayRowInput(
                date = array[1] as LocalDate?,
                isArrivalDeparture = array[2] as Boolean,
                breakfastDeduction = array[3] as Boolean,
                lunchDeduction = array[4] as Boolean,
                dinnerDeduction = array[5] as Boolean,
                employerContribution = array[6] as Money,
                claimableAmount = array[7] as Money,
            )
        }.groupBy({ it.first }, { it.second })
    }

    /** Mode A — see [InvoiceExportReadRepository.findJournalRows]'s own KDoc. Pages through
     *  [InvoiceAuditJournalRepository.streamByInvoiceId] with a `seq` cursor until a batch comes
     *  back shorter than [InvoiceExportReadRepository.DEFAULT_BATCH_SIZE] — the same "keep asking
     *  until the page is short" convention [findHeaderRows]' own caller (a future orchestrator)
     *  would use, just driven internally here since a single invoice's journal is unbounded (J3)
     *  but this method's own return type is not itself paged. */
    override fun findJournalRows(invoiceId: Long): List<InvoiceJournalExportRow> {
        val batchSize = InvoiceExportReadRepository.DEFAULT_BATCH_SIZE
        val rows = mutableListOf<InvoiceJournalExportRow>()
        var afterSeq = 0L
        var hasMore = true
        while (hasMore) {
            val batch = invoiceAuditJournalRepository.streamByInvoiceId(invoiceId, afterSeq, batchSize)
            if (batch.isEmpty()) {
                hasMore = false
            } else {
                rows += batch.map(InvoiceJournalRowMapper::fromEntry)
                afterSeq = batch.last().seq
                hasMore = batch.size >= batchSize
            }
        }
        return rows.toList()
    }

    private class PositionJoins(ii: Root<InvoiceItem>) {
        val article: Join<InvoiceItem, Article> = ii.join("articleRef", JoinType.LEFT)
        val taxRate: Join<InvoiceItem, TaxRate> = ii.join("taxRateRef", JoinType.LEFT)
    }

    private fun positionSelections(ii: Root<InvoiceItem>, joins: PositionJoins): List<Selection<*>> = listOf(
        ii.get<Long>("id"),
        ii.get<String>("positionId"),
        ii.get<Int>(ATTR_SORT_ORDER),
        ii.get("category"),
        ii.get<String>("description"),
        ii.get<String>("itemDescription"),
        ii.get<BigDecimal>("quantity"),
        ii.get("unit"),
        ii.get("unitPriceNet"),
        ii.get("taxCategory"),
        ii.get<TaxRatePercent>("taxRateSnapshot"),
        joins.taxRate.get<TaxRatePercent>("rate"),
        ii.get<String>("taxRuleCode"),
        ii.get<String>("exemptionReasonCode"),
        ii.get<String>("exemptionReasonText"),
        ii.get<String>("taxLegalBasis"),
        ii.get(ATTR_NET_AMOUNT),
        ii.get("vatAmount"),
        ii.get("grossAmount"),
        ii.get<LocalDate>("servicePeriodFrom"),
        ii.get<LocalDate>("servicePeriodTo"),
        ii.get<String>("costCenter"),
        ii.get<String>("deliveryNoteReference"),
        joins.article.get<ArticleNumber>("articleNumber"),
        // The position's own invoice currency, so the sheet can format each amount in
        // its real currency even when mode B mixes several invoices in one sheet.
        ii.get<Invoice>("invoice").get("currency"),
        // Only ever non-null on a TRIP item; read here rather than re-queried per item so
        // InvoicePositionSheetWriter's own control block can tell a fixed-price trip from one
        // priced off its parts without a second round trip.
        ii.get<Money>("tripFixedNetPrice"),
    )

    /** BT-155/BT-156 live in the generic `b2b_field_value` store, not on the item; one
     *  [B2bFieldValueStore.findByOwners] call for the whole page of [itemIds] instead of one
     *  lookup per position. */
    // See tripExpensesByItem's own KDoc note above.
    internal fun b2bIdentifiersByItem(itemIds: List<Long>): Map<Long, Pair<String?, String?>> {
        if (itemIds.isEmpty()) return emptyMap()
        val values = b2bFieldValueStore.findByOwners(B2bFieldOwnerType.INVOICE_ITEM, itemIds)
        return values.groupBy { it.ownerId }.mapValues { (_, forItem) ->
            forItem.firstOrNull { it.term == B2bTerm.BT_155 }?.value to forItem.firstOrNull { it.term == B2bTerm.BT_156 }?.value
        }
    }

    /** One query for the whole page of [itemIds]' receipt file names, never one per item — the
     *  same batching discipline [b2bIdentifiersByItem]/[rawInvoiceNumbers] already apply. */
    // See tripExpensesByItem's own KDoc note above.
    internal fun receiptsByItem(itemIds: List<Long>): Map<Long, List<String>> {
        if (itemIds.isEmpty()) return emptyMap()
        val cb = em.criteriaBuilder
        val cq = cb.createTupleQuery()
        val ii = cq.from(InvoiceItem::class.java)
        val receipt = ii.join<InvoiceItem, Receipt>("receipts", JoinType.INNER)
        cq.select(cb.tuple(ii.get<Long>("id"), receipt.get<String>("fileName"))).where(ii.get<Long>("id").`in`(itemIds))
        return em.createQuery(cq).resultList
            .groupBy({ (it.get(0) as Number).toLong() }, { it.get(1) as String })
    }

    /** A blank number is a legitimate draft, not a defect; only
     *  a non-blank value that matches none of [InvoiceNumber]'s three accepted formats is flagged.
     *  [InvoiceNumber]'s own format check is exactly the validation this needs, so it is reused
     *  here as a probe rather than duplicating its three regexes — the exception is the expected,
     *  intentional signal for "malformed", not an unhandled error (`ignore` matches detekt's
     *  `SwallowedException.allowedExceptionNameRegex` default). */
    private fun isMalformed(raw: String?): Boolean =
        !raw.isNullOrBlank() &&
            try {
                InvoiceNumber(raw)
                false
            } catch (ignore: InvoiceValidationException) {
                true
            }

    /** Bypasses [de.littletrotter.domain.invoice.InvoiceNumberConverter] entirely — see this
     *  class's own KDoc. Batched by [ids] rather than one query per row. */
    private fun rawInvoiceNumbers(ids: List<Long>): Map<Long, String?> {
        val rows: List<*> = em.createNativeQuery("SELECT id, invoice_number FROM invoice WHERE id IN (:ids)")
            .setParameter("ids", ids)
            .resultList
        return rows.associate { rowAny ->
            val row = rowAny as Array<*>
            (row[0] as Number).toLong() to row[1].toString()
        }
    }

    /** Also keeps the [cb]/[i] this call's joins were built from — so [headerPredicates] (built
     *  from the exact same [i]/[cb]) takes one parameter for the whole query-scoped state instead
     *  of three (LongParameterList). */
    private class HeaderJoins(val i: Root<Invoice>, val cb: CriteriaBuilder) {
        val selectedSeller: Join<Invoice, Any> = i.join("selectedSeller", JoinType.LEFT)
        val senderProfile: Join<Invoice, Any> = i.join("senderProfile", JoinType.LEFT)
        val recipient: Join<Invoice, Any> = i.join("recipient", JoinType.LEFT)

        /** LEFT JOINs onto the same `invoice_party_snapshot` table, filtered by role with an `ON`
         *  predicate — same idiom as [de.littletrotter.application.invoice
         *  .InvoiceOverviewQueryServiceImpl.resolveLivePaymentTermName]'s exact/fallback joins. */
        val sellerSnapshot: Join<Invoice, InvoicePartySnapshot> = i.join<Invoice, InvoicePartySnapshot>("partySnapshots", JoinType.LEFT)
            .also { it.on(cb.equal(it.get<PartyRole>("partyRole"), PartyRole.SELLER)) }
        val buyerSnapshot: Join<Invoice, InvoicePartySnapshot> = i.join<Invoice, InvoicePartySnapshot>("partySnapshots", JoinType.LEFT)
            .also { it.on(cb.equal(it.get<PartyRole>("partyRole"), PartyRole.BUYER)) }
    }

    private class HeaderPredicates(val predicates: Array<Predicate>, val filterParam: ParameterExpression<String>?)

    private fun headerPredicates(
        joins: HeaderJoins,
        filter: InvoiceExportFilter,
        afterId: Long?,
    ): HeaderPredicates {
        val cb = joins.cb
        val i = joins.i
        // Qodana wk39/DuplicatedCode — the text/status prefix and the six dimension predicates now
        // travel through the same shared builders as the grid (InvoiceOverviewQueryServiceImpl),
        // so export and grid provably select on identical predicates.
        val (textAndStatus, filterParam) = invoiceOverviewTextAndStatusPredicates(
            cb, i,
            InvoiceOverviewPartyNames(
                joins.recipient.get("name"), joins.selectedSeller.get("name"), joins.senderProfile.get("name"),
            ),
            InvoiceOverviewTextStatusFilter(filter.filterText, filter.status),
        )
        val dimensions = textAndStatus + invoiceOverviewDimensionPredicates(
            cb, i,
            InvoiceOverviewDimensionPaths.of(joins.recipient, joins.selectedSeller, joins.senderProfile),
            filter.toDimensions(),
        )
        val predicates = if (afterId != null) dimensions + cb.greaterThan(i.get("id"), afterId) else dimensions
        return HeaderPredicates(predicates.toTypedArray(), filterParam)
    }

    /** Maps the export filter's six dimension fields to the shared predicate parameter object —
     *  the export counterpart of the grid's `OverviewFilters.toDimensions()`. */
    private fun InvoiceExportFilter.toDimensions() = InvoiceOverviewDimensions(
        reverseCharge = reverseCharge,
        validationStatus = validationStatus,
        recipientId = recipientId,
        senderProfileId = senderProfileId,
        dateFrom = dateFrom,
        dateTo = dateTo,
    )

    private fun headerSelections(cb: CriteriaBuilder, i: Root<Invoice>, joins: HeaderJoins): List<Selection<*>> =
        identificationSelections(i) +
            dateSelections(i) +
            paymentTermSelections(i) +
            sellerSelections(cb, i, joins) +
            buyerSelections(joins) +
            referenceSelections(i) +
            cancellationSelections(i) +
            amountSelections(cb, i) +
            auditSelections(i)

    private fun identificationSelections(i: Root<Invoice>): List<Selection<*>> = listOf(
        i.get<Long>("id"),
        i.get<String>("invoiceNumberPrefix"),
        i.get<Long>("invoiceNumberSequence"),
        i.get("typeCode"),
        i.get("status"),
        i.get("validationStatus"),
        i.get("invoiceFormat"),
        i.get<Any>("documentLanguage"),
    )

    private fun dateSelections(i: Root<Invoice>): List<Selection<*>> = listOf(
        i.get<LocalDate>("invoiceDate"),
        i.get<LocalDate>("deliveryDate"),
        i.get<LocalDate>("servicePeriodFrom"),
        i.get<LocalDate>("servicePeriodTo"),
        i.get<LocalDate>("dueDate"),
        i.get<Int>("paymentTermsDays"),
    )

    private fun paymentTermSelections(i: Root<Invoice>): List<Selection<*>> {
        val snapshot = i.get<Any>("paymentTermSnapshot")
        return listOf(
            snapshot.get<String>("name"),
            snapshot.get<Any>("discountPercent"),
            snapshot.get<Int>("discountDays"),
        )
    }

    private fun sellerSelections(cb: CriteriaBuilder, i: Root<Invoice>, joins: HeaderJoins): List<Selection<*>> {
        val s = joins.selectedSeller
        val sp = joins.senderProfile
        val sSnap = joins.sellerSnapshot
        return listOf(
            cb.coalesce<String>(s.get("name"), sp.get("name")),
            cb.coalesce<String>(s.get(ATTR_VAT_ID), sp.get(ATTR_VAT_ID)),
            cb.coalesce<String>(s.get(ATTR_TAX_NUMBER), sp.get(ATTR_TAX_NUMBER)),
            cb.coalesce<String>(s.get<PartyIdentifier>(ATTR_SELLER_IDENTIFIER).get(ATTR_VALUE), sp.get<PartyIdentifier>(ATTR_SELLER_IDENTIFIER).get(ATTR_VALUE)),
            cb.coalesce<String>(s.get<PartyIdentifier>(ATTR_SELLER_IDENTIFIER).get(ATTR_SCHEME_ID), sp.get<PartyIdentifier>(ATTR_SELLER_IDENTIFIER).get(ATTR_SCHEME_ID)),
            cb.coalesce<String>(s.get("commercialRegisterNumber"), sp.get("commercialRegisterNumber")),
            cb.coalesce<String>(s.get("legalRegistrationSchemeId"), sp.get("legalRegistrationSchemeId")),
            cb.coalesce<String>(s.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get(ATTR_VALUE), sp.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get(ATTR_VALUE)),
            cb.coalesce<String>(s.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get(ATTR_SCHEME_ID), sp.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get(ATTR_SCHEME_ID)),
            i.get<Any>("sellerVatRegistrationCountry"),
            sSnap.get<Long>("id"),
            sSnap.get<String>("name"),
            sSnap.get<String>(ATTR_VAT_ID),
            sSnap.get<String>(ATTR_TAX_NUMBER),
            sSnap.get<PartyIdentifier>(ATTR_IDENTIFIER).get<String>(ATTR_VALUE),
            sSnap.get<PartyIdentifier>(ATTR_IDENTIFIER).get<String>(ATTR_SCHEME_ID),
            sSnap.get<LegalRegistrationIdentifier>("legalRegistration").get<String>(ATTR_VALUE),
            sSnap.get<LegalRegistrationIdentifier>("legalRegistration").get<String>(ATTR_SCHEME_ID),
            sSnap.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get<String>(ATTR_VALUE),
            sSnap.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get<String>(ATTR_SCHEME_ID),
        )
    }

    private fun buyerSelections(joins: HeaderJoins): List<Selection<*>> {
        val r = joins.recipient
        val bSnap = joins.buyerSnapshot
        return listOf(
            r.get<String>("name"),
            r.get<String>(ATTR_VAT_ID),
            r.get<PartyIdentifier>("buyerIdentifier").get<String>(ATTR_VALUE),
            r.get<PartyIdentifier>("buyerIdentifier").get<String>(ATTR_SCHEME_ID),
            r.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get<String>(ATTR_VALUE),
            r.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get<String>(ATTR_SCHEME_ID),
            bSnap.get<Long>("id"),
            bSnap.get<String>("name"),
            bSnap.get<String>(ATTR_VAT_ID),
            bSnap.get<PartyIdentifier>(ATTR_IDENTIFIER).get<String>(ATTR_VALUE),
            bSnap.get<PartyIdentifier>(ATTR_IDENTIFIER).get<String>(ATTR_SCHEME_ID),
            bSnap.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get<String>(ATTR_VALUE),
            bSnap.get<ElectronicAddress>(ATTR_ELECTRONIC_ADDRESS).get<String>(ATTR_SCHEME_ID),
        )
    }

    /** [InvoiceReferences] sits on the entity's `referenceFields` FIELD, never the `references`
     *  Kotlin accessor — a Criteria path only ever addresses the mapped
     *  attribute, so this alone already cannot materialise/dirty the embedded composite the way
     *  calling `invoice.references` on a loaded entity instance would. */
    private fun referenceSelections(i: Root<Invoice>): List<Selection<*>> {
        val refs = i.get<InvoiceReferences>("referenceFields")
        return listOf(
            refs.get<String>("buyerReference"),
            refs.get<String>("purchaseOrderReference"),
            refs.get<String>("orderReference"),
            refs.get<LocalDate>("orderReferenceDate"),
            refs.get<String>("offerReference"),
            refs.get<LocalDate>("offerReferenceDate"),
            refs.get<String>("projectNumber"),
            refs.get<String>("costCenter"),
        )
    }

    private fun cancellationSelections(i: Root<Invoice>): List<Selection<*>> = listOf(
        i.get<InvoiceNumber>("originalInvoiceNumber"),
        i.get<LocalDate>("originalInvoiceDate"),
    )

    private fun amountSelections(cb: CriteriaBuilder, i: Root<Invoice>): List<Selection<*>> {
        val totals = i.get<InvoiceTotals>("totals")
        return listOf(
            i.get("currency"),
            i.get<Boolean>("reverseCharge"),
            totals.get(ATTR_NET_AMOUNT),
            totals.get("vatAmount"),
            totals.get("grossAmount"),
            cb.size(i.get<MutableList<InvoiceItem>>("items")),
            cb.size(i.get<MutableList<Receipt>>("receipts")),
            i.get<Boolean>("needsRecalculation"),
        )
    }

    private fun auditSelections(i: Root<Invoice>): List<Selection<*>> = listOf(
        i.get("createdAt"),
        i.get<String>("createdBy"),
        i.get("updatedAt"),
        i.get<String>("updatedBy"),
        i.get<Long>("version"),
        i.get<InvoiceTexts>("texts").get<String>("freeText"),
    )

    private companion object {
        private const val ATTR_SORT_ORDER = "sortOrder"
        private const val ATTR_TRAVEL_DATE = "travelDate"
        private const val ATTR_NET_AMOUNT = "netAmount"
        private const val ATTR_VAT_ID = "vatId"
        private const val ATTR_TAX_NUMBER = "taxNumber"
        private const val ATTR_SELLER_IDENTIFIER = "sellerIdentifier"
        private const val ATTR_IDENTIFIER = "identifier"
        private const val ATTR_VALUE = "value"
        private const val ATTR_SCHEME_ID = "schemeId"
        private const val ATTR_ELECTRONIC_ADDRESS = "electronicAddress"
    }
}
