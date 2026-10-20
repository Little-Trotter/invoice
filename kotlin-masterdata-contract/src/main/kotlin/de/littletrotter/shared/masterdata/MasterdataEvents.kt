package de.littletrotter.shared.masterdata

/**
 * Wire DTOs of the master data events. Every upsert carries the complete current state of its
 * aggregate (not a delta), so a replica applies it idempotently: it keeps the row with the higher
 * [version]. Money and percentages travel as plain decimal strings, dates as ISO-8601, enums by
 * name and countries as ISO alpha-2 codes; a consumer never needs a producer domain type.
 */

data class AddressWire(
  val id: Long?,
  val type: String,
  val street: String,
  val postalCode: String,
  val city: String,
  val country: String,
  val isDefault: Boolean,
)

/** A customer-side value of a B2B profile field; [btCode] names the field, [value] may be empty. */
data class CustomerB2bValueWire(
  val profileFieldId: Long,
  val btCode: String?,
  val value: String?,
)

data class CustomerUpserted(
  val id: Long,
  val version: Long,
  val customerNumber: String?,
  val name: String,
  val vatId: String?,
  val email: String?,
  val phone: String?,
  val contactPerson: String?,
  val customerType: String?,
  val documentLanguage: String,
  val invoiceFormat: String,
  val reverseChargePreference: String,
  val routingId: String?,
  val iban: String?,
  val b2bProfileId: Long?,
  val addresses: List<AddressWire>,
  val b2bValues: List<CustomerB2bValueWire>,
)

data class CustomerDeleted(val id: Long)

data class SellerVatRegistrationWire(val country: String, val vatId: String)

data class SellerUpserted(
  val id: Long,
  val version: Long,
  val sellerNumber: String?,
  val name: String,
  val invoicePrefix: String,
  val isDefault: Boolean,
  val taxNumber: String?,
  val vatId: String?,
  val iban: String?,
  val bic: String?,
  val bankName: String?,
  val phone: String?,
  val email: String?,
  val website: String?,
  val commercialRegisterNumber: String?,
  val registerCourt: String?,
  val managingDirector: String?,
  val legalForm: String?,
  val defaultPaymentTermId: Long?,
  val addresses: List<AddressWire>,
  val additionalVatRegistrations: List<SellerVatRegistrationWire>,
)

data class SellerDeleted(val id: Long)

data class ArticleTaxVariantWire(val country: String, val taxRateId: Long?)

/** The identifiers one customer uses for an article; at least one of the two is set. */
data class ArticleCustomerReferenceWire(
  val recipientId: Long,
  val buyerArticleNumber: String?,
  val buyerArticleName: String?,
)

data class ArticleUpserted(
  val id: Long,
  val version: Long,
  val articleNumber: String?,
  val name: String,
  val description: String?,
  val category: String,
  val defaultUnit: String,
  val defaultQuantity: String,
  val defaultUnitPriceNet: String,
  val defaultTaxRateId: Long?,
  val isDefault: Boolean,
  val taxVariants: List<ArticleTaxVariantWire>,
  val customerReferences: List<ArticleCustomerReferenceWire> = emptyList(),
)

data class ArticleDeleted(val id: Long)

data class TaxRateUpserted(
  val id: Long,
  val version: Long,
  val name: String,
  val code: String?,
  val category: String?,
  val rate: String,
  val country: String,
  val validFrom: String?,
  val validTo: String?,
  val isDefault: Boolean,
  val isReference: Boolean,
  val isHotelRate: Boolean,
  val isActive: Boolean,
  val isSelectable: Boolean,
  val exemptionReasonCode: String?,
  val exemptionReasonText: String?,
  val legalBasis: String?,
)

data class TaxRateDeleted(val id: Long)

/** A number range of the quote subject; the counter itself is kept by the consumer. */
data class NumberRangeUpserted(
  val id: Long,
  val version: Long,
  val code: String,
  val subject: String,
  val fixedPrefix: String?,
  val datePart: String,
  val counterWidth: Int,
  val resetCycle: String,
  val isActive: Boolean,
  val isDefault: Boolean,
)

data class NumberRangeDeleted(val id: Long)

data class PaymentTermUpserted(
  val id: Long,
  val version: Long,
  /** Display names by language code. */
  val names: Map<String, String>,
  val description: String?,
  val dueInDays: Int,
  val discountPercent: String,
  val discountDays: Int?,
  val isDefault: Boolean,
  val isActive: Boolean,
)

data class PaymentTermDeleted(val id: Long)

/** One field of a B2B profile: business term, requirement, placement, prefill and order. */
data class B2bProfileFieldWire(
  val id: Long,
  val btCode: String?,
  val businessDefinition: String,
  val requirement: String,
  val conditionBtCode: String?,
  val placement: String,
  val sortOrder: Int,
  val defaultValue: String?,
)

data class B2bProfileUpserted(
  val id: Long,
  val version: Long,
  val name: String,
  val description: String?,
  val active: Boolean,
  val countries: List<String>,
  val fields: List<B2bProfileFieldWire>,
)

data class B2bProfileDeleted(val id: Long)
