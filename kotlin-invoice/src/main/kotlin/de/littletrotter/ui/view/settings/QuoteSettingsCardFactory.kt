package de.littletrotter.ui.view.settings

import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.port.inbound.QuoteSettingsQueryPort
import de.littletrotter.application.quote.port.inbound.UpdateQuoteSettingsUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.shared.AuditActorProvider
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant

/**
 * Builds the quote card of the settings page, or nothing while the quote component is not
 * deployed (a switched-off module keeps its card, otherwise it could not be switched on again).
 * It owns the narrow ports the card needs so the settings view itself stays small.
 */
@ApplicationScoped
class QuoteSettingsCardFactory @Inject constructor(
    private val availability: QuoteAvailability,
    private val settingsQuery: QuoteSettingsQueryPort,
    private val updateSettings: UpdateQuoteSettingsUseCase,
    private val roles: AppRoleProvider,
    private val featureGate: FeatureSettingsGate,
    private val actorProvider: AuditActorProvider,
) {

    fun create(lastChange: (Instant?) -> com.vaadin.flow.component.Component): QuoteSettingsCard? =
        if (availability.enabled) {
            QuoteSettingsCard(
                settingsQuery,
                updateSettings,
                QuoteSwitch(featureGate, actorProvider),
                roles.hasPermission(AppPermission.QUOTE_SETTINGS_EDIT),
                lastChange,
            )
        } else {
            null
        }
}
