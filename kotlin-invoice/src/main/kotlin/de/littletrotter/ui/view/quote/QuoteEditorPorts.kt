package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderUseCase
import de.littletrotter.application.quote.port.inbound.EditQuoteItemsUseCase
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteHeaderFieldQueryPort
import de.littletrotter.application.quote.port.inbound.RemoveQuoteAttachmentUseCase
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** The write use cases of the offer editor, bundled to keep the view's constructor short; each member stays one narrow port. */
@Dependent
class QuoteEditorCommands
@Inject
constructor(
    val editHeader: EditQuoteHeaderUseCase,
    val editItems: EditQuoteItemsUseCase,
    val attach: AttachQuoteFileUseCase,
    val removeAttachment: RemoveQuoteAttachmentUseCase,
    val assignAttachment: AssignQuoteAttachmentUseCase,
    val attachLibrary: AttachLibraryDocumentUseCase,
)

/** The read side of the offer editor, bundled for the same reason as [QuoteEditorCommands]. */
@Dependent
class QuoteEditorQueries
@Inject
constructor(
    val detail: QuoteDetailQueryPort,
    val headerFields: QuoteHeaderFieldQueryPort,
    val articles: QuoteArticleLookupQueryPort,
    val attachments: QuoteAttachmentQueryPort,
    val library: AttachmentLibraryQueryPort,
)
