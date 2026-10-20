package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.outbound.UpdateQuoteSettingsRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `PUT /quote/api/settings`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpUpdateQuoteSettingsAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : UpdateQuoteSettingsRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun update(validityDays: Long, xmlFormat: QuoteXmlFormat) =
        apiClient.put(
            QuoteApiPaths.SETTINGS,
            QuoteWireCodec.encode(QuoteRequestTranslator.toSettingsRequest(validityDays, xmlFormat)),
            guarded = false,
        )
}
