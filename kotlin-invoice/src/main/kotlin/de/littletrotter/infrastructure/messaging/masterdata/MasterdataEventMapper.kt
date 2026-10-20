package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.masterdata.AddressWire
import de.littletrotter.shared.masterdata.ArticleCustomerReferenceWire
import de.littletrotter.shared.masterdata.ArticleDeleted
import de.littletrotter.shared.masterdata.ArticleTaxVariantWire
import de.littletrotter.shared.masterdata.ArticleUpserted
import de.littletrotter.shared.masterdata.B2bProfileDeleted
import de.littletrotter.shared.masterdata.B2bProfileFieldWire
import de.littletrotter.shared.masterdata.B2bProfileUpserted
import de.littletrotter.shared.masterdata.CustomerB2bValueWire
import de.littletrotter.shared.masterdata.CustomerDeleted
import de.littletrotter.shared.masterdata.CustomerUpserted
import de.littletrotter.shared.masterdata.NumberRangeDeleted
import de.littletrotter.shared.masterdata.NumberRangeUpserted
import de.littletrotter.shared.masterdata.PaymentTermDeleted
import de.littletrotter.shared.masterdata.PaymentTermUpserted
import de.littletrotter.shared.masterdata.SellerDeleted
import de.littletrotter.shared.masterdata.SellerUpserted
import de.littletrotter.shared.masterdata.SellerVatRegistrationWire
import de.littletrotter.shared.masterdata.TaxRateDeleted
import de.littletrotter.shared.masterdata.TaxRateUpserted
import jakarta.enterprise.context.ApplicationScoped

/**
 * Maps the master data aggregates to the wire DTOs of the master data contract and encodes them.
 *
 * Reads only; it never touches the persistence context beyond the entity graph it is handed, so
 * the lazy associations it follows need the caller's open session. The customer-side B2B values
 * are not part of the recipient entity and are passed in, as are the addresses: the caller reads them
 * from the database, because the in-memory collection of an owner need not know an address that was
 * saved on its own.
 */
@ApplicationScoped
class MasterdataEventMapper {

    fun customer(recipient: Recipient, addresses: List<Address>, b2bValues: List<RecipientB2bFieldValue>, fieldTerms: Map<Long, B2bTerm?>) =
        CustomerUpserted(
            id = requireNotNull(recipient.id),
            version = recipient.version ?: 0,
            customerNumber = recipient.customerNumber?.value,
            name = recipient.name,
            vatId = recipient.vatId?.value,
            email = recipient.email?.value,
            phone = recipient.phone,
            contactPerson = recipient.contactPerson,
            customerType = recipient.customerType?.name,
            documentLanguage = recipient.documentLanguage,
            invoiceFormat = recipient.invoiceFormat.name,
            reverseChargePreference = recipient.reverseChargePreference.name,
            routingId = recipient.routingId,
            iban = recipient.iban?.value,
            b2bProfileId = recipient.b2bProfile?.id,
            addresses = addresses.map(::address),
            b2bValues = b2bValues.map {
                CustomerB2bValueWire(it.profileFieldId, fieldTerms[it.profileFieldId]?.let(::btCode), it.value)
            },
        )

    fun seller(profile: SenderProfile, addresses: List<Address>) = SellerUpserted(
        id = requireNotNull(profile.id),
        version = profile.version ?: 0,
        sellerNumber = profile.sellerNumber,
        name = profile.name,
        invoicePrefix = profile.invoicePrefix,
        isDefault = profile.isDefault,
        taxNumber = profile.taxNumber?.value,
        vatId = profile.vatId?.value,
        iban = profile.iban?.value,
        bic = profile.bic?.value,
        bankName = profile.bankName,
        phone = profile.phone,
        email = profile.email?.value,
        website = profile.website,
        commercialRegisterNumber = profile.commercialRegisterNumber,
        registerCourt = profile.registerCourt,
        managingDirector = profile.managingDirector,
        legalForm = profile.legalForm?.name,
        defaultPaymentTermId = profile.defaultPaymentTerm?.id,
        addresses = addresses.map(::address),
        additionalVatRegistrations = profile.additionalVatRegistrations.mapNotNull { registration ->
            registration.vatId?.let { SellerVatRegistrationWire(registration.country.alpha2, it.value) }
        },
    )

