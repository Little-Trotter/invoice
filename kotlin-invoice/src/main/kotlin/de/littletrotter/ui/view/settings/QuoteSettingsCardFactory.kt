package de.littletrotter.ui.view.settings

import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteSettingsQueryPort
import de.littletrotter.application.quote.port.inbound.UpdateQuoteSettingsUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.settings.port.inbound.ManageFeatureSettingsUseCase
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
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
    private val availability: QuoteAvailabilityQueryPort,
    private val settingsQuery: QuoteSettingsQueryPort,
    private val updateSettings: UpdateQuoteSettingsUseCase,
    private val roles: AppRoleQueryPort,
    private val featureGate: ManageFeatureSettingsUseCase,
    private val actorProvider: AuditActorQueryPort,
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
