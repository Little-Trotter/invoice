package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.mail.ArchiveHandoverNotice
import de.littletrotter.application.mail.ArchiveHandoverNoticeReason
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeEvent
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeReason

/**
 * Archive hand-over notice side of the anti-corruption layer: the administrator notice arrives on
 * the message bus in the published mail language and is translated into this application's own
 * model here, next to the consumer that receives it. The reason is mapped by exhaustive `when` so a
 * constant added on either side fails the build here.
 */
object MailArchiveHandoverNoticeTranslator {

    fun toNotice(event: MailArchiveHandoverNoticeEvent): ArchiveHandoverNotice =
        ArchiveHandoverNotice(event.journalId, event.referenceKey, reasonFrom(event.reason), event.error, event.occurredAt)

    private fun reasonFrom(reason: MailArchiveHandoverNoticeReason): ArchiveHandoverNoticeReason = when (reason) {
        MailArchiveHandoverNoticeReason.ADDRESS_MISSING -> ArchiveHandoverNoticeReason.ADDRESS_MISSING
        MailArchiveHandoverNoticeReason.HANDOVER_FAILED -> ArchiveHandoverNoticeReason.HANDOVER_FAILED
    }
}
