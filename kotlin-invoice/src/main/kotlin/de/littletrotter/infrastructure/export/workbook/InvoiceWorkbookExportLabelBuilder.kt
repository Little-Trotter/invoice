package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoiceHeaderColumnKeys
import de.littletrotter.application.export.InvoiceJournalColumnKeys
import de.littletrotter.application.export.InvoicePositionColumnKeys
import de.littletrotter.application.export.PartySource
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.ValidationStatus

/**
 * Resolves every label the four-sheet single-invoice export needs through the i18n
 * bundle. This is the one place in the whole export stack that is allowed to know translation
 * keys at all (see [ExportSheetLabels]'s own KDoc on why the sheet writers beneath it carry none)
 * — every enum → label mapping here deliberately reuses an existing bundle key wherever this
 * codebase already has one for the same value (status/validation/format/engine/type-code/item-
 * category/VAT-category), rather than minting a duplicate `invoiceExport.*` key for text that
 * already exists elsewhere in the product; only the `Export` sheet's own field names (which
 * nothing else in the app already names) are new keys.
 *
 * Lives in this package (not `application`/`ui`) because its return types
 * ([InvoiceWorkbookExportLabels] and friends) are this adapter's own — the onion
 * architecture lock-in forbids `application`/`ui` from depending on this `infrastructure.export`
 * adapter, so the port implementation ([InvoiceWorkbookExportService]) is the only caller.
 *
 * Pure and Vaadin-free — [tr] is a plain `(String) -> String` lookup, so this is unit-testable
 * without Karibu/MockVaadin.
 */
fun buildInvoiceWorkbookExportLabels(tr: (String) -> String, mode: ExportSheetMode): InvoiceWorkbookExportLabels = InvoiceWorkbookExportLabels(
    exportSheet = buildExportSheetLabels(tr, mode),
    headerSheet = buildInvoiceHeaderSheetLabels(tr),
    positionSheet = buildInvoicePositionSheetLabels(tr),
    journalSheet = buildInvoiceJournalSheetLabels(tr),
)

/** Exposed separately from [buildInvoiceWorkbookExportLabels] — the CSV alternative (R5) only
 *  ever needs the `Positionen` sheet's own label set, not the other three sheets'. */
fun buildInvoicePositionSheetLabels(tr: (String) -> String): InvoicePositionSheetLabels = InvoicePositionSheetLabels(
    columnHeaders = InvoicePositionColumnKeys.ORDERED_KEYS.map(tr),
    enumLabels = InvoicePositionEnumLabels(
        category = InvoiceItemCategory.entries.associateWith { tr(itemCategoryKey(it)) },
        vatCategory = VatCategory.entries.associateWith { tr(vatCategoryKey(it)) },
        // Reuses the exact key TripExpenseKind already carries for its UI/export label
        // (see the enum's own KDoc), never a second `invoiceExport.*` duplicate.
        tripExpenseKind = TripExpenseKind.entries.associateWith { tr(it.labelKey) },
    ),
    verdictOkLabel = tr(InvoicePositionColumnKeys.VERDICT_OK_KEY),
    verdictDeviationLabel = tr(InvoicePositionColumnKeys.VERDICT_DEVIATION_KEY),
    verdictTaxRateMissingLabel = tr(InvoicePositionColumnKeys.VERDICT_TAX_RATE_MISSING_KEY),
    // The trip dialog's own per-diem section title, reused verbatim for a per-diem-day
    // detail row's "Art" column rather than minting a second "Verpflegungspauschale" key.
    perDiemKindLabel = tr("invoiceDetail.perDiem.title"),
)

private fun buildInvoiceHeaderSheetLabels(tr: (String) -> String): InvoiceHeaderSheetLabels = InvoiceHeaderSheetLabels(
    columnHeaders = InvoiceHeaderColumnKeys.ORDERED_KEYS.map(tr),
    enumLabels = InvoiceHeaderEnumLabels(
        // InvoiceTypeCode.code ("380"/"381"/"384") IS the journal.value.typeCode.<code> key's own
        // suffix (see JournalEventLabels' sibling convention for InvoiceAuditEvent) — reused
        // verbatim rather than re-declaring the same three labels under an invoiceExport.* key.
        typeCode = InvoiceTypeCode.entries.associateWith { tr("journal.value.typeCode.${it.code}") },
        status = InvoiceStatus.entries.associateWith { tr(it.labelKey) },
        validationStatus = ValidationStatus.entries.associateWith { tr(validationStatusKey(it)) },
        invoiceFormat = InvoiceFormat.entries.associateWith { tr(invoiceFormatKey(it)) },
        documentEngine = DocumentEngine.entries.associateWith { tr(documentEngineKey(it)) },
        partySource = PartySource.entries.associateWith { tr(partySourceKey(it)) },
    ),
    verdictOkLabel = tr(InvoiceHeaderColumnKeys.VERDICT_OK_KEY),
    verdictDeviationLabel = tr(InvoiceHeaderColumnKeys.VERDICT_DEVIATION_KEY),
    // Resolved as the raw pattern (tr() with no params leaves "{0}" untouched, see
    // UiTranslations.tr) — InvoiceHeaderSheetWriter applies java.text.MessageFormat to it itself,
    // once per malformed row, not here.
    malformedNumberMarkerPattern = tr(InvoiceHeaderColumnKeys.INVOICE_NUMBER_MALFORMED_MARKER_KEY),
)

