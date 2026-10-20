package de.littletrotter.infrastructure.export.csv

import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListCsvExportQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import de.littletrotter.infrastructure.export.allRows
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.OutputStream

private val COLUMN_KEYS = listOf(
    "quoteExport.column.number",
    "quoteExport.column.customer",
    "quoteExport.column.subject",
    "quoteExport.column.status",
    "quoteExport.column.quoteDate",
    "quoteExport.column.validUntil",
    "quoteExport.column.net",
)

/** Streams the filtered offer list page by page into the CSV writer; the list rows carry every column. */
@ApplicationScoped
class QuoteListCsvExportService @Inject constructor(
    private val listRemote: QuoteListRemote,
) : QuoteListCsvExportQueryPort {

    override fun writeCsv(criteria: QuoteListCriteria, translate: (String) -> String, output: OutputStream) {
        QuoteListCsvWriter.write(
            output,
            COLUMN_KEYS.map(translate),
            { status -> translate("quote.status.${status.name}") },
            listRemote.allRows(criteria),
        )
    }
}
