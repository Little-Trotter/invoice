package de.littletrotter.application.export.port.outbound

import java.io.OutputStream
import de.littletrotter.application.export.TripWorkbookView

/**
 * Thrown when [invoiceId]/[positionId] does not resolve to a `TRIP` position with trip
 * detail. Covers three cases alike: the invoice does not exist, the position does not exist on it,
 * or the position exists but is not a `TRIP` (nothing to export) — the caller never needs to tell
 * these apart, all three mean "there is no trip workbook for this address".
 */
class TripExportNotFoundException(invoiceId: Long, positionId: String) :
    NoSuchElementException("No TRIP position found for export: invoiceId=$invoiceId, positionId=$positionId")

/**
 * The per-trip workbook export port: one `.xlsx` (`Export`, `Dienstreise`, `Posten`) for
 * exactly one `TRIP` position, so the entrepreneur and the customer's contact can check the price
 * even when the invoice itself only shows the fixed price or a grouped total. A pure
 * application-layer port (onion architecture lock-in), the same shape as
 * [InvoiceWorkbookExportPort]: every parameter is a primitive, [OutputStream] or a plain function —
 * never a POI type — so `application`/`ui` code can depend on this interface without depending on
 * the `infrastructure.export.workbook.trip` adapter that implements it.
 *
 * **Worker seam:** this port is called synchronously today (nothing wires a caller into it
 * yet — the download button hosts it, the X-Rechnung BG-24 attachment is the other planned caller). That
 * worker path does not call this port directly at render time: [de.littletrotter.shared.PayloadExchange]
 * (the Valkey exchange, the same one [de.littletrotter.application.invoice.InvoiceDtoMapper]
 * already uses for the PDF template body) is where the rendered bytes belong once the
 * BG-24 XLSX annex is wired — the app renders the workbook at job-publish time via this port and `SETEX`s
 * the bytes into Valkey, sending only a `{key, sha256}` reference on the NATS envelope (AP-M payload
 * rule: Valkey for text/blobs, NATS envelope only), exactly like the template-offload precedent.
 * Nothing here anticipates that wiring beyond this note.
 *
 * translate resolves a plain, argument-less i18n key to display text, the same convention
 * [InvoiceWorkbookExportPort] already uses.
 *
 * The worker seam note above is now wired: [writeTripWorkbook] (the `view`-based overload)
 * is how the app renders the workbook at job-publish time, called directly from
 * [de.littletrotter.application.invoice.InvoiceDtoMapper] with the [TripWorkbookView] it already
 * assembled while building the export DTO. It deliberately does **not** go through the
 * `invoiceId`/`positionId` overload below: that one re-loads the invoice and re-runs
 * [de.littletrotter.application.invoice.InvoiceDtoMapper.mapToDto] itself
 * (TripWorkbookExportService's own `loadTripWorkbookView`), which would recurse back into the
 * very mapping call that is still in progress.
 */
interface TripWorkbookExportPort {

    /**
     * Writes the three-sheet trip workbook for the `TRIP` position [positionId] of invoice
     * [invoiceId] to [output] (never closed here — the caller owns the stream).
     *
     * @throws TripExportNotFoundException when the invoice, the position, or a `TRIP` detail on it
     *   does not exist.
     */
    fun writeTripWorkbook(invoiceId: Long, positionId: String, translate: (String) -> String, output: OutputStream)

    /**
     * The same three sheets, written directly from an already-assembled [view] instead of
     * reloading the invoice. See this interface's own KDoc for why InvoiceDtoMapper must call
     * this overload, never the `invoiceId`/`positionId` one above.
     */
    fun writeTripWorkbook(view: TripWorkbookView, translate: (String) -> String, output: OutputStream)

    /** The download file name for that workbook (`dienstreise_<invoice-number|id-<id>>_<pos>.xlsx`). */
    fun fileName(invoiceNumber: String, invoiceId: Long, positionId: String): String
}
