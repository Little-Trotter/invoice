package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.inbound.UpdateQuoteSettingsUseCase
import de.littletrotter.application.quote.port.outbound.UpdateQuoteSettingsRemote
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `UpdateQuoteSettingsUseCase`; the call itself is the outbound `UpdateQuoteSettingsRemote`. */
@ApplicationScoped
class UpdateQuoteSettingsService @Inject constructor(
    private val remote: UpdateQuoteSettingsRemote,
) : UpdateQuoteSettingsUseCase {

    @RequiresPermission(AppPermission.QUOTE_SETTINGS_EDIT)
    override fun update(validityDays: Long, xmlFormat: QuoteXmlFormat) =
        remote.update(validityDays, xmlFormat)
}
