package de.littletrotter.application.export.port.outbound

import de.littletrotter.application.dto.InvoiceExportFilter
import java.io.OutputStream

/**
 * Thrown when the port is asked to export an invoice id that does not exist.
 * [InvoiceExportReadRepository.findHeaderRow] reports that as `null` rather than throwing itself
 * (R6: the export never assumes an id is valid) — the implementation is the one place that turns
 * the absence into a failure the UI's download path can catch.
 */
class InvoiceExportNotFoundException(invoiceId: Long) :
    NoSuchElementException("No invoice found for export: $invoiceId")

/**
 * The single-invoice (mode A) export port: assembles all four sheets (`Export`,
 * `Rechnungen`, `Positionen`, `Journal`) for exactly one invoice. A pure application-layer port
 * (onion architecture lock-in): every parameter type here is a primitive, [OutputStream]
 * or a plain function — never a POI/OOXML type from `de.littletrotter.infrastructure.export
 * .workbook`, so `application`/`ui` code can depend on this interface without depending on that
 * adapter directly. [InvoiceWorkbookExportPort]'s real implementation (which DOES know about POI)
 * lives in that infrastructure package, next to the sheet writers it assembles.
 *
 * A later change (PO 2026-09-10) deleted mode A's own positions-only CSV alternative — the workbook's
 * `Positionen` sheet already carries the same rows for one invoice, and nobody used the separate
 * file. The CSV path that remains ([writePositionsCsv] below) is mode B's only: a bulk export
 * across every invoice a filter matches, which the single-invoice workbook cannot express.
 *
 * translate resolves a plain, argument-less i18n key to display text — the only way UI-language
 * text crosses this port, since neither this interface nor its implementation carry an i18n bundle
 * of their own. The one label that takes a parameter (the `Export` sheet's "which invoice"
 * sentence) is resolved by the caller *before* crossing the port, as [writeWorkbook]'s
 * appliedSelection argument.
 *
 * Adds mode B (the filtered-list export) as an overload of [writeWorkbook] below, taking
 * an [InvoiceExportFilter] instead of an invoice id.
 */
interface InvoiceWorkbookExportPort {

    /**
     * Writes the four-sheet workbook for [invoiceId] to [output] (never closed here — the caller
     * owns the stream).
     *
     * @throws InvoiceExportNotFoundException when [invoiceId] does not exist.
     */
    fun writeWorkbook(invoiceId: Long, appliedSelection: String, translate: (String) -> String, output: OutputStream)

    /**
     * The filtered-list (mode B) export: three sheets (`Export`, `Rechnungen`,
     * `Positionen`) for every invoice matching [filter], **no** `Journal` sheet (a PO decision —
     * the change journal belongs to a single invoice; spread across a filtered set it would be
     * neither readable nor meaningful). Never throws for a filter that matches nothing — an empty
     * selection still produces a valid workbook with header rows and a "no entries" note, exactly
     * like mode A's empty-positions case.
     */
    fun writeWorkbook(filter: InvoiceExportFilter, appliedSelection: String, translate: (String) -> String, output: OutputStream)

    /**
     * The positions-only CSV (R5) across every invoice matching [filter]. Same shape as
     * the mode-A CSV, just over more than one invoice's positions.
     */
    fun writePositionsCsv(filter: InvoiceExportFilter, translate: (String) -> String, output: OutputStream)
}
