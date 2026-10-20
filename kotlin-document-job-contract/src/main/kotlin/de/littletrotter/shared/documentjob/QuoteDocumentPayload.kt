package de.littletrotter.shared.documentjob

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue

/** Job type and subjects of the offer document, next to the invoice job type of the same worker. */
object QuoteDocumentJob {
  /** The `type` of a [DocumentJobRequest] that renders an offer PDF. */
  const val TYPE = "QUOTE_PDF"

  /** Where the worker reports the outcome of an offer job; the offer service consumes only this subject. */
  const val RESULT_SUBJECT = "quote-document-job-results"

  /** Result reference holding the stored PDF's object key. */
  const val REF_OBJECT_KEY = "objectKey"

  /** Result reference holding the PDF's SHA-256 (hex). */
  const val REF_SHA256 = "sha256"

  /** Result reference holding the validation report as JSON; absent on a failed job. */
  const val REF_VALIDATION_REPORT = "validationReport"

  /** Result reference holding the failure reason of a failed job. */
  const val REF_ERROR = "error"
}

/**
 * Which copy of an offer is rendered. The mark decides the watermark: a draft and a copy under
 * review are stamped, the copy that is dispatched to the customer is not.
 */
enum class QuoteDocumentMark { DRAFT, REVIEW, DISPATCH }

/** One address block of an offer; the seller additionally carries the letterhead and footer facts. */
data class QuoteDocumentParty(
  val name: String,
  val street: String? = null,
  val postalCode: String? = null,
  val city: String? = null,
  val countryCode: String? = null,
  val vatId: String? = null,
  val taxNumber: String? = null,
  val customerNumber: String? = null,
  val phone: String? = null,
  val email: String? = null,
  val website: String? = null,
  val iban: String? = null,
  val bic: String? = null,
  val bankName: String? = null,
  val commercialRegisterNumber: String? = null,
  val registerCourt: String? = null,
  val managingDirector: String? = null,
)

/** A header reference of the customer's business profile, identified by its BT code. */
data class QuoteDocumentReference(val btCode: Int, val value: String)

/**
 * One offer line with the amounts exactly as the offer shows them: [unitPrice] and [lineAmount]
 * are net or gross according to [QuoteDocumentPayload.grossPrices], computed by the offer domain.
 * [unit] is the name of the unit as the offer holds it; the worker prints it in the document language.
 * Numbers travel as plain decimal strings and are formatted for the document language by the worker.
 */
data class QuoteDocumentItem(
  val position: Int,
  val description: String,
  val quantity: String,
  val unit: String,
  val unitPrice: String,
  val lineAmount: String,
  val vatRate: String,
  val articleNumber: String? = null,
  val customerReference: String? = null,
  val customerArticleName: String? = null,
)

/** One VAT group of the offer totals. */
data class QuoteDocumentVatGroup(val rate: String, val base: String, val vat: String)

/** Totals as computed by the offer domain; the worker only formats them. */
data class QuoteDocumentTotals(
  val net: String,
  val vat: String,
  val gross: String,
  val groups: List<QuoteDocumentVatGroup>,
)

/**
 * A file the worker embeds into the PDF, referenced instead of carried: the offer service has
 * stored it under [objectKey] in the worker's bucket, and the worker rejects the job when the
 * stored bytes do not match [sha256] (hex). Name, media type and description are stated here so
 * the worker needs no knowledge of what the file is.
 */
data class QuoteDocumentAttachmentRef(
  val objectKey: String,
  val sha256: String,
  val fileName: String,
  val mimeType: String,
  val description: String,
)

/**
 * A customer file of the offer the worker shows as rasterised pages behind the offer pages, as the
 * invoice worker shows receipts. It is never embedded: the offer data only refers to it by name
 * and checksum. The offer service has staged it under [objectKey] in the worker's bucket, and the
 * worker rejects the job when the stored bytes do not match [sha256] (hex).
 */
data class QuoteDocumentReceiptRef(
  val objectKey: String,
  val sha256: String,
  val fileName: String,
  val mimeType: String,
  val description: String? = null,
)

/**
 * Everything the worker needs to render one offer PDF. Free texts are in the shared free-text
 * storage syntax. An offer has no payment terms, only the free-text [noteText].
 */
data class QuoteDocumentPayload(
  val quoteId: String,
  val quoteNumber: String?,
  val quoteDate: String,
  val validUntil: String,
  val mark: QuoteDocumentMark,
  val language: String,
  val grossPrices: Boolean,
  val seller: QuoteDocumentParty,
  val customer: QuoteDocumentParty,
  val items: List<QuoteDocumentItem>,
  val totals: QuoteDocumentTotals,
  val currency: String = "EUR",
  val subject: String? = null,
  val introText: String? = null,
  val closingText: String? = null,
  val noteText: String? = null,
  val references: List<QuoteDocumentReference> = emptyList(),
  val attachment: QuoteDocumentAttachmentRef? = null,
  /** Customer files shown as pages behind the offer, in the order the offer lists them; additive, absent means none. */
  val receipts: List<QuoteDocumentReceiptRef> = emptyList(),
)

/** Converts the offer payload to and from the generic map a [DocumentJobRequest] carries. */
object QuoteDocumentPayloadCodec {

  private val mapper: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  /** The `payload` map of a `QUOTE_PDF` request. */
  fun toMap(payload: QuoteDocumentPayload): Map<String, Any> =
    mapper.convertValue(payload)

  /** Reads the payload back; fails with an [IllegalArgumentException] when it is not an offer payload. */
  fun fromMap(payload: Map<String, Any>?): QuoteDocumentPayload {
    requireNotNull(payload) { "A QUOTE_PDF job carries a payload" }
    return mapper.convertValue(payload)
  }
}
