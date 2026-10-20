package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.RearchiveMailUseCase
import de.littletrotter.application.mail.port.inbound.RetryArchiveHandoverUseCase
import de.littletrotter.application.mail.port.inbound.RetryMailUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * The row actions "Erneut senden" (failed rows), "Erneut archivieren" (sent rows whose archive
 * package failed) and "Erneut übergeben" (rows whose hand-over to the archive address failed;
 * administrators only, like the setting itself). All go through a confirm dialog: a retry mails the customer again and an
 * accidental click must not. A row in any other state gets no button at all, so there is nothing
 * disabled to explain.
 *
 * The helper owns exactly the use-case ports it calls, so no port bundle is needed in the view;
 * the view only supplies the presentation context (translations, refresh callback) through [bind].
 */
@Dependent
class MailJournalRowActions @Inject constructor(
    private val retryUseCase: RetryMailUseCase,
    private val rearchiveUseCase: RearchiveMailUseCase,
    private val handoverUseCase: RetryArchiveHandoverUseCase,
    private val roles: AppRoleQueryPort,
) {
    private lateinit var translations: TranslationProvider
    private var onDone: () -> Unit = {}

    fun bind(translations: TranslationProvider, onDone: () -> Unit) {
        this.translations = translations
        this.onDone = onDone
    }

    private fun tr(key: String, vararg params: Any): String = translations.tr(key, *params)

    fun build(entry: MailJournalEntry): Component =
        HorizontalLayout().apply {
            isPadding = false
            isSpacing = true
            if (canRetry(entry)) add(actionButton("mail.journal.action.retry") { confirmRetry(entry) })
            if (canRearchive(entry)) add(actionButton("mail.journal.action.rearchive") { confirmRearchive(entry) })
            if (canRetryHandover(entry) && roles.hasPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)) {
                add(actionButton("mail.journal.action.retryHandover") { confirmRetryHandover(entry) })
            }
        }

    private fun actionButton(labelKey: String, onClick: () -> Unit): Button =
        AppButtons.tertiary(tr(labelKey), null, onClick).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
        }

    private fun confirmRetry(entry: MailJournalEntry) =
        confirm("mail.journal.retry", entry) {
            val outcome = retryUseCase.retry(entry.id)
            UiNotifications.success(tr("mail.journal.retry.done", outcome.journalId))
        }

    private fun confirmRearchive(entry: MailJournalEntry) =
        confirm("mail.journal.rearchive", entry) {
            val outcome = rearchiveUseCase.rearchive(entry.id)
            if (outcome.archiveStatus == MailJournalArchiveStatus.ARCHIVED) {
                UiNotifications.success(tr("mail.journal.rearchive.done"))
            } else {
                UiNotifications.error(tr("mail.journal.rearchive.failed", outcome.archiveError ?: "-"))
            }
        }

    private fun confirmRetryHandover(entry: MailJournalEntry) =
        confirm("mail.journal.retryHandover", entry) {
            val outcome = handoverUseCase.retryArchiveHandover(entry.id)
            if (outcome.status == MailJournalHandoverStatus.HANDED_OVER) {
                UiNotifications.success(tr("mail.journal.retryHandover.done"))
            } else {
                UiNotifications.error(tr("mail.journal.retryHandover.failed", outcome.error ?: "-"))
            }
        }

    private fun confirm(keyPrefix: String, entry: MailJournalEntry, action: () -> Unit) {
        ConfirmDialog(
            tr("$keyPrefix.title"),
            tr("$keyPrefix.text", entry.referenceKey, entry.recipient),
            tr("$keyPrefix.confirm"),
        ) {
            // The component's own message is what the user can act on ("only a failed row can be
            // repeated"); the journal is refreshed either way because a refusal usually means the
            // row changed under the user.
            try {
                action()
            } catch (e: MailDispatchException) {
                UiNotifications.error(tr("mail.journal.action.error", e.message.orEmpty()))
            }
            onDone()
        }.also {
            it.setCancelable(true)
            it.setCancelText(tr("app.cancel"))
            it.open()
        }
    }

    companion object {
        fun canRetry(entry: MailJournalEntry): Boolean = entry.status == MailJournalStatus.FAILED

        fun canRearchive(entry: MailJournalEntry): Boolean =
            entry.status == MailJournalStatus.SENT && entry.archiveStatus == MailJournalArchiveStatus.FAILED

        fun canRetryHandover(entry: MailJournalEntry): Boolean =
            entry.status == MailJournalStatus.SENT && entry.archiveHandoverStatus == MailJournalHandoverStatus.FAILED
    }
}
