package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.TravelExpenseDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.dto.TripExpenseDto
import de.littletrotter.shared.Money
import jakarta.persistence.Tuple
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The item/trip cohesive group of [InvoiceReadRepositoryImpl.findInvoiceDetailDto]: the
 * items projection itself, the batched receipt/travel/trip-expense queries keyed by item id, and
 * the final per-item enrichment ([mapAll] is the one entry point the repository calls; every other
 * function here is a private step of it).
 */
internal object InvoiceDetailItemFieldMapper {

    /** Bundles the four id-keyed lookups [enrichItems] needs, so neither it nor [mapAll] exceeds
     *  detekt's `LongParameterList.functionThreshold` (5). */
    private data class ItemEnrichmentMaps(
        val tripFlagsByItem: Map<Long, Pair<Boolean, Money?>>,
        val receiptIdsByItem: Map<Long, List<Long>>,
        val travelsByItem: Map<Long, List<TravelExpenseDto>>,
        val expensesByItem: Map<Long, List<TripExpenseDto>>,
    )

    fun mapAll(
        itemRows: List<Tuple>,
        receiptRows: List<Array<*>>,
        travelRows: List<Array<*>>,
        expenseRows: List<Array<*>>,
    ): List<ItemDto> {
        val (items, tripFlagsByItem) = mapItemRows(itemRows)
        val maps = ItemEnrichmentMaps(
            tripFlagsByItem = tripFlagsByItem,
            receiptIdsByItem = mapReceiptRows(receiptRows),
            travelsByItem = mapTravelRows(travelRows),
            expensesByItem = mapExpenseRows(expenseRows),
        )
        return enrichItems(items, maps)
    }

    // Items (lightweight). Project Money value objects directly instead of dereferencing `.amount`
    // because Hibernate treats converter-backed fields as basic-valued paths in JPQL.
    // S6619 — Tuple with explicit aliases, not a positional Object[]: a keyed Tuple.get(alias)
    // is a genuine Any? read every time, so the nullable columns below (unit, category,
    // tripFixedNetPrice, ...) keep an honest safe-call instead of Sonar mis-reading a star-projected
    // Array<*> element as non-null.
    private fun mapItemRows(rows: List<Tuple>): Pair<List<ItemDto>, Map<Long, Pair<Boolean, Money?>>> {
        // A TRIP item's tripDetailedOnInvoice/tripFixedNetPrice, keyed by item id; carried
        // alongside (not inside) ItemDto until the TripDto is assembled in enrichItems.
        val tripFlagsByItem = mutableMapOf<Long, Pair<Boolean, Money?>>()
        val items = rows.map { row ->
            // positionId/description/quantity are all non-nullable columns on
            // InvoiceItem, so their `?:` fallback was unreachable for any persisted row.
            val itemId = (row.get("id") as Number).toLong()
            val positionId = row.get("positionId") as String
            val description = row.get("description") as String
            val quantity = row.get("quantity") as BigDecimal
            // Unlike positionId/description/quantity above, unit is one of this
            // projection's genuinely optional columns.
            val unit = row.get("unit")?.toString()
            val tripDetailedOnInvoice = row.get("tripDetailedOnInvoice") as Boolean
            val tripFixedNetPrice = InvoiceDetailRowSupport.parseMoney(row.get("tripFixedNetPrice"))
            tripFlagsByItem[itemId] = tripDetailedOnInvoice to tripFixedNetPrice
            ItemDto(
                id = itemId, positionId = positionId, description = description, quantity = quantity,
                unit = unit,
                unitPriceNet = InvoiceDetailRowSupport.parseMoney(row.get("unitPriceNet")),
                taxRate = InvoiceDetailRowSupport.parseBigDecimal(row.get("taxRate")),
                taxRateRefId = (row.get("taxRateRefId") as Number?)?.toLong(),
                netAmount = InvoiceDetailRowSupport.parseMoney(row.get("netAmount")),
                vatAmount = InvoiceDetailRowSupport.parseMoney(row.get("vatAmount")),
                grossAmount = InvoiceDetailRowSupport.parseMoney(row.get("grossAmount")),
                receiptsCount = 0,
                receiptIds = emptyList(),
                version = (row.get("version") as Number?)?.toLong(),
                // category is one of this projection's genuinely optional columns.
                category = row.get("category")?.toString(),
                // sortOrder is a non-nullable Int column on InvoiceItem.
                sortOrder = (row.get("sortOrder") as Number).toInt(),
                deliveryNoteReference = InvoiceDetailRowSupport.parseString(row.get("deliveryNoteReference")),
                itemDescription = InvoiceDetailRowSupport.parseString(row.get("itemDescription")),
                buyerItemDescription = InvoiceDetailRowSupport.parseString(row.get("buyerItemDescription")),
                articleRefId = (row.get("articleRefId") as Number?)?.toLong(),
                exemptionReasonText = InvoiceDetailRowSupport.parseString(row.get("exemptionReasonText")),
                taxLegalBasis = InvoiceDetailRowSupport.parseString(row.get("taxLegalBasis")),
                taxCategory = InvoiceDetailRowSupport.parseString(row.get("taxCategory")),
                servicePeriodFrom = row.get("servicePeriodFrom") as LocalDate?,
                servicePeriodTo = row.get("servicePeriodTo") as LocalDate?,
            )
        }
        return items to tripFlagsByItem
    }

