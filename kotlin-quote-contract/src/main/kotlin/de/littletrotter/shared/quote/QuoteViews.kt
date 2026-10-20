package de.littletrotter.shared.quote

import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * Wire views of the offer component. Enums travel as their name, identifiers as text, amounts as
 * plain decimal numbers (see QuoteWireJson), typed Money on both sides. Nullable and collection fields default so a payload written before a field existed
 * still reads.
 */

data class QuoteSummaryView(
    val id: String,
    val customerName: String,
    val status: String,
    val quoteDate: LocalDate,
    val validUntil: LocalDate,
    val netTotal: Money,
    val number: String? = null,
    val subject: String? = null,
)

data class QuotePageView(val rows: List<QuoteSummaryView> = emptyList(), val total: Long = 0)

data class QuotePartyView(
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

data class QuoteItemView(
    val id: String,
    val position: Int,
    val description: String,
    val quantity: BigDecimal,
    val category: String,
    val unit: String,
    val unitPrice: Money,
    val vatCategory: String,
    val vatRate: BigDecimal,
    val net: Money,
    val articleId: String? = null,
    val articleNumber: String? = null,
    val customerReference: String? = null,
    val customerArticleName: String? = null,
)

data class QuoteVatGroupView(val category: String, val rate: BigDecimal, val base: Money, val vat: Money)

data class QuoteTotalsView(
    val net: Money,
    val vat: Money,
    val gross: Money,
    val groups: List<QuoteVatGroupView> = emptyList(),
)

data class QuoteB2bValueView(val btCode: Int, val value: String)

/** The latest invoice order of an offer; [status] is REQUESTED, CREATED or REJECTED. */
data class QuoteInvoiceOrderView(
    val orderId: String,
    val status: String,
    val requestedAt: Instant,
    val deliveryDate: LocalDate? = null,
    val servicePeriodFrom: LocalDate? = null,
    val servicePeriodTo: LocalDate? = null,
    val invoiceId: Long? = null,
    val invoiceNumber: String? = null,
    val rejectionReason: String? = null,
)

data class QuoteDetailView(
    val id: String,
    val version: Long,
    val status: String,
    val party: QuotePartyView,
    val priceDisplay: String,
    val quoteDate: LocalDate,
    val validUntil: LocalDate,
    val totals: QuoteTotalsView,
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
    val items: List<QuoteItemView> = emptyList(),
    val b2bValues: List<QuoteB2bValueView> = emptyList(),
    val invoiceOrder: QuoteInvoiceOrderView? = null,
)

data class QuoteLogEntryView(
    val occurredAt: Instant,
    val actor: String,
    val kind: String,
    val text: String,
    val reference: String? = null,
)

data class QuoteAttachmentView(
    val id: String,
    val kind: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: Instant,
    val itemId: String? = null,
    val itemPosition: Int? = null,
    /** Set for a file taken from the attachment library: the version held, the entry's title and the version number. */
    val libraryVersionId: String? = null,
    val libraryTitle: String? = null,
    val versionNo: Int? = null,
    /** Only in draft: the library holds a newer version than the one this offer carries. */
    val newerVersionAvailable: Boolean = false,
)

/** One immutable version of a library entry. */
data class AttachmentLibraryVersionView(
    val id: String,
    val versionNo: Int,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val validFrom: LocalDate,
    val uploadedAt: Instant,
    val uploadedBy: String,
)

/** A reusable offer document; [versions] run from the newest to the oldest, [currentVersion] is the first. */
data class AttachmentLibraryEntryView(
    val id: String,
    val title: String,
    val description: String,
    val currentVersion: AttachmentLibraryVersionView,
    val versions: List<AttachmentLibraryVersionView>,
    val usedByQuotes: Int,
    val archived: Boolean,
)

/** One rendered copy of an offer; [status] is REQUESTED until the document worker has answered. */
data class QuoteDocumentView(
    val id: String,
    val mark: String,
    val status: String,
    val requestedAt: Instant,
    val completedAt: Instant? = null,
    val sha256: String? = null,
    val hasValidationReport: Boolean = false,
    val error: String? = null,
    val hasXml: Boolean = false,
)

data class QuoteStatisticsView(val countsByStatus: Map<String, Long> = emptyMap())

/** Headline figures of a set of offers; the average and the days to sending are null while nothing was sent. */
data class QuoteKpisView(
    val sentVolume: Money,
    val sentCount: Int,
    val averageSent: Money? = null,
    val inProgressAmount: Money,
    val inProgressCount: Int,
    val avgDaysToSend: BigDecimal? = null,
)

/** Offers per stage: draft, review, sent (whatever the customer answered) and cancelled. */
data class QuoteStatusCountsView(val draft: Int, val review: Int, val sent: Int, val cancelled: Int)

data class QuoteQuarterView(val quarter: Int, val net: Money, val count: Int)

/** One bar of the trend: the first day of a month, or a single day when one month is selected. */
data class QuoteTrendPointView(val day: LocalDate, val net: Money, val count: Int)

/** The figures of the statistics page for one selection; the previous year is the same span one year earlier. */
data class QuoteDashboardView(
    val availableYears: List<Int> = emptyList(),
    val kpis: QuoteKpisView,
    val kpisPreviousYear: QuoteKpisView,
    val statusCounts: QuoteStatusCountsView,
    val quarters: List<QuoteQuarterView> = emptyList(),
    val trend: List<QuoteTrendPointView> = emptyList(),
)

/** One line of a customer or article ranking by sent volume. */
data class QuoteRankingRowView(val key: String, val name: String, val count: Int, val net: Money)

data class QuoteSettingsView(val validityDays: Long, val xmlSyntax: String = "UBL", val updatedAt: Instant? = null)

data class QuoteCustomerOptionView(
    val id: String,
    val name: String,
    val customerNumber: String? = null,
    val city: String? = null,
)

data class QuoteArticleOptionView(
    val id: String,
    val articleNumber: String,
    val description: String,
    val category: String,
    val defaultUnit: String,
    val unitPrice: Money,
    val vatCategory: String,
    val vatRate: BigDecimal,
    val customerReference: String? = null,
    val customerArticleName: String? = null,
)

data class QuoteHeaderFieldView(val btCode: Int, val mandatory: Boolean, val defaultValue: String? = null)

/**
 * Answer of every call that creates something: the new identifier. [reduction] is set by the
 * upload calls when the request asked for a reduction; absent means the component did not report one.
 */
data class QuoteIdAnswer(val id: String, val reduction: QuoteReductionAnswer? = null)

/**
 * How a requested reduction ended: [stage] is NONE, IMAGES_DOWNSAMPLED or RASTERISED (the text layer
 * is lost), [storedBytes] the size of the one stored file.
 */
data class QuoteReductionAnswer(val stage: String, val storedBytes: Long)

/** Answer of the submit call: the number the offer received. */
data class QuoteNumberAnswer(val number: String)

/** Answer of the invoice order call: the id that both answers of the invoicing side echo. */
data class QuoteInvoiceOrderAnswer(val orderId: java.util.UUID)

/** Body of every error answer; [status] repeats the HTTP status so a logged body is self-explanatory. */
data class QuoteErrorView(val status: Int, val message: String)

/** The master data resync was requested; [requestId] correlates the answer in the logs. */
data class ReplicaResyncView(val requestId: String)

/** The document backfill ran; [requested] is the number of documents it asked the worker for. */
data class DocumentBackfillView(val requested: Int)

/**
 * Answer of the send call. An offer leaves the house only through the mail component: the send call
 * composes a mail draft there and hands back where the user finishes it ([draftId], [composeUrl]).
 * The call is only allowed while the send gate is open, so the dispatch copy always exists. The
 * offer counts as sent only when the mail component reports the send, never by this answer.
 */
data class QuoteDispatchHandoverView(val draftId: String, val composeUrl: String)

/** Answer of the send-gate query: the name of the [QuoteSendGate] value that currently applies. */
data class QuoteSendGateView(val gate: String)
