package de.littletrotter.infrastructure.export.workbook

import java.text.MessageFormat
import java.time.OffsetDateTime
import java.util.Locale

/** One data sheet's name and how many records it carries — read off its [SheetWriter] once that
 *  sheet has been fully written. */
data class ExportSheetSummary(val sheetName: String, val recordCount: Long)

/** The export's own metadata (R7): when it was generated, who generated it, what selection it
 *  applies (rendered in words), which application version produced it, and the product line the
 *  cover sheet's own title row (and the workbook's OOXML core properties) name themselves after. */
data class ExportMetadata(
    val productLine: String,
    val generatedAt: OffsetDateTime,
    val actingUser: String,
    val appliedSelection: String,
    val applicationVersion: String,
)

/** A single row of the "Hinweise zum Export" (export notes) section: a short label in column A, a
 *  wrapped explanatory sentence in column B. */
data class ExportSheetNote(val label: String, val text: String)

/** A single row of the "Datenschutz (DSGVO)" section: the article reference in column A, the
 *  compliance-grade paragraph text in column B. */
data class ExportSheetPrivacyParagraph(val article: String, val text: String)

/** The original "Export" section's own key/value labels, content unchanged by the cover-page
 *  redesign — grouped into its own type so [ExportSheetLabels]'s own constructor stays well under
 *  this module's `LongParameterList` gate, the same way [InvoiceHeaderSheetLabels] groups its own
 *  six enum-label maps into [InvoiceHeaderEnumLabels]. */
data class ExportSheetKeyValueLabels(
    val fieldColumnHeader: String,
    val valueColumnHeader: String,
    val generatedAtLabel: String,
    val actingUserLabel: String,
    val selectionLabel: String,
    val applicationVersionLabel: String,
    /** Filled in with a data sheet's name via [String.format] — must contain exactly one `%s`. */
    val recordCountLabelPattern: String,
)

/** The "Hinweise zum Export" section's own label set. */
data class ExportSheetNotesLabels(
    val sectionLabel: String,
    /** Exactly six entries ("Hinweise zum Export"); mode B's first note names only its
     *  own two data sheets, so the caller (the label builder) is the one place that knows which
     *  text belongs in [ExportSheetNote.text] at index 0. */
    val notes: List<ExportSheetNote>,
)

/** The "Datenschutz (DSGVO)" section's own label set. */
data class ExportSheetPrivacyLabels(
    val sectionLabel: String,
    /** Exactly seven entries — the binding GDPR paragraphs ("GDPR block", PO decision
     *  P3). */
    val paragraphs: List<ExportSheetPrivacyParagraph>,
)

/**
 * Every user-facing label the "Export" sheet needs, supplied by the caller. This infrastructure
 * package writes OOXML; it does not carry user-facing text of its own — every label lives in the
 * i18n bundles the application layer resolves them from.
 */
data class ExportSheetLabels(
    /** `{0}` = [ExportMetadata.appliedSelection] — the cover sheet's own subtitle (A2). */
    val subtitlePattern: String,
    val exportSectionLabel: String,
    val keyValue: ExportSheetKeyValueLabels,
    val notes: ExportSheetNotesLabels,
    val privacy: ExportSheetPrivacyLabels,
    /** "This notice is general guidance, not legal advice; the company's data protection officer
     *  decides the concrete handling." — stays exactly this, in both languages (P3). */
    val closingLabel: String,
)

/**
 * Writes the "Export" sheet (R7): the workbook's own cover page, placed first. A redesign turned this
 * from a bare key/value list into: a title row (the product line), a subtitle (the applied
 * selection), the original "Export" key/value block (generation timestamp, acting user, selection,
 * application version, per-sheet record counts — content unchanged), a "Hinweise zum Export"
 * section explaining how to read the workbook, and a "Datenschutz (DSGVO)" section carrying the
 * compliance-grade data-protection notice (PO decision P3).
 *
 * This sheet is a cover page, not a tabular data sheet: unlike every other sheet it never calls
 * [SheetWriter.header] — a freeze pane and autofilter anchored on the title row would be
 * meaningless (worse, an autofilter arrow sitting on the product-line title would be actively
 * wrong). [SheetWriter.columnWidths] declares its two column widths on their own, and every row
 * writes through [SheetWriter.noteRow] rather than [SheetWriter.row]: none of this sheet's own rows
 * are "records" in the R7 sense the other sheets' [SheetWriter.recordCount] counts.
 *
 * It needs no domain knowledge — every value is supplied by the caller as plain data, and label data
 * supplies every piece of displayed text; deciding *what* those values are (who is acting, what a
 * filter means in words, which language the labels are in, which notice text mode B's own first
 * note needs) is an application-layer concern outside this package.
 */
object ExportSheetWriter {

    private const val SHEET_NAME = "Export"

    fun write(
        writer: WorkbookWriter,
        labels: ExportSheetLabels,
        metadata: ExportMetadata,
        summaries: List<ExportSheetSummary>,
    ): SheetWriter {
        val sheet = writer.sheet(SHEET_NAME)
        sheet.columnWidths(listOf(ColumnWidths.EXPORT_FIELD, ColumnWidths.EXPORT_VALUE))

        val keyValue = labels.keyValue
        sheet.noteRow().title(metadata.productLine)
        sheet.noteRow().secondary(MessageFormat.format(labels.subtitlePattern, metadata.appliedSelection))
        sheet.noteRow() // spacer

        sheet.noteRow().section(labels.exportSectionLabel)
        sheet.subHeader(listOf(keyValue.fieldColumnHeader, keyValue.valueColumnHeader))
        sheet.noteRow().text(keyValue.generatedAtLabel).dateTime(metadata.generatedAt)
        sheet.noteRow().text(keyValue.actingUserLabel).text(metadata.actingUser)
        sheet.noteRow().text(keyValue.selectionLabel).text(metadata.appliedSelection, wrap = true)
        sheet.noteRow().text(keyValue.applicationVersionLabel).text(metadata.applicationVersion)
        summaries.forEach { summary ->
            val recordCountLabel = String.format(Locale.ROOT, keyValue.recordCountLabelPattern, summary.sheetName)
            sheet.noteRow().text(recordCountLabel).integer(summary.recordCount)
        }

        sheet.noteRow() // spacer
        sheet.noteRow().section(labels.notes.sectionLabel)
        labels.notes.notes.forEach { note -> sheet.noteRow().text(note.label).secondary(note.text) }

        sheet.noteRow() // spacer
        sheet.noteRow().section(labels.privacy.sectionLabel)
        labels.privacy.paragraphs.forEach { paragraph ->
            sheet.noteRow().text(paragraph.article).secondary(paragraph.text)
        }

        sheet.noteRow() // spacer
        sheet.noteRow().secondary(labels.closingLabel)

        writer.moveToFront(sheet)
        return sheet
    }
}
