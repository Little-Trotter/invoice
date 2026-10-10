package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat

/** Remote side of `UpdateQuoteSettingsUseCase`: the offer component call behind the application service. */
fun interface UpdateQuoteSettingsRemote {

    /** Sets the default validity of new offers to [validityDays] and the machine-readable form of their PDFs to [xmlFormat]. */
    fun update(validityDays: Long, xmlFormat: QuoteXmlFormat)
}
