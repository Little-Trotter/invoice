package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailArchiveDownloadQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream
import de.littletrotter.ui.component.ActionIcons

/**
 * The per-row download of the signed archive package. Only a row whose package was built gets a
 * link; the bytes are fetched when the user clicks, so a long journal page never pulls packages
 * it does not need. A package that has vanished since the page was loaded becomes a plain 404
 * with a readable text instead of a broken download request.
 *
 * The helper owns exactly the one query port it uses; the view only supplies translations via [bind].
 */
@Dependent
class MailJournalArchiveDownloadCell @Inject constructor(
    private val archiveQuery: MailArchiveDownloadQueryPort,
) {
    private lateinit var translations: TranslationProvider

    fun bind(translations: TranslationProvider) {
        this.translations = translations
    }

    private val log: Logger = Logger.getLogger(MailJournalArchiveDownloadCell::class.java)

    fun build(entry: MailJournalEntry): Component {
        if (entry.archiveStatus != MailJournalArchiveStatus.ARCHIVED) {
            return Span()
        }
        val label = translations.tr("mail.journal.action.downloadArchive")
        val button = AppButtons.icon(ActionIcons.DOWNLOAD, label) {}.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
        }
        return Anchor(DownloadHandler.fromInputStream { respond(entry.id) }, "").apply {
            element.setAttribute("download", true)
            add(button)
        }
    }

    internal fun respond(journalId: Long): DownloadResponse {
        val archive = try {
            archiveQuery.findArchive(journalId)
        } catch (e: MailDispatchException) {
            log.warnf(e, "Archive package of journalId=%d could not be fetched", journalId)
            null
        }
        return if (archive == null) {
            DownloadResponse.error(HTTP_NOT_FOUND, translations.tr("mail.journal.archive.download.missing"))
        } else {
            DownloadResponse(ByteArrayInputStream(archive.bytes), archive.filename, CONTENT_TYPE, archive.bytes.size.toLong())
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val CONTENT_TYPE = "application/gzip"
    }
}
