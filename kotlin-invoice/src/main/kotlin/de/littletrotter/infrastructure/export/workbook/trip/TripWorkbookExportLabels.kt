package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.infrastructure.export.workbook.ExportSheetLabels
import de.littletrotter.shared.PerDiemDescriptionLabels

/** The "Dienstreise" key/value sheet's own labels: one field label per row, in the sheet's
 *  own fixed row order (see [TripDetailSheetWriter]'s private `Row` object). */
data class TripDetailSheetLabels(
    /** Exactly two entries: "Feld"/"Field", "Wert"/"Value" — reused verbatim from the invoice
     *  workbook's own cover sheet key/value block, same concept. */
    val columnHeaders: List<String>,
    val fieldTitle: String,
    val fieldPurpose: String,
    val fieldPeriodFrom: String,
    val fieldPeriodTo: String,
    val fieldInvoiceNumber: String,
    val fieldInvoiceDate: String,
    val fieldCustomer: String,
    val fieldCostCenter: String,
    val fieldTaxTreatment: String,
    val fieldCalculatedNet: String,
    val fieldFixedNetPrice: String,
    val fieldDelta: String,
    val fieldReceiptsCount: String,
    /** `{0}` = the tax rate percentage (a plain number, e.g. `19`, never `%`-formatted — the same
     *  convention [de.littletrotter.infrastructure.export.workbook.CellStyles.percent] documents). */
    val taxTreatmentInheritsRatePattern: String,
    /** "—" — written when there is no fixed price to show, and (consequently) no Δ to compute. */
    val noValueLabel: String,
)

/** The "Posten" sheet's own labels: the 14 column headers
 *  ([TripExpenseColumnKeys.ORDERED_KEYS]'s own order), the resolved text for every
 *  [de.littletrotter.shared.TripExpenseExportDto.kindLabelKey] this export ever produces, the
 *  per-diem row's own fixed "kind" label (per-diem days carry no [de.littletrotter.domain.invoice
 *  .TripExpenseKind] of their own), and the total row's label. */
data class TripExpenseSheetLabels(
    val columnHeaders: List<String>,
    /** Keyed by the DTO's own `kind.*` i18n key (`trip.kind.hotel` etc.) — [TripExpenseSheetWriter]
     *  looks a row's own [de.littletrotter.shared.TripExpenseExportDto.kindLabelKey] up here rather
     *  than resolving it itself, keeping the writer free of a `translate` closure (ExportSheetWriter's
     *  own KDoc explains why sheet writers carry no i18n bundle of their own). Falls back to the raw
     *  key itself for a key this map does not cover — defensive, never actually hit in production. */
    val kindLabels: Map<String, String>,
    val totalRowLabel: String,
)

/** Aggregates every label the trip workbook needs, the same "one aggregate per export"
 *  shape [de.littletrotter.infrastructure.export.workbook.InvoiceWorkbookExportLabels] already
 *  establishes. Built by [buildTripWorkbookExportLabels], consumed only by
 *  [TripWorkbookExportService] in this same package. */
data class TripWorkbookExportLabels(
    val exportSheet: ExportSheetLabels,
    val detailSheet: TripDetailSheetLabels,
    val expenseSheet: TripExpenseSheetLabels,
    // No `= PerDiemSheetLabels()` default — [buildTripWorkbookExportLabels] is this
    // class's one production caller and always supplies it explicitly, so a default here only
    // grew an unreachable `<init>$default` bridge no caller could ever invoke.
    val perDiemSheet: PerDiemSheetLabels,
    /** `{0}` = the trip's own title, `{1}` = the invoice number — composes the cover sheet's own
     *  subtitle ("Dienstreise «Titel» — Rechnung «Nummer»") *and* the "Auswahl" field row, exactly
     *  like [de.littletrotter.infrastructure.export.workbook.InvoiceWorkbookExportService]'s own
     *  `appliedSelection` already serves both purposes with one string. */
    val subtitlePattern: String,
)

data class PerDiemSheetLabels(
    val columnHeaders: List<String> = emptyList(),
    val title: String = "Verpflegungspauschalen",
    val totalRowLabel: String = "Summe",
    val kindLabel: String = "Verpflegungspauschale",
    val scopeDaysOnlyPattern: String = "{0,choice,0#0 Tage|1#1 Tag|1<{0} Tage}",
    val scopeNamedPattern: String = "{0,choice,0#0 Tage|1#1 Tag|1<{0} Tage} · {1}",
    val scopeCountedPattern: String = "{0,choice,0#0 Tage|1#1 Tag|1<{0} Tage} · {1} Teilnehmer",
    val descriptionLabels: PerDiemDescriptionLabels = PerDiemDescriptionLabels(
        arrivalDeparture = "An-/Abreise",
        fullDay = "24 Std.",
        breakfastDeduction = "− Früh.",
        lunchDeduction = "− Mittag",
        dinnerDeduction = "− Abend",
    ),
)

/** The "Dienstreise" sheet's own two column headers — reuses the invoice workbook cover's own
 *  "Feld"/"Wert" key pair (identical concept), not a new `tripExport.*` key. */
internal object TripDetailColumnKeys {
    val ORDERED_KEYS: List<String> = listOf(
        "invoiceExport.export.column.field",
        "invoiceExport.export.column.value",
    )
}

/** The "Posten" sheet's own 14 column headers, in [TripExpenseSheetWriter]'s own `Col` order. */
internal object TripExpenseColumnKeys {
    val ORDERED_KEYS: List<String> = listOf(
        "tripExport.posten.column.number",
        "tripExport.posten.column.kind",
        "tripExport.posten.column.description",
        "tripExport.posten.column.date",
        "tripExport.posten.column.quantity",
        "tripExport.posten.column.unit",
        "tripExport.posten.column.unitPriceNet",
        "tripExport.posten.column.netAmount",
        "tripExport.posten.column.arrivalDeparture",
        "tripExport.posten.column.breakfastDeduction",
        "tripExport.posten.column.lunchDeduction",
        "tripExport.posten.column.dinnerDeduction",
        "tripExport.posten.column.controlNet",
        "tripExport.posten.column.deltaNet",
    )
}

/** Every `trip.kind.*` key [de.littletrotter.application.invoice.InvoiceDtoMapper
 *  .exportLabelKey] can ever put into [de.littletrotter.shared.TripExpenseExportDto.kindLabelKey] —
 *  resolved once, up front, into [TripExpenseSheetLabels.kindLabels] rather than looked up one key
 *  at a time per row. */
internal val TRIP_EXPENSE_KIND_KEYS: List<String> = listOf(
    "trip.kind.hotel",
    "trip.kind.transport",
    "trip.kind.mileage",
    "trip.kind.other",
    "trip.kind.travelTime",
)
