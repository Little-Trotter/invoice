package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteSettings

/** Remote side of `QuoteSettingsQueryPort`: the offer component call behind the application service. */
fun interface QuoteSettingsRemote {

    fun settings(): QuoteSettings
}