private fun buildInvoiceJournalSheetLabels(tr: (String) -> String): InvoiceJournalSheetLabels = InvoiceJournalSheetLabels(
    columnHeaders = InvoiceJournalColumnKeys.ORDERED_KEYS.map(tr),
    eventLabels = InvoiceAuditEvent.entries.associateWith { tr(it.labelKey) },
    noEntriesNoteLabel = tr(InvoiceJournalColumnKeys.NO_ENTRIES_NOTE_KEY),
    actionLabels = InvoiceAuditAction.entries.associateWith { tr(it.labelKey) },
)

/** The "Export" cover sheet's first note is the one piece of text this
 *  whole label set that differs by [ExportSheetMode]: mode A's workbook holds four sheets, mode
 *  B's three (no `Journal`) — every other note and every GDPR paragraph is mode-independent. */
private fun buildExportSheetLabels(tr: (String) -> String, mode: ExportSheetMode): ExportSheetLabels = ExportSheetLabels(
    subtitlePattern = tr("invoiceExport.cover.subtitlePattern"),
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
        notes = buildExportSheetNotes(tr, mode),
    ),
    privacy = ExportSheetPrivacyLabels(
        sectionLabel = tr("invoiceExport.cover.section.privacy"),
        paragraphs = buildExportSheetPrivacyParagraphs(tr),
    ),
    closingLabel = tr("invoiceExport.cover.closing"),
)

/** Six notes ("Hinweise zum Export"), in the sheet's own fixed row order. Note 1's own
 *  text is the only mode-dependent one — see this function's own KDoc above. */
private fun buildExportSheetNotes(tr: (String) -> String, mode: ExportSheetMode): List<ExportSheetNote> {
    val firstNoteTextKey = when (mode) {
        ExportSheetMode.SINGLE_INVOICE -> "invoiceExport.notes.1.text"
        ExportSheetMode.FILTERED_LIST -> "invoiceExport.notes.1.text.modeB"
    }
    return listOf(
        ExportSheetNote(tr("invoiceExport.notes.1.label"), tr(firstNoteTextKey)),
        ExportSheetNote(tr("invoiceExport.notes.2.label"), tr("invoiceExport.notes.2.text")),
        ExportSheetNote(tr("invoiceExport.notes.3.label"), tr("invoiceExport.notes.3.text")),
        ExportSheetNote(tr("invoiceExport.notes.4.label"), tr("invoiceExport.notes.4.text")),
        ExportSheetNote(tr("invoiceExport.notes.5.label"), tr("invoiceExport.notes.5.text")),
        ExportSheetNote(tr("invoiceExport.notes.6.label"), tr("invoiceExport.notes.6.text")),
    )
}

/** The seven binding GDPR paragraphs (PO decision P3), in the sheet's own
 *  fixed row order — mode-independent, unlike [buildExportSheetNotes]'s own first entry. */
private fun buildExportSheetPrivacyParagraphs(tr: (String) -> String): List<ExportSheetPrivacyParagraph> = (1..PRIVACY_PARAGRAPH_COUNT).map { n ->
    ExportSheetPrivacyParagraph(tr("invoiceExport.privacy.$n.article"), tr("invoiceExport.privacy.$n.text"))
}

private const val PRIVACY_PARAGRAPH_COUNT = 7

private fun validationStatusKey(status: ValidationStatus): String = when (status) {
    ValidationStatus.PENDING -> "invoice.validation.pending"
    ValidationStatus.VALID -> "invoice.validation.valid"
    ValidationStatus.INVALID -> "invoice.validation.invalid"
    ValidationStatus.UNAVAILABLE -> "invoice.validation.unavailable"
    ValidationStatus.NOT_VALIDATED -> "invoice.validation.notValidated"
}

private fun invoiceFormatKey(format: InvoiceFormat): String = when (format) {
    InvoiceFormat.GERMAN -> "invoiceModule.invoiceFormat.german"
    InvoiceFormat.INTERNATIONAL -> "invoiceModule.invoiceFormat.international"
}

private fun documentEngineKey(engine: DocumentEngine): String = when (engine) {
    DocumentEngine.MUSTANG -> "invoiceDetail.jobs.engine.mustang"
    DocumentEngine.FACTURX -> "invoiceDetail.jobs.engine.facturx"
}

private fun partySourceKey(source: PartySource): String = when (source) {
    PartySource.SNAPSHOT -> InvoiceHeaderColumnKeys.PARTY_SOURCE_SNAPSHOT_KEY
    PartySource.MASTER_DATA -> InvoiceHeaderColumnKeys.PARTY_SOURCE_MASTER_DATA_KEY
}

internal fun itemCategoryKey(category: InvoiceItemCategory): String = when (category) {
    InvoiceItemCategory.SERVICE -> "item.category.service"
    InvoiceItemCategory.PRODUCT -> "item.category.product"
    InvoiceItemCategory.TRIP -> "item.category.trip"
    InvoiceItemCategory.UNIT_PRICE -> "item.category.unitPrice"
}

internal fun vatCategoryKey(category: VatCategory): String = when (category) {
    VatCategory.STANDARD -> "invoiceExport.position.vatCategory.standard"
    VatCategory.ZERO_RATED -> "invoiceExport.position.vatCategory.zeroRated"
    VatCategory.EXEMPT -> "invoiceExport.position.vatCategory.exempt"
    VatCategory.REVERSE_CHARGE -> "invoiceExport.position.vatCategory.reverseCharge"
    VatCategory.OUTSIDE_SCOPE -> "invoiceExport.position.vatCategory.outsideScope"
    VatCategory.FREE_EXPORT_ITEM -> "invoiceExport.position.vatCategory.freeExportItem"
    VatCategory.INTRA_COMMUNITY_SUPPLY -> "invoiceExport.position.vatCategory.intraCommunitySupply"
}
