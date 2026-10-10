package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Changes the offer settings (administrators only). */
interface UpdateQuoteSettingsUseCase : CommandEntryPoint {

    /** Sets the default validity of new offers to [validityDays] and the machine-readable form of their PDFs to [xmlFormat]. */
    fun update(validityDays: Long, xmlFormat: QuoteXmlFormat)
}
