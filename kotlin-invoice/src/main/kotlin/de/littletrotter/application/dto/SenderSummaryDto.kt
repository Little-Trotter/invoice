package de.littletrotter.application.dto

/**
 * [de.littletrotter.application.masterdata.port.inbound.SenderQueryPort]'s read model for a
 * sender profile — the invoice-list sender filter and the create-invoice dialog's seller
 * ComboBox, which never need the full entity (SPI extension pairs, delivery addresses, PDF
 * template preferences, …).
 */
data class SenderSummaryDto(
    val id: Long,
    val name: String,
    val vatId: String?,
    val isDefault: Boolean,
    val billingAddressCity: String?,
    val additionalVatRegistrations: List<SellerVatRegistrationDto> = emptyList(),
)

/** One additional, non-German VAT registration of a sender
 *  ([de.littletrotter.domain.masterdata.SellerVatRegistration]'s read side). */
data class SellerVatRegistrationDto(
    val countryAlpha2: String,
    val vatId: String,
)