    /** receipt IDs per item (for correct detached-entity merge and UI count display). */
    private fun mapReceiptRows(rows: List<Array<*>>): Map<Long, List<Long>> = rows
        .map { raw -> ((raw[0] as Number).toLong()) to (raw[1] as Number?)?.toLong() }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.filterNotNull() }

    // travel entries
    // Indices: 0=id, 1=itemId, 2=travelDate, 3=country, 4=isArrivalDeparture,
    //          5=breakfast, 6=lunch, 7=dinner, 8=calculatedAmount,
    //          9=version, 10=fullDayRate, 11=arrivalDepartureRate, 12=employerContribution,
    //          13=description, 14=participant, 15=perDiemRateId, 16=shortName
    private fun mapTravelRows(rows: List<Array<*>>): Map<Long, List<TravelExpenseDto>> = rows.map { r ->
        val teId = (r[0] as Number).toLong()
        val itemId = (r[1] as Number).toLong()
        val travelDate = r[2] as LocalDate?
        // fullDayRateSnapshot/arrivalDepartureRateSnapshot/employerContribution (Money)
        // and description/participant (String) are all non-nullable columns on TravelExpenseEntry,
        // so their `?:` fallback was unreachable for any persisted row.
        TravelExpenseDto(
            id = teId, invoiceItemId = itemId, travelDate = travelDate,
            countryAlpha2 = r[COL_TRAVEL_COUNTRY].toString(),
            isArrivalDeparture = r[COL_TRAVEL_IS_ARRIVAL_DEPARTURE] as Boolean,
            breakfastDeduction = r[COL_TRAVEL_BREAKFAST] as Boolean,
            lunchDeduction = r[COL_TRAVEL_LUNCH] as Boolean,
            dinnerDeduction = r[COL_TRAVEL_DINNER] as Boolean,
            calculatedAmount = InvoiceDetailRowSupport.parseMoney(r[COL_TRAVEL_CALCULATED_AMOUNT]),
            version = (r[COL_TRAVEL_VERSION] as Number?)?.toLong(),
            fullDayRateSnapshot = r[COL_TRAVEL_FULL_DAY_RATE] as Money,
            arrivalDepartureRateSnapshot = r[COL_TRAVEL_ARRIVAL_DEPARTURE_RATE] as Money,
            employerContribution = r[COL_TRAVEL_EMPLOYER_CONTRIBUTION] as Money,
            description = r[COL_TRAVEL_DESCRIPTION] as String,
            participant = r[COL_TRAVEL_PARTICIPANT] as String,
            perDiemRateId = (r[COL_TRAVEL_PER_DIEM_RATE_ID] as Number?)?.toLong(),
            shortName = InvoiceDetailRowSupport.parseString(r[COL_TRAVEL_SHORT_NAME]),
        )
    }.groupBy { it.invoiceItemId }

    // Trip expenses, batched per invoice (one query, no N+1 per trip item).
    // Indices: 0=id, 1=itemId, 2=kind, 3=description, 4=travelDate, 5=quantity, 6=unit,
    //          7=unitPriceNet, 8=netAmount, 9=receiptId, 10=sortOrder, 11=version, 12=rateProvided
    private fun mapExpenseRows(rows: List<Array<*>>): Map<Long, List<TripExpenseDto>> = rows.map { r ->
        // kind/description/quantity/unitPriceNet/netAmount/sortOrder/rateProvided are all
        // non-nullable columns on TripExpense, so their `?:` fallback was unreachable for any
        // persisted row; only travelDate/unit/receiptId are genuinely optional here.
        TripExpenseDto(
            id = (r[0] as Number).toLong(),
            invoiceItemId = (r[1] as Number).toLong(),
            kind = r[2].toString(),
            description = r[COL_EXPENSE_DESCRIPTION] as String,
            travelDate = r[COL_EXPENSE_TRAVEL_DATE] as LocalDate?,
            quantity = r[COL_EXPENSE_QUANTITY] as BigDecimal,
            unit = r[COL_EXPENSE_UNIT].toString(),
            unitPriceNet = r[COL_EXPENSE_UNIT_PRICE_NET] as Money,
            netAmount = r[COL_EXPENSE_NET_AMOUNT] as Money,
            receiptId = (r[COL_EXPENSE_RECEIPT_ID] as Number?)?.toLong(),
            sortOrder = (r[COL_EXPENSE_SORT_ORDER] as Number).toInt(),
            version = (r[COL_EXPENSE_VERSION] as Number?)?.toLong(),
            rateProvided = r[COL_EXPENSE_RATE_PROVIDED] as Boolean,
        )
    }.groupBy { it.invoiceItemId }

    /** attach receiptsCount, receiptIds and (for a TRIP item) its trip detail. */
    private fun enrichItems(items: List<ItemDto>, maps: ItemEnrichmentMaps): List<ItemDto> = items.map { item ->
        val trip = if (item.category == "TRIP") {
            // tripFlagsByItem[item.id] is unconditionally populated for every item id in
            // mapItemRows (its own `tripFlagsByItem[itemId] = ...` line runs regardless of
            // category), so a TRIP item's own entry can never be missing here — the `?.let` null
            // arm was unreachable. `!!`/`getValue` documents that instead of hiding it behind a
            // dead branch.
            maps.tripFlagsByItem.getValue(item.id).let { (detailedOnInvoice, fixedNetPrice) ->
                val expenses = maps.expensesByItem[item.id] ?: emptyList()
                val perDiemDays = maps.travelsByItem[item.id] ?: emptyList()
                val calculatedNet = expenses.fold(Money.ZERO) { acc, e -> acc + e.netAmount } +
                    perDiemDays.fold(Money.ZERO) { acc, d -> acc + (d.calculatedAmount ?: Money.ZERO) }
                TripDto(
                    detailedOnInvoice = detailedOnInvoice,
                    fixedNetPrice = fixedNetPrice,
                    calculatedNet = calculatedNet,
                    expenses = expenses,
                    perDiemDays = perDiemDays,
                )
            }
        } else {
            null
        }
        item.copy(
            receiptsCount = maps.receiptIdsByItem[item.id]?.size ?: 0,
            receiptIds = maps.receiptIdsByItem[item.id] ?: emptyList(),
            trip = trip,
        )
    }

    /** The travel/trip-expense projections are read as a positional `Array<*>` (no `Tuple`
     *  aliasing there, unlike the header/items queries); named so detekt's MagicNumber rule
     *  doesn't flag them. */
    private const val COL_TRAVEL_COUNTRY = 3
    private const val COL_TRAVEL_IS_ARRIVAL_DEPARTURE = 4
    private const val COL_TRAVEL_BREAKFAST = 5
    private const val COL_TRAVEL_LUNCH = 6
    private const val COL_TRAVEL_DINNER = 7
    private const val COL_TRAVEL_CALCULATED_AMOUNT = 8
    private const val COL_TRAVEL_VERSION = 9
    private const val COL_TRAVEL_FULL_DAY_RATE = 10
    private const val COL_TRAVEL_ARRIVAL_DEPARTURE_RATE = 11
    private const val COL_TRAVEL_EMPLOYER_CONTRIBUTION = 12
    private const val COL_TRAVEL_DESCRIPTION = 13
    private const val COL_TRAVEL_PARTICIPANT = 14
    private const val COL_TRAVEL_PER_DIEM_RATE_ID = 15
    private const val COL_TRAVEL_SHORT_NAME = 16

    private const val COL_EXPENSE_DESCRIPTION = 3
    private const val COL_EXPENSE_TRAVEL_DATE = 4
    private const val COL_EXPENSE_QUANTITY = 5
    private const val COL_EXPENSE_UNIT = 6
    private const val COL_EXPENSE_UNIT_PRICE_NET = 7
    private const val COL_EXPENSE_NET_AMOUNT = 8
    private const val COL_EXPENSE_RECEIPT_ID = 9
    private const val COL_EXPENSE_SORT_ORDER = 10
    private const val COL_EXPENSE_VERSION = 11
    private const val COL_EXPENSE_RATE_PROVIDED = 12
}
