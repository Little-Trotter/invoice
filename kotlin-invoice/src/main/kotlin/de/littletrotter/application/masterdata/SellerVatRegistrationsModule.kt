package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.spi.party.SellerVatRegistrationRow
import de.littletrotter.spi.party.SellerVatRegistrationsExtension
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The seller's additional, non-domestic VAT registrations (0..n rows: country + local
 * VAT id), curated to [Country.supportedSellerVatRegistrationCountries].
 */
@ApplicationScoped
class SellerVatRegistrationsModule @Inject constructor(private val time: TimeSource) : SellerVatRegistrationsExtension {

    override val id: String = "sellerVatRegistrations"
    override val supportedCountries: List<String> =
        Country.supportedSellerVatRegistrationCountries(time.today()).map { it.alpha2 }.sorted()

    override fun read(senderProfile: Any): List<SellerVatRegistrationRow> =
        (senderProfile as SenderProfile).additionalVatRegistrations.map {
            // vatId is only ever null on a half-built row, which SellerVatRegistration.of
            // (the sole construction path) never produces; see its own KDoc.
            SellerVatRegistrationRow(it.country.alpha2, it.vatId!!.value, it.taxNumber?.value)
        }

    override fun write(senderProfile: Any, rows: List<SellerVatRegistrationRow>) {
        val profile = senderProfile as SenderProfile
        val next = rows.map { row ->
            val country = Country.fromAlpha2(row.country)
                ?: throw InvalidParameterException("Unbekanntes Länderkürzel '${row.country}'.")
            SellerVatRegistrationCommand(
                country,
                row.vatId.takeIf { it.isNotBlank() }?.let { VatId.of(it) },
                ForeignTaxNumber.ofNullable(row.taxNumber),
            )
        }
        profile.updateVatRegistrations(next, time.today())
    }
}
