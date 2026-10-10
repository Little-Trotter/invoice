package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.domain.shared.InvoiceFormat
import jakarta.persistence.Tuple

/**
 * The "parties" cohesive group: sender/recipient identity and the billing/delivery
 * addresses of the header-tuple projection [InvoiceReadRepositoryImpl.findInvoiceDetailDto]
 * reads. [mapSender] is the one function here with an IO-derived input ([additionalVatCountries])
 * — the query for it (`SellerVatRegistration`) stays in
 * [InvoiceReadRepositoryImpl] itself, only run when a sender is actually present.
 */
internal object InvoiceDetailPartyFieldMapper {

    fun mapSender(header: Tuple, senderId: Long, additionalVatCountries: List<String>): PartyDto = PartyDto(
        id = senderId,
        name = InvoiceDetailRowSupport.parseString(header.get("senderName")),
        taxNumber = InvoiceDetailRowSupport.parseString(header.get("senderTaxNumber")),
        vatId = InvoiceDetailRowSupport.parseString(header.get("senderVatId")),
        email = InvoiceDetailRowSupport.parseString(header.get("senderEmail")),
        phone = InvoiceDetailRowSupport.parseString(header.get("senderPhone")),
        commercialRegisterNumber = InvoiceDetailRowSupport.parseString(header.get("senderCommercialRegisterNumber")),
        courtOfJurisdiction = InvoiceDetailRowSupport.parseString(header.get("senderCourtOfJurisdiction")),
        registerCourt = InvoiceDetailRowSupport.parseString(header.get("senderRegisterCourt")),
        iban = InvoiceDetailRowSupport.parseString(header.get("senderIban")),
        managingDirector = InvoiceDetailRowSupport.parseString(header.get("senderManagingDirector")),
        bankName = InvoiceDetailRowSupport.parseString(header.get("senderBankName")),
        bic = InvoiceDetailRowSupport.parseString(header.get("senderBic")),
        additionalVatRegistrationCountries = additionalVatCountries,
    )

    fun mapRecipient(header: Tuple): PartyDto? {
        val recipientId = header.get("recipientId") ?: return null
        return PartyDto(
            id = (recipientId as Number).toLong(),
            name = InvoiceDetailRowSupport.parseString(header.get("recipientName")),
            customerNumber = InvoiceDetailRowSupport.parseString(header.get("recipientCustomerNumber")),
            vatId = InvoiceDetailRowSupport.parseString(header.get("recipientVatId")),
            email = InvoiceDetailRowSupport.parseString(header.get("recipientEmail")),
            phone = InvoiceDetailRowSupport.parseString(header.get("recipientPhone")),
            contactPerson = InvoiceDetailRowSupport.parseString(header.get("recipientContactPerson")),
            routingId = InvoiceDetailRowSupport.parseString(header.get("recipientRoutingId")),
            b2bProfileId = (header.get("recipientB2bProfileId") as Number?)?.toLong(),
            // Reached only when a recipient exists (guarded by the `recipientId != null`
            // check above), and recipient.invoice_format is a non-nullable column, so the `as?`
            // cast-failure fallback was unreachable.
            invoiceFormat = (header.get("recipientInvoiceFormat") as InvoiceFormat).ordinal,
        )
    }

    fun mapBillingAddress(header: Tuple): AddressDto? {
        val id = header.get("billingAddressId") ?: return null
        return AddressDto(
            id = (id as Number).toLong(),
            street = InvoiceDetailRowSupport.parseString(header.get("billingStreet")),
            postalCode = InvoiceDetailRowSupport.parseString(header.get("billingPostalCode")),
            city = InvoiceDetailRowSupport.parseString(header.get("billingCity")),
            // Reached only when a billing address exists, and address.country is a
            // non-nullable column, so the safe-call's null branch was unreachable.
            countryAlpha2 = header.get("billingCountry").toString(),
        )
    }

    fun mapDeliveryAddress(header: Tuple): AddressDto? {
        val id = header.get("deliveryAddressId") ?: return null
        return AddressDto(
            id = (id as Number).toLong(),
            street = InvoiceDetailRowSupport.parseString(header.get("deliveryStreet")),
            postalCode = InvoiceDetailRowSupport.parseString(header.get("deliveryPostalCode")),
            city = InvoiceDetailRowSupport.parseString(header.get("deliveryCity")),
            // See mapBillingAddress's own note just above.
            countryAlpha2 = header.get("deliveryCountry").toString(),
        )
    }
}
