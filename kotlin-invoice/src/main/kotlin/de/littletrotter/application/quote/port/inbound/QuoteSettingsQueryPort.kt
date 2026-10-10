package de.littletrotter.application.quote.port.inbound

/** Reads the offer settings. */
fun interface QuoteSettingsQueryPort {

    fun settings(): QuoteSettings
}
