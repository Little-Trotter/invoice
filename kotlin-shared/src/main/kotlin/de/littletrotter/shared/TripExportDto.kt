package de.littletrotter.shared

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import java.math.BigDecimal

/**
 * One expense line of a business trip, exported for PDF/CII/Excel writers.
 * [kindLabelKey] is an i18n key (`trip.kind.hotel|transport|mileage|travelTime|other`), never a raw
 * label — every writer resolves it against its own label bundle, the same convention
 * [InvoiceItemDto.unitLabel]'s sibling [InvoiceItemDto.unitCode] already uses for machine-readable
 * values.
 */
data class TripExpenseExportDto(
    val kindLabelKey: String,
    val description: String,
    val travelDate: String? = null,
    val quantity: BigDecimal,
    val unitCode: String? = null,
    val unitLabel: String? = null,
    val unitPriceNet: Money,
    val netAmount: Money,
    val rateProvided: Boolean = true,
)

/** One per-diem day of a business trip, exported alongside [TripExpenseExportDto]. */
data class PerDiemDayExportDto(
    val date: String? = null,
    val isArrivalDeparture: Boolean,
    val breakfastDeduction: Boolean,
    val lunchDeduction: Boolean,
    val dinnerDeduction: Boolean,
    val claimableAmount: Money,
    /** The per-diem block's own optional description, carried per day because that is where it
     *  is stored; the workbook's per-diem overview would otherwise leave every row's
     *  description cell empty. `null` only for rows captured before the description existed. */
    val description: String? = null,
    /** The day's own base rate — `arrivalDepartureRateSnapshot` for an arrival/departure day,
     *  `fullDayRateSnapshot` otherwise — so the workbook overview can state the same unit price as
     *  the UI overview. `null` only for rows captured
     *  before this field existed. */
    val baseRate: Money? = null,
    val participant: String? = null,
    val employerContribution: Money? = null,
)

/**
 * The export-side detail of a [InvoiceItemDto] whose [InvoiceItemDto.category] is
 * [InvoiceItemCategory.TRIP]; `null` for every other category. [calculatedNet] is the trip's
 * price computed from [expenses]/[perDiemDays] alone (`InvoiceItem.calculatedTripNet`),
 * independent of [fixedNetPrice] — the Excel Δ-column needs both figures regardless of
 * which one the invoice actually printed ([InvoiceItemDto.netAmount]).
 */
data class TripExportDto(
    val detailedOnInvoice: Boolean,
    val fixedNetPrice: Money?,
    val calculatedNet: Money,
    val expenses: List<TripExpenseExportDto> = emptyList(),
    val perDiemDays: List<PerDiemDayExportDto> = emptyList(),
    /**
     * The wire pointer to this trip's workbook, producer-set
     * (`kotlin-invoice`'s `InvoiceDtoMapper`, which renders the workbook at job-publish time and
     * `SETEX`s it into Valkey — [TripWorkbookRefDto]'s own KDoc). `null` when no
     * [de.littletrotter.shared.PayloadExchange] was wired (a plain unit test) or rendering failed;
     * the invoice still exports, just without the BG-24 workbook attachment.
     */
    val workbookRef: TripWorkbookRefDto? = null,
    /**
     * Populated locally by `kotlin-xpdf`'s `JobConsumer` after resolving [workbookRef]
     * (base64, verified against [TripWorkbookRefDto.sha256]); never set by the producer and never
     * present in the JSON `DocumentJobPublisher` actually emits.
     */
    val resolvedWorkbookBase64: String? = null,
)
