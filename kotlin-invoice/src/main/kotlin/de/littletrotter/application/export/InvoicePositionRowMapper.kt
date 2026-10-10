package de.littletrotter.application.export

import de.littletrotter.application.export.RowProjectionSupport.bigDecimal
import de.littletrotter.application.export.RowProjectionSupport.date
import de.littletrotter.application.export.RowProjectionSupport.enumOrDefault
import de.littletrotter.application.export.RowProjectionSupport.int
import de.littletrotter.application.export.RowProjectionSupport.long
import de.littletrotter.application.export.RowProjectionSupport.money
import de.littletrotter.application.export.RowProjectionSupport.str
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.PositionNumberFormatter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pure mapping from the raw scalar projection [de.littletrotter.infrastructure.persistence
 * .InvoiceExportReadRepositoryImpl] reads into a fully-typed [InvoicePositionExportRow]. No
 * database access, no i18n resolution (that is [de.littletrotter.infrastructure.export.workbook
 * .InvoicePositionSheetWriter]'s job) — same shape as [InvoiceHeaderRowMapper], deliberately a
 * plain function over an `Array<Any?>` so it is unit-testable without a Testcontainers Postgres.
 *
 * row carries every column the item's own Criteria tuple query selects (see
 * [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl
 * .positionSelections] for the exact selection list); invoiceNumber (one native-SQL lookup per
 * invoice, the same bypass reused here), sellerItemIdentifier/buyerItemIdentifier
 * (one batched `b2b_field_value` query for the whole page of items) and
 * receiptFileNames (one batched join query for the whole page of items) all come from sibling
 * batch loads keyed by item id, never a per-row query.
 *
 * Column layout of row (0-indexed, named in [Idx]):
 * ```
 *  0 item.id (join key only, not a sheet column)   12 exemptionReasonCode
 *  1 positionId                                    13 exemptionReasonText
 *  2 sortOrder                                      14 taxLegalBasis
 *  3 category                                       15 netAmount
 *  4 description                                     16 vatAmount
 *  5 itemDescription                                  17 grossAmount
 *  6 quantity                                          18 servicePeriodFrom
 *  7 unit                                               19 servicePeriodTo
 *  8 unitPriceNet                                        20 costCenter
 *  9 taxCategory                                          21 deliveryNoteReference
 * 10 taxRateSnapshot                                       22 articleRef.articleNumber (LEFT JOIN)
 * 11 taxRateRef.rate (LEFT JOIN)
 * 12 taxRuleCode
 * 24 invoice.currency (R7)
 * 25 tripFixedNetPrice (`TRIP` rows only)
 * ```
 */
object InvoicePositionRowMapper {

    /** Which invoice a position row belongs to — bundled so [fromRow] takes one parameter for it
     *  instead of two (LongParameterList). */
    data class InvoiceIdentity(val invoiceId: Long, val invoiceNumber: String)

