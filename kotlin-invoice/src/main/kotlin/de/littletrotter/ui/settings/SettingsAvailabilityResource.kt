package de.littletrotter.ui.settings

import de.littletrotter.application.mail.port.inbound.MailAvailabilityQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.settings.FeatureAvailability
import de.littletrotter.application.settings.port.inbound.FeatureSettingsQueryPort
import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.domain.settings.FeatureFlag
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * SFS — read-only exposure of the two feature switches and the states derived from them.
 *
 * Same posture as [de.littletrotter.ui.i18n.I18nBundleResource] (which this exists to mirror): no
 * `@PermitAll` of its own, it simply inherits the deployment's auth policy. There is deliberately
 * no write side — flipping a switch is a *UI* action (`System → Einstellungen`), so the only actor
 * that can write is one holding a real `SYSTEM_VIEW` session through the route guard; a REST write
 * endpoint would add a second authority to secure without any client that needs it.
 *
 * Who reads it: the admin-tools Playwright suite (`tests/system/sfs-settings-flags.spec.ts`) as a
 * *precondition/state probe* — e.g. "is the mail component even deployed before asserting mail
 * UI" — plus ops scripts. The behavioural expectations themselves are asserted through the UI
 * like in every other spec of that suite; the kotlin e2e ITs read the gate/repository directly
 * (that module has no HTTP client at all).
 *
 * `flags` is the raw persisted/catalog state ([FeatureFlag.defaultEnabled] when a row is missing —
 * the gate's fail-open contract); `effective` is what consumers act on, with `emailDispatch` being
 * the AND of the deployment fact ([MailAvailabilityQueryPort]) and the flag ([FeatureAvailability.mailAvailable]).
 */
@Path("/api/settings/availability")
class SettingsAvailabilityResource @Inject constructor(
    private val gate: FeatureSettingsQueryPort,
    private val mailAvailability: MailAvailabilityQueryPort,
    private val quoteAvailability: QuoteAvailabilityQueryPort,
    private val wordingGate: WordingProfileQueryPort,
) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun availability(): Map<String, Any> = mapOf(
        "flags" to FeatureFlag.entries.associate { it.key to gate.isEnabled(it) },
        "effective" to mapOf(
            "perdiemUi" to gate.isEnabled(FeatureFlag.PER_DIEM_UI),
            "emailDeployment" to mailAvailability.enabled,
            "emailDispatch" to FeatureAvailability.mailAvailable(
                mailAvailability.enabled,
                gate.isEnabled(FeatureFlag.EMAIL_SEND),
            ),
            "quoteDeployment" to quoteAvailability.enabled,
            "quoteOffered" to FeatureAvailability.quoteAvailable(
                quoteAvailability.enabled,
                gate.isEnabled(FeatureFlag.QUOTE),
            ),
            // Read-only: this endpoint has no write side (see this class's own KDoc), the
            // wording card writes through WordingProfileQueryPort directly.
            "wordingProfile" to wordingGate.currentProfile().name,
        ),
    )
}
