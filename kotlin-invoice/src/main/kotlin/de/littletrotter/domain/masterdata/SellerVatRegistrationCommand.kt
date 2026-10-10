package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.value.VatId

/** Immutable user input for replacing a sender's complete additional VAT registration set.
 *  [vatId] carries the value type: the raw string a caller has (a Vaadin Binder form
 *  bean field, an SPI row) converts to [VatId] when building this command, not inside it. */
data class SellerVatRegistrationCommand(
    val country: Country,
    val vatId: VatId,
)
