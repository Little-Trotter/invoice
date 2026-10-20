package de.littletrotter.shared.quote

import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate

/*
 * Request bodies of the offer component. None carries the acting user: the component takes it from
 * the authenticated edge identity, so a body can never claim to be someone else.
 */

data class CreateQuoteRequest(val customerId: String)

data class EditQuoteHeaderRequest(
    val validUntil: LocalDate,
    val priceDisplay: String,
    val subject: String? = null,
    val introText: String? = null,
    val closingText: String? = null,
    val noteText: String? = null,
    val b2bValues: Map<Int, String> = emptyMap(),
)

data class QuoteItemLineRequest(
    val description: String,
    val quantity: BigDecimal,
    val category: String,
    val unit: String,
    val unitPrice: Money,
    val vatCategory: String,
    val vatRate: BigDecimal,
    val id: String? = null,
    val articleId: String? = null,
    val articleNumber: String? = null,
    val customerReference: String? = null,
    val customerArticleName: String? = null,
)

data class EditQuoteItemsRequest(val lines: List<QuoteItemLineRequest> = emptyList())

data class ReturnQuoteToDraftRequest(val note: String)

data class CancelQuoteRequest(val reason: String)

data class AcceptQuoteRequest(val acceptedOn: LocalDate, val customerOrderReference: String? = null)

/**
 * Orders an invoice draft. [acceptedOn] is required for a sent offer (the acceptance is recorded with
 * the order) and ignored for an accepted one. A delivery date excludes a service period; a period has both ends.
 */
data class RequestQuoteInvoiceRequest(
    val acceptedOn: LocalDate? = null,
    val customerOrderReference: String? = null,
    val deliveryDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
)

data class RejectQuoteRequest(val reason: String)

data class AddQuoteNoteRequest(val text: String)

/** [reduce] asks the component to shrink a large PDF before storing it; absent means store as sent. */
data class AttachQuoteFileRequest(
    val kind: String,
    val fileName: String,
    val mimeType: String,
    val contentBase64: String,
    val itemId: String? = null,
    val reduce: Boolean = false,
)

/** [itemId] null releases the attachment from its line. */
data class AssignQuoteAttachmentRequest(val itemId: String? = null)

/** The first file of a new library entry; [validFrom] defaults to today. */
data class CreateAttachmentLibraryEntryRequest(
    val title: String,
    val description: String,
    val fileName: String,
    val mimeType: String,
    val contentBase64: String,
    val validFrom: LocalDate? = null,
    val reduce: Boolean = false,
)

/** A new file as the next version of a library entry; [validFrom] defaults to today. */
data class AddAttachmentLibraryVersionRequest(
    val fileName: String,
    val mimeType: String,
    val contentBase64: String,
    val validFrom: LocalDate? = null,
    val reduce: Boolean = false,
)

/** Takes a library version over into a draft offer, optionally tied to one of its lines. */
data class AttachFromLibraryRequest(val versionId: String, val itemId: String? = null)

/** Swaps the library version of an attachment of a draft offer. */
data class ReplaceLibraryVersionRequest(val versionId: String)

/** [mark] is DRAFT, REVIEW or DISPATCH: which copy of the offer to render. */
data class RequestQuoteDocumentRequest(val mark: String)

data class UpdateQuoteSettingsRequest(val validityDays: Long, val xmlSyntax: String? = null)
