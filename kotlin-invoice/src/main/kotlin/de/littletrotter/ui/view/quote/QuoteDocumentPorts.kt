package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteValidationReportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.RequestQuoteDocumentUseCase
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** The document, attachment and export use cases of the offer detail, bundled to keep the view's constructor short; each member stays one narrow port. */
@Dependent
class QuoteDocumentPorts
@Inject
constructor(
    val request: RequestQuoteDocumentUseCase,
    val documents: QuoteDocumentQueryPort,
    val files: QuoteDocumentFileQueryPort,
    val reports: QuoteValidationReportQueryPort,
    val attachments: QuoteAttachmentQueryPort,
    val workbook: QuoteWorkbookExportQueryPort,
)
