package de.littletrotter.application.export

import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One fully-mapped row of the `Positionen` sheet, one per [de.littletrotter.domain.invoice
 * .InvoiceItem]. Mirrors [InvoiceHeaderExportRow]'s own shape: every enum stays a real Kotlin enum
 * (resolved to an i18n label only by [de.littletrotter.infrastructure.export.workbook
 * .InvoicePositionSheetWriter]), every amount is [Money], `BigDecimal` stays reserved for the
 * quantity and the tax-rate percentage. Produced by [InvoicePositionRowMapper] from the raw
 * projection [de.littletrotter.infrastructure.persistence.InvoiceExportReadRepositoryImpl] reads.
 *
 * Field order follows the sheet's own column order so the two stay easy to read side by
 * side; it has no bearing on how Excel lays out the columns — that is entirely
 * [de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter]'s job.
 *
 * [taxRatePercent] is `null` exactly when [de.littletrotter.domain.invoice.InvoiceItem
 * .effectiveTaxRate] would throw (no frozen snapshot and no live `taxRateRef`) — this row model
 * never calls that method on a managed entity (which would take the whole export down on one bad
 * row); instead the mapper reproduces its exact fallback
 * (`taxRateSnapshot ?: taxRateRef?.rate`) over plain projected columns, and a `null` here is the
 * single, deliberate signal [de.littletrotter.infrastructure.export.workbook
 * .InvoicePositionSheetWriter] uses to write an empty rate cell and the "Steuersatz fehlt" verdict
 * instead of a control-formula deviation.
 */
