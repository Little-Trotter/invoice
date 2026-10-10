package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.port.inbound.ArchiveHandoverRetryOutcome
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.shared.mail.MailArchiveHandoverConfigUpdate
import de.littletrotter.shared.mail.MailArchiveHandoverConfigView
import de.littletrotter.shared.mail.MailArchiveHandoverRetryResult
import de.littletrotter.shared.mail.MailArchiveHandoverStatus

/**
 * Archive hand-over side of the anti-corruption layer: setting and retry answer are translated between the published mail language and this application's own models.
 * Enums are mapped by exhaustive `when` so a constant added on either side fails the build here.
 */
object MailArchiveHandoverTranslator {

    fun toSettings(view: MailArchiveHandoverConfigView): ArchiveHandoverSettings =
        ArchiveHandoverSettings(view.address, view.invoiceEnabled, view.quoteEnabled, view.updatedAt, view.updatedBy)

    fun toUpdate(update: ArchiveHandoverSettingsUpdate): MailArchiveHandoverConfigUpdate =
        MailArchiveHandoverConfigUpdate(update.address?.trim()?.takeIf { it.isNotEmpty() }, update.invoiceEnabled, update.quoteEnabled)

    fun toRetryOutcome(result: MailArchiveHandoverRetryResult): ArchiveHandoverRetryOutcome =
        ArchiveHandoverRetryOutcome(result.journalId, statusFrom(result.archiveHandoverStatus), result.archiveHandoverError)

    internal fun statusFrom(status: MailArchiveHandoverStatus): MailJournalHandoverStatus = when (status) {
        MailArchiveHandoverStatus.NOT_CONFIGURED -> MailJournalHandoverStatus.NOT_CONFIGURED
        MailArchiveHandoverStatus.DISABLED -> MailJournalHandoverStatus.DISABLED
        MailArchiveHandoverStatus.PENDING -> MailJournalHandoverStatus.PENDING
        MailArchiveHandoverStatus.HANDED_OVER -> MailJournalHandoverStatus.HANDED_OVER
        MailArchiveHandoverStatus.FAILED -> MailJournalHandoverStatus.FAILED
    }
}
