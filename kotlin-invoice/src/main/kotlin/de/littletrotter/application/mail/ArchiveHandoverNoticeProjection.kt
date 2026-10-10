package de.littletrotter.application.mail

import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Turns the component's archive hand-over notices into message-center entries.
 *
 * Delivery is at-least-once and every sent mail without an address reports the same condition, so
 * both reasons are recorded once per subject: a missing address is one standing entry for the whole
 * instance (withdrawn when an address is configured, see [MISSING_ADDRESS_KEY]), a failed hand-over
 * one entry per journal row.
 */
@ApplicationScoped
class ArchiveHandoverNoticeProjection @Inject constructor(
    private val messageCenter: MessageCenterService,
) {

    /** Records [notice] unless the same condition is already shown; `true` when a new entry was created. */
    fun project(notice: ArchiveHandoverNotice): Boolean =
        messageCenter.recordOnce(contentOf(notice)) != null

    private fun contentOf(notice: ArchiveHandoverNotice): SystemMessageContent =
        when (notice.reason) {
            ArchiveHandoverNoticeReason.ADDRESS_MISSING -> SystemMessageContent(
                severity = SystemMessageSeverity.WARNING,
                source = SystemMessageSource.SYSTEM,
                messageKey = MISSING_ADDRESS_KEY,
                resolvedText = "Keine Archivadresse hinterlegt: versendete E-Mails werden nicht an das E-Mail-Archiv übergeben. " +
                    "Die Adresse wird unter System, Einstellungen eingetragen.",
                refType = REF_TYPE_SETTING,
                refId = REF_ID_ADDRESS,
            )
            ArchiveHandoverNoticeReason.HANDOVER_FAILED -> SystemMessageContent(
                severity = SystemMessageSeverity.ERROR,
                source = SystemMessageSource.SYSTEM,
                messageKey = FAILED_KEY,
                args = listOf(notice.referenceKey, notice.error.orEmpty()),
                resolvedText = "Übergabe an das E-Mail-Archiv fehlgeschlagen für ${notice.referenceKey}: ${notice.error ?: "unbekannter Fehler"}. " +
                    "Im E-Mail-Journal kann die Übergabe erneut angestoßen werden.",
                refType = REF_TYPE_JOURNAL,
                refId = notice.journalId.toString(),
            )
        }

    companion object {
        const val MISSING_ADDRESS_KEY = "message.center.archiveHandover.addressMissing"
        const val FAILED_KEY = "message.center.archiveHandover.failed"
        const val REF_TYPE_SETTING = SystemMessage.STANDING_REF_TYPE
        const val REF_ID_ADDRESS = "ADDRESS"
        const val REF_TYPE_JOURNAL = "MAIL_JOURNAL"
    }
}