    fun article(article: Article, customerRefs: List<ArticleRecipientRef>) = ArticleUpserted(
        id = requireNotNull(article.id),
        version = article.version ?: 0,
        articleNumber = article.articleNumber?.value,
        name = article.name,
        description = article.description,
        category = article.category.name,
        defaultUnit = article.defaultUnit.name,
        defaultQuantity = article.defaultQuantity.toPlainString(),
        defaultUnitPriceNet = article.defaultUnitPriceNet.amount.toPlainString(),
        defaultTaxRateId = article.defaultTaxRateRef?.id,
        isDefault = article.isDefault,
        taxVariants = article.taxVariants.map { ArticleTaxVariantWire(it.country.alpha2, it.taxRateRef?.id) },
        customerReferences = customerRefs.mapNotNull { ref ->
            ref.recipient?.id?.let { ArticleCustomerReferenceWire(it, ref.buyerArticleNumber, ref.buyerArticleName) }
        },
    )

    fun taxRate(rate: TaxRate) = TaxRateUpserted(
        id = requireNotNull(rate.id),
        version = rate.version ?: 0,
        name = rate.name,
        code = rate.code,
        category = rate.category,
        rate = rate.rate.value.toPlainString(),
        country = rate.country.alpha2,
        validFrom = rate.validFrom?.toString(),
        validTo = rate.validTo?.toString(),
        isDefault = rate.isDefault,
        isReference = rate.isReference,
        isHotelRate = rate.isHotelRate,
        isActive = rate.isActive,
        isSelectable = rate.isSelectable,
        exemptionReasonCode = rate.exemptionReasonCode,
        exemptionReasonText = rate.exemptionReasonText,
        legalBasis = rate.legalBasis,
    )

    fun paymentTerm(term: PaymentTerm) = PaymentTermUpserted(
        id = requireNotNull(term.id),
        version = term.version ?: 0,
        names = term.nameGroup?.entries.orEmpty()
            .mapNotNull { entry -> entry.language?.code?.let { it to entry.value } }
            .toMap(),
        description = term.description,
        dueInDays = term.dueInDays,
        discountPercent = term.discountPercent.value.toPlainString(),
        discountDays = term.discountDays,
        isDefault = term.isDefault,
        isActive = term.isActive,
    )

    fun b2bProfile(profile: B2bProfile) = B2bProfileUpserted(
        id = requireNotNull(profile.id),
        version = profile.version ?: 0,
        name = profile.name,
        description = profile.description,
        active = profile.active,
        countries = profile.countries.map { it.alpha2 }.sorted(),
        fields = profile.fieldList.sortedBy { it.sortOrder }.map(::profileField),
    )

    fun numberRange(range: NumberRange) = NumberRangeUpserted(
        id = requireNotNull(range.id),
        version = range.version ?: 0,
        code = range.code,
        subject = range.subject.name,
        fixedPrefix = range.fixedPrefix,
        datePart = range.datePart.name,
        counterWidth = range.counterWidth,
        resetCycle = range.resetCycle.name,
        isActive = range.isActive,
        isDefault = range.isDefault,
    )

    /** The event of a removed aggregate: only its id is left. */
    fun deleted(aggregate: MasterdataAggregate, id: Long): Any = when (aggregate) {
        MasterdataAggregate.CUSTOMER -> CustomerDeleted(id)
        MasterdataAggregate.SELLER -> SellerDeleted(id)
        MasterdataAggregate.ARTICLE -> ArticleDeleted(id)
        MasterdataAggregate.TAX_RATE -> TaxRateDeleted(id)
        MasterdataAggregate.PAYMENT_TERM -> PaymentTermDeleted(id)
        MasterdataAggregate.B2B_PROFILE -> B2bProfileDeleted(id)
        MasterdataAggregate.NUMBER_RANGE -> NumberRangeDeleted(id)
    }

    private fun address(address: Address) = AddressWire(
        id = address.id,
        type = address.addressType.name,
        street = address.street,
        postalCode = address.postalCode.value,
        city = address.city,
        country = address.country.alpha2,
        isDefault = address.isDefault,
    )

    private fun profileField(field: B2bProfileField) = B2bProfileFieldWire(
        id = requireNotNull(field.id),
        btCode = field.term?.let(::btCode),
        businessDefinition = field.businessDefinition,
        requirement = field.requirement.name,
        conditionBtCode = field.conditionTerm?.let(::btCode),
        placement = field.placement.name,
        sortOrder = field.sortOrder,
        defaultValue = field.defaultValue,
    )

    private fun btCode(term: B2bTerm) = "BT-${term.code}"
}
