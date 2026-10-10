package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.ArchiveHandoverRetryOutcome
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsQueryPort
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.RetryArchiveHandoverUseCase
import de.littletrotter.application.mail.port.inbound.UpdateArchiveHandoverSettingsUseCase
import de.littletrotter.application.mail.port.outbound.MailArchiveHandoverPort
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger

private fun Instance<MailArchiveHandoverPort>.requirePort(): MailArchiveHandoverPort =
    if (isResolvable) get() else throw MailDispatchException(MailNotAvailable.MESSAGE)

/**
 * Reads the archive hand-over setting. Administrators only: the component refuses everyone else,
 * so the check here keeps a refusal from surfacing as a transport error. Resolved through
 * [Instance] because the adapter does not exist when the component is not deployed.
 */
@ApplicationScoped
class ArchiveHandoverSettingsQueryService @Inject constructor(
    private val handoverPort: Instance<MailArchiveHandoverPort>,
) : ArchiveHandoverSettingsQueryPort {

    @RequiresPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)
    override fun settings(): ArchiveHandoverSettings = handoverPort.requirePort().settings()
}

/** Saves the archive hand-over setting and withdraws the standing "address missing" notice once an address exists. */
@ApplicationScoped
class UpdateArchiveHandoverSettingsService @Inject constructor(
    private val handoverPort: Instance<MailArchiveHandoverPort>,
    private val messageCenter: MessageCenterService,
) : UpdateArchiveHandoverSettingsUseCase {

    private val log = Logger.getLogger(UpdateArchiveHandoverSettingsService::class.java)

    @RequiresPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)
    override fun update(update: ArchiveHandoverSettingsUpdate): ArchiveHandoverSettings {
        val stored = handoverPort.requirePort().save(update)
        if (!stored.address.isNullOrBlank()) withdrawMissingAddressNotice()
        return stored
    }

    // The setting is already saved at this point; a message-center hiccup must not turn that
    // success into an error dialog, and the stale notice is harmless until the next save.
    private fun withdrawMissingAddressNotice() {
        runCatching {
            messageCenter.withdraw(
                ArchiveHandoverNoticeProjection.MISSING_ADDRESS_KEY,
                ArchiveHandoverNoticeProjection.REF_TYPE_SETTING,
                ArchiveHandoverNoticeProjection.REF_ID_ADDRESS,
            )
        }.onFailure { log.warn("Could not withdraw the missing-archive-address notice", it) }
    }
}

/** Repeats the archive hand-over of one sent mail through the mail component. */
@ApplicationScoped
class RetryArchiveHandoverService @Inject constructor(
    private val handoverPort: Instance<MailArchiveHandoverPort>,
) : RetryArchiveHandoverUseCase {

    @RequiresPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)
    override fun retryArchiveHandover(journalId: Long): ArchiveHandoverRetryOutcome =
        handoverPort.requirePort().retry(journalId)
}
