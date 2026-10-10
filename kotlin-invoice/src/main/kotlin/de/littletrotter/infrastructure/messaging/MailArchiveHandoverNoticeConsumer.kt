package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.mail.ArchiveHandoverNoticeProjection
import de.littletrotter.infrastructure.messaging.acl.MailArchiveHandoverNoticeTranslator
import de.littletrotter.shared.mail.MailArchiveHandoverNoticeEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The business half of the `mail.archive-handover.notice` subscription: decodes one envelope and
 * hands it to [ArchiveHandoverNoticeProjection]. A payload that violates the contract propagates
 * as `MailContractViolationException` so the adapter terminates it instead of acknowledging a
 * notice nobody could read.
 */
@ApplicationScoped
class MailArchiveHandoverNoticeConsumer @Inject constructor(
    private val projection: ArchiveHandoverNoticeProjection,
) {

    private val log = Logger.getLogger(MailArchiveHandoverNoticeConsumer::class.java)

    /** Decodes [raw] and projects it; `true` when it created a new message-center entry. */
    fun consume(raw: String): Boolean {
        val notice = MailArchiveHandoverNoticeTranslator.toNotice(MailArchiveHandoverNoticeEvent.decode(raw))
        val created = projection.project(notice)
        log.debugf("Archive hand-over notice %s (journal %d): %s", notice.reason, notice.journalId, if (created) "recorded" else "already shown")
        return created
    }
}
