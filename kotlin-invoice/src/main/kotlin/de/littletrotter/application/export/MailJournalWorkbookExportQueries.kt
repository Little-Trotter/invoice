package de.littletrotter.application.export

import de.littletrotter.application.export.port.inbound.MailJournalWorkbookExportQueryPort
import de.littletrotter.application.export.port.outbound.MailJournalWorkbookExportPort
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.OutputStream

/** Implements [MailJournalWorkbookExportQueryPort] on top of the workbook adapter. */
@ApplicationScoped
class MailJournalWorkbookExportQueries @Inject constructor(
    private val export: MailJournalWorkbookExportPort,
) : MailJournalWorkbookExportQueryPort {
    override fun writeWorkbook(criteria: MailJournalCriteria, translate: (String) -> String, output: OutputStream) =
        export.writeWorkbook(criteria, translate, output)
}
