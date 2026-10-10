package de.littletrotter.ui.view.settings

import com.vaadin.flow.component.Component
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsQueryPort
import de.littletrotter.application.mail.port.inbound.UpdateArchiveHandoverSettingsUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant

/**
 * Builds the archive hand-over card of the settings page, or nothing while the mail component is
 * not deployed or the user may not see the setting (the component only answers administrators, so
 * there is nothing to show read-only).
 */
@ApplicationScoped
class ArchiveHandoverSettingsCardFactory @Inject constructor(
    private val availability: MailAvailability,
    private val query: ArchiveHandoverSettingsQueryPort,
    private val updater: UpdateArchiveHandoverSettingsUseCase,
    private val roles: AppRoleProvider,
) {

    fun create(lastChange: (Instant?, String?) -> Component): ArchiveHandoverSettingsCard? =
        if (availability.enabled && roles.hasPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)) {
            ArchiveHandoverSettingsCard(query, updater, true, lastChange)
        } else {
            null
        }
}