    fun fromRow(
        row: Array<Any?>,
        invoice: InvoiceIdentity,
        b2bIdentifiers: Pair<String?, String?>,
        receiptFileNames: List<String>,
    ): InvoicePositionExportRow {
        val (sellerItemIdentifier, buyerItemIdentifier) = b2bIdentifiers
        return InvoicePositionExportRow(
            invoiceId = invoice.invoiceId,
            invoiceNumber = invoice.invoiceNumber,
            positionId = row.str(Idx.POSITION_ID).orEmpty(),
            sortOrder = row.int(Idx.SORT_ORDER) ?: 0,
            category = row.enumOrDefault(Idx.CATEGORY, InvoiceItemCategory.SERVICE),
            description = row.str(Idx.DESCRIPTION).orEmpty(),
            itemDescription = row.str(Idx.ITEM_DESCRIPTION),
            sellerItemIdentifier = sellerItemIdentifier,
            buyerItemIdentifier = buyerItemIdentifier,
            articleNumber = row.str(Idx.ARTICLE_NUMBER),
            quantity = row.bigDecimal(Idx.QUANTITY) ?: BigDecimal.ONE,
            unit = row.enumOrDefault(Idx.UNIT, InvoiceItemUnit.HOUR),
            unitPriceNet = row.money(Idx.UNIT_PRICE_NET) ?: Money.ZERO,
            taxCategoryCode = row.str(Idx.TAX_CATEGORY),
            // The missing-tax-rate border case, reproduced without ever calling
            // InvoiceItem.effectiveTaxRate() on a managed entity: the frozen snapshot wins when
            // present, the live rate is the fallback, and both absent stays null (never a thrown
            // exception that would take the whole export down over one row).
            taxRatePercent = row.bigDecimal(Idx.TAX_RATE_SNAPSHOT) ?: row.bigDecimal(Idx.TAX_RATE_REF_RATE),
            taxRuleCode = row.str(Idx.TAX_RULE_CODE),
            exemptionReasonCode = row.str(Idx.EXEMPTION_REASON_CODE),
            exemptionReasonText = row.str(Idx.EXEMPTION_REASON_TEXT),
            taxLegalBasis = row.str(Idx.TAX_LEGAL_BASIS),
            netAmount = row.money(Idx.NET_AMOUNT) ?: Money.ZERO,
            vatAmount = row.money(Idx.VAT_AMOUNT) ?: Money.ZERO,
            grossAmount = row.money(Idx.GROSS_AMOUNT) ?: Money.ZERO,
            servicePeriodFrom = row.date(Idx.SERVICE_PERIOD_FROM),
            servicePeriodTo = row.date(Idx.SERVICE_PERIOD_TO),
            costCenter = row.str(Idx.COST_CENTER),
            deliveryNoteReference = row.str(Idx.DELIVERY_NOTE_REFERENCE),
            receiptsCount = receiptFileNames.size,
            receiptFileNames = receiptFileNames.takeIf { it.isNotEmpty() }?.joinToString(", "),
            // R7 — the invoice's own currency, read off this row rather than assumed, so the sheet
            // formats this amount correctly even when mode B mixes several currencies.
            currency = row.enumOrDefault(Idx.CURRENCY, Currency.EUR),
            tripFixedNetPrice = row.money(Idx.TRIP_FIXED_NET_PRICE),
        )
    }

    /**
     * Expands a `TRIP` level-0 row (already produced by [fromRow]) into its own detail rows:
     * one per [expenses] entry, then one per [perDiemDays] entry, in that order (rk-6 item 1) —
     * numbered "3.1", "3.2", … continuously across both lists. Returns an empty list for a trip with
     * neither (README border case 1: a fixed-price-only workshop). Every detail row copies
     * [position]'s own link columns ([InvoicePositionExportRow.invoiceId]/[InvoicePositionExportRow
     * .invoiceNumber]/[InvoicePositionExportRow.currency]) so it can stand on its own in the sheet;
     * [InvoicePositionExportRow.category] is left as [position]'s own `TRIP` (not blanked) so a
     * reader filtering by category still sees every row of the trip together.
     */
    fun tripDetailRows(
        position: InvoicePositionExportRow,
        expenses: List<TripExpenseRowInput>,
        perDiemDays: List<PerDiemDayRowInput>,
    ): List<InvoicePositionExportRow> {
        val expenseRows = expenses.mapIndexed { index, expense ->
            detailRow(position, index + 1).copy(
                description = expense.description,
                quantity = expense.quantity,
                unit = expense.unit,
                unitPriceNet = expense.unitPriceNet,
                netAmount = expense.netAmount,
                tripKind = expense.kind,
                travelDate = expense.travelDate,
                receiptsCount = if (expense.receiptFileName != null) 1 else 0,
                receiptFileNames = expense.receiptFileName,
            )
        }
        val perDiemRows = perDiemDays.mapIndexed { index, day ->
            detailRow(position, expenses.size + index + 1).copy(
                quantity = BigDecimal.ONE,
                unit = InvoiceItemUnit.DAY,
                unitPriceNet = day.claimableAmount,
                netAmount = day.claimableAmount,
                travelDate = day.date,
                perDiem = PerDiemDetailColumns(
                    isArrivalDeparture = day.isArrivalDeparture,
                    breakfastDeduction = day.breakfastDeduction,
                    lunchDeduction = day.lunchDeduction,
                    dinnerDeduction = day.dinnerDeduction,
                    employerContribution = day.employerContribution,
                    claimableAmount = day.claimableAmount,
                ),
            )
        }
        return expenseRows + perDiemRows
    }

