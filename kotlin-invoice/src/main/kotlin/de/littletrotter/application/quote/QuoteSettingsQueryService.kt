package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteSettings
import de.littletrotter.application.quote.port.inbound.QuoteSettingsQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteSettingsRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteSettingsQueryPort`; the call itself is the outbound `QuoteSettingsRemote`. */
@ApplicationScoped
class QuoteSettingsQueryService @Inject constructor(
    private val remote: QuoteSettingsRemote,
) : QuoteSettingsQueryPort {

    override fun settings(): QuoteSettings =
        remote.settings()
}
