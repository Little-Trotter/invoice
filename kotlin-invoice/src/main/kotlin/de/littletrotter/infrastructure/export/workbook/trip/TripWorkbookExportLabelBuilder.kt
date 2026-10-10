package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.infrastructure.export.workbook.ExportSheetKeyValueLabels
import de.littletrotter.infrastructure.export.workbook.ExportSheetLabels
import de.littletrotter.infrastructure.export.workbook.ExportSheetNote
import de.littletrotter.infrastructure.export.workbook.ExportSheetNotesLabels
import de.littletrotter.infrastructure.export.workbook.ExportSheetPrivacyLabels
import de.littletrotter.infrastructure.export.workbook.ExportSheetPrivacyParagraph
import de.littletrotter.shared.PerDiemDescriptionLabels

/**
 * Resolves every label the trip workbook needs through the i18n bundle, the same role
 * [de.littletrotter.infrastructure.export.workbook.buildInvoiceWorkbookExportLabels] plays for the
 * per-invoice workbook: this is the one place in the trip-export stack allowed to know translation
 * keys, and it reuses an existing key wherever the invoice workbook's cover already names the exact
 * same concept (the "Feld"/"Wert" column headers, the "Export"/"Hinweise zum Export"/"Datenschutz
 * (DSGVO)" section headings, the generation/actor/selection/version field labels, the closing line,
 * and — verbatim, per spec — all seven GDPR paragraphs) rather than minting a duplicate
 * `tripExport.*` key for text the bundles already carry.
 */
fun buildTripWorkbookExportLabels(tr: (String) -> String): TripWorkbookExportLabels = TripWorkbookExportLabels(
    exportSheet = buildTripExportSheetLabels(tr),
    detailSheet = buildTripDetailSheetLabels(tr),
    expenseSheet = buildTripExpenseSheetLabels(tr),
    perDiemSheet = PerDiemSheetLabels(
        columnHeaders = listOf(
            "invoiceDetail.trip.expenses.overview.kind", "invoiceDetail.trip.expense.description",
            "invoiceDetail.trip.expenses.overview.date", "invoiceDetail.trip.expenses.overview.quantity",
            "invoiceDetail.trip.expenses.overview.unitPrice", "invoiceDetail.trip.expenses.overview.total",
            "invoiceDetail.trip.expenses.overview.receipt",
        ).map(tr),
        title = tr("tripExport.perDiem.sheet.title"),
        totalRowLabel = tr("invoiceDetail.trip.perDiem.overview.footer.total"),
        kindLabel = tr("invoiceDetail.trip.perDiem.overview.kind"),
        scopeDaysOnlyPattern = tr("invoiceDetail.trip.perDiem.overview.scope.daysOnly"),
        scopeNamedPattern = tr("invoiceDetail.trip.perDiem.overview.scope.named"),
        scopeCountedPattern = tr("invoiceDetail.trip.perDiem.overview.scope.counted"),
        descriptionLabels = PerDiemDescriptionLabels(
            arrivalDeparture = tr("invoiceDetail.trip.perDiem.overview.arrivalDeparture"),
            fullDay = tr("invoiceDetail.trip.perDiem.overview.fullDay"),
            breakfastDeduction = tr("invoiceDetail.trip.perDiem.overview.deduction.breakfast"),
            lunchDeduction = tr("invoiceDetail.trip.perDiem.overview.deduction.lunch"),
            dinnerDeduction = tr("invoiceDetail.trip.perDiem.overview.deduction.dinner"),
        ),
    ),
    subtitlePattern = tr("tripExport.cover.subtitlePattern"),
)

