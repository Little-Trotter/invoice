package de.littletrotter.infrastructure.mail

import de.littletrotter.application.invoice.port.outbound.MailJournalAttachment
import de.littletrotter.application.invoice.port.outbound.MailJournalAttachmentRestorePort
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped

/** Used when the mail component is not part of the deployment: nothing is restorable from mail. */
@DefaultBean
@ApplicationScoped
class MailJournalRestoreUnavailable : MailJournalAttachmentRestorePort {
    override fun findAttachment(referenceKey: String, sha256: String): MailJournalAttachment? = null
}
