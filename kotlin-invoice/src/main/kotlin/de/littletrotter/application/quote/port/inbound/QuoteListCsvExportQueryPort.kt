package de.littletrotter.application.quote.port.inbound

import java.io.OutputStream

/** Exports the filtered offer list as a flat CSV file; reads from the offer component, never cached here. */
fun interface QuoteListCsvExportQueryPort {

    /**
     * Writes one header row and one row per offer [criteria] matches (its offset and limit are ignored,
     * order and filter apply), with the columns of the list. [translate] resolves a plain i18n key to
     * display text. Does not close [output].
     */
    fun writeCsv(criteria: QuoteListCriteria, translate: (String) -> String, output: OutputStream)
}
