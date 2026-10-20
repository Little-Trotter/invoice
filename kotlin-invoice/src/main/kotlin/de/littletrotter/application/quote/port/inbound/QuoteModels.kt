package de.littletrotter.application.quote.port.inbound

import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * Local model of the offer component as the invoicing UI sees it. Identifiers are opaque text,
 * free texts (subject, intro, closing, note) are canonical free-text markup — the view converts
 * from and to HTML — and amounts are Money, so no view ever handles the wire format.
 */

/**
 * [UNKNOWN] stands for a status name the offer component sent that this build does not know (newer
 * contract): the row stays visible with a neutral state instead of failing the whole page.
 */
enum class QuoteStatus {
    DRAFT,
    REVIEW,
    SENT,
    ACCEPTED,
    REJECTED,
    CANCELLED,
    UNKNOWN,
    ;

    companion object {
        /** The statuses a user can filter by or read about; [UNKNOWN] is only ever a display fallback. */
        val known: List<QuoteStatus> = entries - UNKNOWN
    }
}

enum class QuoteInvoiceOrderStatus { REQUESTED, CREATED, REJECTED }

enum class QuotePriceDisplay { AUTO, NET, GROSS }

enum class QuoteLogKind { NOTE, STATUS, SEND, ATTACHMENT, INVOICE }

enum class QuoteAttachmentKind { INTERNAL, CUSTOMER }

/** Columns the offer list can be ordered by; the offer component maps each to its own ordering. */
enum class QuoteSortField { NUMBER, CUSTOMER, SUBJECT, STATUS, QUOTE_DATE, VALID_UNTIL, NET }

enum class QuoteSortDirection { ASC, DESC }

/**
 * Filter, order and window of the offer list; [text] searches number, customer and subject.
 * Without an explicit order the newest offer comes first.
 */