    /** The shared skeleton every detail row starts from: link columns copied from [position], no
     *  tax/gross/vat/context columns (rk-6 item 2 — "no tax columns"), the "3.$sequence" numbering
     *  rk-6 item 1 spells out written onto both [InvoicePositionExportRow.positionId] (so the sheet
     *  itself shows it) and [InvoicePositionExportRow.detailNumber] (so a caller can read it back
     *  without re-parsing the display column). */
    private fun detailRow(position: InvoicePositionExportRow, sequence: Int): InvoicePositionExportRow {
        val detailNumber = PositionNumberFormatter.format(position.positionId, sequence)
        return InvoicePositionExportRow(
            invoiceId = position.invoiceId,
            invoiceNumber = position.invoiceNumber,
            positionId = detailNumber,
            sortOrder = sequence,
            category = position.category,
            currency = position.currency,
            level = 1,
            parentPositionId = position.positionId,
            detailNumber = detailNumber,
        )
    }

    /** The item id column every batch join (B2B values, receipts) keys its lookup map by — reads
     *  index 0 of the same [row] [fromRow] otherwise maps, so a caller building those lookup maps
     *  never has to hard-code the index a second time. */
    fun itemId(row: Array<Any?>): Long = row.long(Idx.ITEM_ID) ?: 0L

    /** Every column index [fromRow]/[itemId] read, named so no literal column number appears in
     *  the mapping logic itself — see this file's own KDoc table for the full layout. */
    private object Idx {
        const val ITEM_ID = 0
        const val POSITION_ID = 1
        const val SORT_ORDER = 2
        const val CATEGORY = 3
        const val DESCRIPTION = 4
        const val ITEM_DESCRIPTION = 5
        const val QUANTITY = 6
        const val UNIT = 7
        const val UNIT_PRICE_NET = 8
        const val TAX_CATEGORY = 9
        const val TAX_RATE_SNAPSHOT = 10
        const val TAX_RATE_REF_RATE = 11
        const val TAX_RULE_CODE = 12
        const val EXEMPTION_REASON_CODE = 13
        const val EXEMPTION_REASON_TEXT = 14
        const val TAX_LEGAL_BASIS = 15
        const val NET_AMOUNT = 16
        const val VAT_AMOUNT = 17
        const val GROSS_AMOUNT = 18
        const val SERVICE_PERIOD_FROM = 19
        const val SERVICE_PERIOD_TO = 20
        const val COST_CENTER = 21
        const val DELIVERY_NOTE_REFERENCE = 22
        const val ARTICLE_NUMBER = 23
        const val CURRENCY = 24
        const val TRIP_FIXED_NET_PRICE = 25
    }
}

/**
 * One [de.littletrotter.domain.invoice.TripExpense] line, already resolved to plain scalars
 * by [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl]'s own batched
 * query (one query for the whole page of trip items, never one per trip — the same discipline
 * [InvoicePositionRowMapper.itemId]'s sibling batch loads already follow). [receiptFileName] is
 * `null` unless this one expense line has its own receipt attached.
 */
data class TripExpenseRowInput(
    val kind: TripExpenseKind,
    val description: String,
    val travelDate: LocalDate?,
    val quantity: BigDecimal,
    val unit: InvoiceItemUnit,
    val unitPriceNet: Money,
    val netAmount: Money,
    val receiptFileName: String?,
)

/** One per-diem day of a trip, the [InvoicePositionRowMapper.tripDetailRows] counterpart of
 *  [TripExpenseRowInput]. [claimableAmount] is the already-calculated, stored amount (mirrors
 *  [de.littletrotter.domain.reference.TravelExpenseEntry.calculatedAmount]) — this row model never
 *  recomputes it. */
data class PerDiemDayRowInput(
    val date: LocalDate?,
    val isArrivalDeparture: Boolean,
    val breakfastDeduction: Boolean,
    val lunchDeduction: Boolean,
    val dinnerDeduction: Boolean,
    val employerContribution: Money,
    val claimableAmount: Money,
)