data class InvoicePositionExportRow(
    // ── Link and order ──────────────────────────────────────────────────────
    val invoiceId: Long,
    /** The same raw, as-stored invoice number [InvoiceHeaderExportRow.invoiceNumber] carries for
     *  this row's own invoice — empty for a draft. Unlike the header sheet, the position sheet
     *  carries no malformed-number marker of its own; the `Rechnungs-Id` column is what keeps two
     *  invoices' positions apart regardless. */
    val invoiceNumber: String = "",
    val positionId: String = "",
    val sortOrder: Int = 0,

    // ── Description ─────────────────────────────────────────────────────────
    val category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
    /** BT-153, mandatory on a valid item — never blank in practice. */
    val description: String = "",
    /** BT-154 — deliberately no fallback to [description]. */
    val itemDescription: String? = null,

    // ── Article identifiers ─────────────────────────────────────────────────
    /** BT-155, loaded in one batched query keyed by item id — see [InvoiceExportReadRepository
     *  .findPositionRows]'s own KDoc. */
    val sellerItemIdentifier: String? = null,
    /** BT-156, same batched load as [sellerItemIdentifier]. */
    val buyerItemIdentifier: String? = null,
    val articleNumber: String? = null,

    // ── Quantity and price ──────────────────────────────────────────────────
    val quantity: BigDecimal = BigDecimal.ONE,
    /** Carried as the domain enum, not a pre-resolved symbol/UN-ECE-code pair — the writer derives
     *  both [InvoiceItemUnit.symbol] and [InvoiceItemUnit.uneceCode] from it at write time. */
    val unit: InvoiceItemUnit = InvoiceItemUnit.HOUR,
    /** May be negative — a discount line is a position with a negative unit price, never a
     *  separate discount field. */
    val unitPriceNet: Money = Money.ZERO,

    // ── Tax ──────────────────────────────────────────────────────────────────
    /** The frozen UNTDID 5305 code string, as stored — never derived, never re-encoded (mirrors
     *  [de.littletrotter.domain.invoice.InvoiceItem.taxCategory]'s own R1 design rule). The
     *  writer derives the human label from this via [de.littletrotter.shared.tax.VatCategory
     *  .fromCodeOrNull]. */
    val taxCategoryCode: String? = null,
    /** See this class's own KDoc: `null` means "missing", not "zero". */
    val taxRatePercent: BigDecimal? = null,
    val taxRuleCode: String? = null,
    val exemptionReasonCode: String? = null,
    val exemptionReasonText: String? = null,
    val taxLegalBasis: String? = null,

    // ── Amounts as stored ────────────────────────────────────────────────────
    val netAmount: Money = Money.ZERO,
    val vatAmount: Money = Money.ZERO,
    val grossAmount: Money = Money.ZERO,
    /** R7 — this position's own invoice currency; every money cell above is formatted in it,
     *  never assumed to be Euro. */
    val currency: Currency = Currency.EUR,

    // ── Context ─────────────────────────────────────────────────────────────
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val costCenter: String? = null,
    val deliveryNoteReference: String? = null,
    val receiptsCount: Int = 0,
    /** File names only, joined by `", "` — the receipt binaries themselves stay in the object
     *  store and never enter the workbook. `null` when [receiptsCount] is `0`. */
    val receiptFileNames: String? = null,

    // ── Trip position/detail rows ────────────────────────────────────
    /** `0` for every real [de.littletrotter.domain.invoice.InvoiceItem] row (including a `TRIP`
     *  item's own summary row), `1` for a synthetic detail row [InvoicePositionRowMapper
     *  .tripDetailRows] adds beneath it (one of the trip's own [de.littletrotter.domain.invoice
     *  .TripExpense] lines or per-diem days) — never a third level (README border case 8: sub-lines
     *  never nest further). */
    val level: Int = 0,
    /** [positionId] of the owning trip's own level-0 row — `null` for a level-0 row itself, always
     *  set for a level-1 row. Deliberately duplicates [positionId] rather than replacing it: a
     *  reader sees at a glance which rows belong together (a filled "Übergeordnet" cell) without
     *  losing the row's own display id. */
    val parentPositionId: String? = null,
    /** The sub-line number (`"3.1"`, `"3.2"`, …) a level-1 row's own [positionId] is *also* set to —
     *  never written back onto any domain `positionId` (README border case 8: presentation only).
     *  `null` for a level-0 row. */
    val detailNumber: String? = null,
    /** Which [de.littletrotter.domain.invoice.TripExpense] this level-1 row is, for the writer's
     *  own "Art" column label lookup — `null` for a level-0 row and for a per-diem-day level-1 row
     *  ([perDiem] is set instead; the two are mutually exclusive on a level-1 row). */
    val tripKind: TripExpenseKind? = null,
    /** The expense's or per-diem day's own date — `null` for a level-0 row, or a level-1 row with
     *  no date recorded. */
    val travelDate: LocalDate? = null,
    /** Set exactly for a per-diem-day level-1 row, `null` for every other row (a level-0 row, or a
     *  [tripKind] expense row). */
    val perDiem: PerDiemDetailColumns? = null,
    /** The trip's own fixed price ([de.littletrotter.domain.invoice.InvoiceItem.tripFixedNetPrice]),
     *  read by [de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter]'s own
     *  R4 control block to decide whether a `TRIP` level-0 row's control formula is the fixed price
     *  or the `SUM` of its own detail rows' net amounts (rk-6 item 2) — never shown in a column of
     *  its own, the control/Δ columns already carry it. `null` for every non-`TRIP` row and for a
     *  `TRIP` row priced from its parts. */
    val tripFixedNetPrice: Money? = null,
)

/**
 * The per-diem-day columns of a level-1 detail row (README border case: Verpflegungstage
 * carry no tax of their own, only these). Grouped into one nullable value rather than six more
 * top-level nullable fields on [InvoicePositionExportRow] — every field here is set together or not
 * at all (a row is a per-diem day, or it isn't), which a single `perDiem: PerDiemDetailColumns?`
 * expresses directly; six independent nullables would let a caller set some without the others for
 * no representable reason.
 */
data class PerDiemDetailColumns(
    val isArrivalDeparture: Boolean,
    val breakfastDeduction: Boolean,
    val lunchDeduction: Boolean,
    val dinnerDeduction: Boolean,
    val employerContribution: Money,
    val claimableAmount: Money,
)