data class QuoteListCriteria(
    val status: QuoteStatus? = null,
    val text: String? = null,
    val offset: Int = 0,
    val limit: Int = DEFAULT_LIMIT,
    val sortField: QuoteSortField = QuoteSortField.QUOTE_DATE,
    val sortDirection: QuoteSortDirection = QuoteSortDirection.DESC,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

data class QuoteSummary(
    val id: String,
    val customerName: String,
    val status: QuoteStatus,
    val quoteDate: LocalDate,
    val validUntil: LocalDate,
    val netTotal: Money,
    val number: String? = null,
    val subject: String? = null,
)

/** One window of the list plus the size of the whole filtered set for the pager. */
data class QuotePage(val rows: List<QuoteSummary>, val total: Long)

data class QuoteParty(
    val customerId: String,
    val name: String,
    val countryCode: String,
    val customerType: String,
    val documentLanguage: String,
    val customerNumber: String? = null,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val vatId: String? = null,
    val email: String? = null,
)

data class QuoteItem(
    val id: String,
    val position: Int,
    val description: String,
    val quantity: BigDecimal,
    val category: InvoiceItemCategory,
    val unit: InvoiceItemUnit,
    val unitPrice: Money,
    val vatCategory: VatCategory,
    val vatRate: BigDecimal,
    val net: Money,
    val articleId: String? = null,
    val articleNumber: String? = null,
    val customerReference: String? = null,
    val customerArticleName: String? = null,
)

data class QuoteVatGroup(val category: VatCategory, val rate: BigDecimal, val base: Money, val vat: Money)

data class QuoteTotals(val net: Money, val vat: Money, val gross: Money, val groups: List<QuoteVatGroup> = emptyList())

/** Value of one business-term field of the offer header, addressed by its BT code. */
data class QuoteB2bValue(val btCode: Int, val value: String)

data class QuoteDetail(
    val id: String,
    val version: Long,
    val status: QuoteStatus,
    val party: QuoteParty,
    val priceDisplay: QuotePriceDisplay,
    val quoteDate: LocalDate,
    val validUntil: LocalDate,
    val totals: QuoteTotals,
    val number: String? = null,
    val subject: String? = null,
    val introText: String? = null,
    val closingText: String? = null,
    val noteText: String? = null,
    val showsGrossPrices: Boolean = false,
    val acceptedOn: LocalDate? = null,
    val customerOrderReference: String? = null,
    val rejectionReason: String? = null,
    val invoiceNumber: String? = null,
    val items: List<QuoteItem> = emptyList(),
    val b2bValues: List<QuoteB2bValue> = emptyList(),
    val invoiceOrder: QuoteInvoiceOrder? = null,
)

/** The latest invoice order of an offer: waiting for the invoicing application, answered with an invoice, or refused with a reason. */
data class QuoteInvoiceOrder(
    val orderId: String,
    val status: QuoteInvoiceOrderStatus,
    val requestedAt: Instant,
    val deliveryDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val invoiceId: Long? = null,
    val invoiceNumber: String? = null,
    val rejectionReason: String? = null,
)

/**
 * What the clerk states when ordering an invoice draft. [acceptedOn] is required for a sent offer
 * (the order records the acceptance) and ignored for an accepted one. A delivery date excludes a
 * service period; a period has both ends.
 */
data class QuoteInvoiceOrderRequest(
    val acceptedOn: LocalDate?,
    val customerOrderReference: String?,
    val deliveryDate: LocalDate?,
    val servicePeriodFrom: LocalDate?,
    val servicePeriodTo: LocalDate?,
)

data class QuoteLogEntry(
    val occurredAt: Instant,
    val actor: String,
    val kind: QuoteLogKind,
    val text: String,
    val reference: String? = null,
)

data class QuoteAttachment(
    val id: String,
    val kind: QuoteAttachmentKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: Instant,
    /** The offer line this file belongs to, if any, with its position number for display. */
    val itemId: String? = null,
    val itemPosition: Int? = null,
    /** Set for a file the offer took from its attachment library: the version held, the entry's title and its version number. */
    val libraryVersionId: String? = null,
    val libraryTitle: String? = null,
    val versionNo: Int? = null,
    /** Only reported for a draft: the pool holds a newer version than the one this offer carries. */
    val newerVersionAvailable: Boolean = false,
)

/** The bytes of one attachment together with the name and media type it is to be offered under. */
class QuoteAttachmentContent(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** Offers per status; absent statuses count zero. */
data class QuoteStatistics(val countsByStatus: Map<QuoteStatus, Long>)

/**
 * What the offer statistics page looks at: [year], narrowed to one [quarter] or one [month] (never
 * both), and optionally to one [status] or one [customerType] that narrows every figure alike.
 */
data class QuoteStatisticsSelection(
    val year: Int,
    val quarter: Int? = null,
    val month: Int? = null,
    val status: QuoteStatus? = null,
    val customerType: CustomerType? = null,
)

/**
 * Headline figures of a set of offers: what was sent, what is still in work (draft and review) and
 * how long sending took. The average and the days to sending are null while nothing was sent.
 */
data class QuoteKpis(
    val sentVolume: Money,
    val sentCount: Int,
    val averageSent: Money?,
    val inProgressAmount: Money,
    val inProgressCount: Int,
    val avgDaysToSend: BigDecimal?,
)

/** Offers per stage; "sent" covers every offer that went out, whatever the customer answered. */
data class QuoteStatusCounts(val draft: Int, val review: Int, val sent: Int, val cancelled: Int) {
    val total: Int get() = draft + review + sent + cancelled
}

/** Sent volume and number of offers of one quarter. */
data class QuoteQuarterTotals(val quarter: Int, val net: Money, val count: Int)

/** One bar of the trend: the first day of a month, or a single day when one month is selected. */
data class QuoteTrendPoint(val day: LocalDate, val net: Money, val count: Int)

/** The figures of the offer statistics page; the previous year is the same span one year earlier. */
data class QuoteDashboard(
    val availableYears: List<Int>,
    val kpis: QuoteKpis,
    val kpisPreviousYear: QuoteKpis,
    val statusCounts: QuoteStatusCounts,
    val quarters: List<QuoteQuarterTotals>,
    val trend: List<QuoteTrendPoint>,
)

/** What a ranking lists. */
enum class QuoteRankingKind { CUSTOMER, ARTICLE }

/** One line of a ranking by sent volume; [key] identifies the customer or article, [name] is its text. */
data class QuoteRankingRow(val key: String, val name: String, val count: Int, val net: Money)

/** Machine-readable form an offer PDF carries: none (plain PDF) or UBL 2.1 Quotation data. */
enum class QuoteXmlFormat { NONE, UBL }

data class QuoteSettings(
    val validityDays: Long,
    val xmlFormat: QuoteXmlFormat = QuoteXmlFormat.UBL,
    val updatedAt: Instant? = null,
)

/** Which copy of an offer a document is: the unnumbered draft, the review copy or the dispatch copy. */
enum class QuoteDocumentMark { DRAFT, REVIEW, DISPATCH }

/** Rendering state of a document: asked for, finished, or failed. */
enum class QuoteDocumentStatus { REQUESTED, COMPLETED, FAILED }

/** One rendered (or still pending) copy of an offer; [hasXml] tells whether machine-readable data belongs to it. */
data class QuoteDocument(
    val id: String,
    val mark: QuoteDocumentMark,
    val status: QuoteDocumentStatus,
    val requestedAt: Instant,
    val completedAt: Instant? = null,
    val sha256: String? = null,
    val hasValidationReport: Boolean = false,
    val error: String? = null,
    val hasXml: Boolean = false,
)

/** The bytes of one document file together with the name it is offered under. */
class QuoteDocumentContent(val fileName: String, val bytes: ByteArray)

/** Outcome of the checks a document went through; [findings] are readable lines, empty when [valid]. */
data class QuoteValidationReport(val valid: Boolean, val findings: List<String> = emptyList())

data class QuoteCustomerOption(
    val id: String,
    val name: String,
    val customerNumber: String? = null,
    val city: String? = null,
)

data class QuoteArticleOption(
    val id: String,
    val articleNumber: String,
    val description: String,
    val category: InvoiceItemCategory,
    val unit: InvoiceItemUnit,
    val unitPrice: Money,
    val vatCategory: VatCategory,
    val vatRate: BigDecimal,
    val customerReference: String? = null,
    val customerArticleName: String? = null,
)

/** A business-term field the header offers for entry; [mandatory] fields must be filled before submitting. */
data class QuoteHeaderField(val btCode: Int, val mandatory: Boolean, val defaultValue: String? = null)

/** The header as the user edited it; [b2bValues] maps a BT code to its value. */
data class EditQuoteHeaderCommand(
    val quoteId: String,
    val validUntil: LocalDate,
    val priceDisplay: QuotePriceDisplay,
    val subject: String? = null,
    val introText: String? = null,
    val closingText: String? = null,
    val noteText: String? = null,
    val b2bValues: Map<Int, String> = emptyMap(),
)

/** One line as edited; a line without [id] is new, [articleId] ties it to an article it was taken from. */
data class QuoteItemInput(
    val description: String,
    val quantity: BigDecimal,
    val category: InvoiceItemCategory,
    val unit: InvoiceItemUnit,
    val unitPrice: Money,
    val vatCategory: VatCategory,
    val vatRate: BigDecimal,
    val id: String? = null,
    val articleId: String? = null,
    val articleNumber: String? = null,
    val customerReference: String? = null,
    val customerArticleName: String? = null,
)

/** A file as the user picked it, ready to be attached to an offer. */
class QuoteAttachmentUpload(
    val kind: QuoteAttachmentKind,
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
    /** The offer line the file belongs to; null keeps it on the offer as a whole. */
    val itemId: String? = null,
    /** True when the user chose (or the setting decided) to shrink a large PDF; false stores it as sent. */
    val reduce: Boolean = false,
)

/**
 * What the offer component made of an upload: the id of what it stored and, when a reduction was
 * requested and reported, how it ended. A null [reduction] means the component did not report one.
 */
class StoredQuoteFile(val id: String, val reduction: StoredQuoteReduction? = null)

/** The [stage] that produced the stored file and its size; [ReceiptNormalisationStage.RASTERISED] means the text layer is gone. */
class StoredQuoteReduction(val stage: ReceiptNormalisationStage, val storedBytes: Long)

/** Links an attachment to a line of the same offer; a null [itemId] releases it from its line. */
data class AssignQuoteAttachmentCommand(val quoteId: String, val attachmentId: String, val itemId: String?)

/**
 * Why an offer can or cannot be handed to the mail component right now, as the invoicing side
 * knows it. It mirrors the offer component's values so the application layer does not depend on
 * the offer contract; the translation lives in the offer adapter. The first condition that does
 * not hold decides.
 */
enum class QuoteSendGateState {
    /** Every condition holds: the mail may be prepared. */
    READY,

    /** Only an offer in review can be sent. */
    NOT_IN_REVIEW,

    /** A dispatch copy of the current offer content is still being rendered. */
    COPY_IN_FLIGHT,

    /** The dispatch copies predate the last content or status change, or their rendering failed. */
    COPY_STALE,

    /** No dispatch copy was ever requested for the offer. */
    COPY_MISSING,

    /** The customer has no e-mail address to send the offer to. */
    NO_RECIPIENT_EMAIL,
}
