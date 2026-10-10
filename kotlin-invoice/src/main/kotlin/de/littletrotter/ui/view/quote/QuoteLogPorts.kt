package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.AddQuoteNoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** Reading and annotating the Protokoll, bundled to keep the offer detail's constructor short; each member stays one narrow port. */
@Dependent
class QuoteLogPorts
@Inject
constructor(
    val query: QuoteLogQueryPort,
    val addNote: AddQuoteNoteUseCase,
)
