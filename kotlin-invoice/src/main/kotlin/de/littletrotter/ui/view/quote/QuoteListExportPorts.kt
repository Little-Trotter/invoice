package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** The list's download port, bundled like the other view ports to keep the view's constructor short. */
@Dependent
class QuoteListExportPorts
@Inject
constructor(
    val workbook: QuoteListWorkbookExportQueryPort,
)