private fun buildTripDetailSheetLabels(tr: (String) -> String): TripDetailSheetLabels = TripDetailSheetLabels(
    columnHeaders = TripDetailColumnKeys.ORDERED_KEYS.map(tr),
    fieldTitle = tr("tripExport.detail.field.title"),
    fieldPurpose = tr("tripExport.detail.field.purpose"),
    fieldPeriodFrom = tr("tripExport.detail.field.periodFrom"),
    fieldPeriodTo = tr("tripExport.detail.field.periodTo"),
    fieldInvoiceNumber = tr("tripExport.detail.field.invoiceNumber"),
    fieldInvoiceDate = tr("tripExport.detail.field.invoiceDate"),
    fieldCustomer = tr("tripExport.detail.field.customer"),
    fieldCostCenter = tr("tripExport.detail.field.costCenter"),
    fieldTaxTreatment = tr("tripExport.detail.field.taxTreatment"),
    fieldCalculatedNet = tr("tripExport.detail.field.calculatedNet"),
    fieldFixedNetPrice = tr("tripExport.detail.field.fixedNetPrice"),
    fieldDelta = tr("tripExport.detail.field.delta"),
    fieldReceiptsCount = tr("tripExport.detail.field.receiptsCount"),
    taxTreatmentInheritsRatePattern = tr("tripExport.detail.taxTreatment.inheritsRatePattern"),
    noValueLabel = tr("tripExport.detail.noValue"),
)

private fun buildTripExpenseSheetLabels(tr: (String) -> String): TripExpenseSheetLabels = TripExpenseSheetLabels(
    columnHeaders = TripExpenseColumnKeys.ORDERED_KEYS.map(tr),
    kindLabels = TRIP_EXPENSE_KIND_KEYS.associateWith(tr),
    totalRowLabel = tr("tripExport.posten.totalRowLabel"),
)

/**
 * The cover ("Export") sheet: title/section labels, the "Export" field/value block, seven GDPR
 * paragraphs and the closing line are the invoice workbook's own keys, byte-for-byte (the spec
 * asks for the GDPR block "verbatim"); only the notes are trip-specific — two short notes rather
 * than the invoice workbook's six, since a single-trip workbook has far less to explain than a
 * whole invoice's four-sheet export ("reduced to what applies" per rk-5-trip-excel.md).
 *
 * [ExportSheetLabels.subtitlePattern] is intentionally the identity format `"{0}"`, not a resolved
 * i18n key: [de.littletrotter.infrastructure.export.workbook.ExportSheetWriter.write] applies
 * [java.text.MessageFormat] to it once more with the metadata's own `appliedSelection` — which
 * [TripWorkbookExportService] already builds by formatting [TripWorkbookExportLabels.subtitlePattern]
 * (the real, two-argument, translated pattern) with the trip's title and invoice number. Passing
 * that already-composed sentence through a second, real pattern here would double-apply
 * [java.text.MessageFormat] to it for no reason — the identity pattern is plumbing, not text a
 * translator would ever see or need to touch.
 */
private fun buildTripExportSheetLabels(tr: (String) -> String): ExportSheetLabels = ExportSheetLabels(
    subtitlePattern = "{0}",
    exportSectionLabel = tr("invoiceExport.cover.section.export"),
    keyValue = ExportSheetKeyValueLabels(
        fieldColumnHeader = tr("invoiceExport.export.column.field"),
        valueColumnHeader = tr("invoiceExport.export.column.value"),
        generatedAtLabel = tr("invoiceExport.export.field.generatedAt"),
        actingUserLabel = tr("invoiceExport.export.field.actingUser"),
        selectionLabel = tr("invoiceExport.export.field.selection"),
        applicationVersionLabel = tr("invoiceExport.export.field.applicationVersion"),
        recordCountLabelPattern = tr("invoiceExport.export.field.recordCountPattern"),
    ),
    notes = ExportSheetNotesLabels(
        sectionLabel = tr("invoiceExport.cover.section.notes"),
        notes = listOf(
            ExportSheetNote(tr("tripExport.notes.1.label"), tr("tripExport.notes.1.text")),
            ExportSheetNote(tr("tripExport.notes.2.label"), tr("tripExport.notes.2.text")),
        ),
    ),
    privacy = ExportSheetPrivacyLabels(
        sectionLabel = tr("invoiceExport.cover.section.privacy"),
        paragraphs = (1..PRIVACY_PARAGRAPH_COUNT).map { n ->
            ExportSheetPrivacyParagraph(tr("invoiceExport.privacy.$n.article"), tr("invoiceExport.privacy.$n.text"))
        },
    ),
    closingLabel = tr("invoiceExport.cover.closing"),
)

private const val PRIVACY_PARAGRAPH_COUNT = 7
