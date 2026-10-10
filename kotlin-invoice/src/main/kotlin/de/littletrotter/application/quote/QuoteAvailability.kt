package de.littletrotter.application.quote

import de.littletrotter.application.settings.FeatureOffers
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Is the separate offer app part of this instance at all, and may users see it?
 *
 * `quote.enabled` (`QUOTE_ENABLED`, default **false**) mirrors whether the offer component is
 * deployed behind the same reverse proxy. Like the mail switch it is a deployment fact, not a
 * reachability probe: the drawer link must be decided without a call into a component that may
 * be starting. [offered] adds the administrator's runtime switch on top, so menu, routes and write
 * calls share one rule. Lives in the application layer because `ui` reads it directly and may not
 * depend on `infrastructure`.
 */
@ApplicationScoped
class QuoteAvailability @Inject constructor(
    @param:ConfigProperty(name = "quote.enabled", defaultValue = "false")
    val enabled: Boolean,
    // Defaulted (null): Arc injects the real bean; component tests without it read "switch on",
    // so the deployment fact alone decides.
    private val featureSettingsGate: FeatureSettingsGate? = null,
) {

    /** Offers are shown and may be changed: deployed AND not switched off in System -> Settings. */
    val offered: Boolean
        get() = FeatureOffers.quoteOffered(enabled, featureSettingsGate?.isEnabled(FeatureFlag.QUOTE) ?: true)
}
