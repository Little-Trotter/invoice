package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.spi.party.SellerVatRegistrationsExtension

/** Input port through which the sender form obtains the registered VAT-registration extension. */
fun interface SellerVatRegistrationsExtensionQueryPort {
    /** The registered extension; `null` when none is present. */
    fun enabledExtension(): SellerVatRegistrationsExtension?
}
