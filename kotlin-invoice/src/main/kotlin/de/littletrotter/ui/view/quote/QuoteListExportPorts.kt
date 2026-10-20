package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.QuoteListCsvExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** The list's download ports and the clock for the file names, bundled like the other view ports to keep the view's constructor short. */
@Dependent
class QuoteListExportPorts
@Inject
constructor(
    val workbook: QuoteListWorkbookExportQueryPort,
    val csv: QuoteListCsvExportQueryPort,
    val time: TimeSource,
)
