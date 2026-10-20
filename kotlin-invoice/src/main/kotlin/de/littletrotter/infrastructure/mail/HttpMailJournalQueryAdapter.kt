package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailJournalQueryPort
import de.littletrotter.infrastructure.mail.acl.MailJournalTranslator
import de.littletrotter.shared.mail.MailJournalCount
import de.littletrotter.shared.mail.MailJournalEntryView
import de.littletrotter.shared.mail.MailJournalStatusCounts
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * Journal reads against `GET /mail/api/journal`, `…/count` and `…/count-by-status`.
 *
 * Reads are idempotent, so each carries a short retry before a human waiting on the list sees an
 * error. Like every mail adapter it exists only when `mail.enabled` is true; the journal itself is
 * owned by the component and is never cached here.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailJournalQueryAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailJournalQueryPort {

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun findPage(criteria: MailJournalCriteria): List<MailJournalEntry> =
        apiClient.get(
            JOURNAL_PATH,
            MailJournalTranslator.toParameters(MailJournalTranslator.toQuery(criteria), withWindow = true),
            "journal page",
            object : TypeReference<List<MailJournalEntryView>>() {},
        ).map(MailJournalTranslator::toEntry)

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun count(criteria: MailJournalCriteria): Long =
        apiClient.get(
            "$JOURNAL_PATH/count",
            MailJournalTranslator.toParameters(MailJournalTranslator.toQuery(criteria), withWindow = false),
            "journal count",
            object : TypeReference<MailJournalCount>() {},
        ).count

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun countByStatus(criteria: MailJournalCriteria): Map<MailJournalStatus, Long> =
        MailJournalTranslator.toCounts(
            apiClient.get(
                "$JOURNAL_PATH/count-by-status",
                MailJournalTranslator.toParameters(MailJournalTranslator.toQuery(criteria), withWindow = false),
                "journal status counts",
                object : TypeReference<MailJournalStatusCounts>() {},
            ).counts,
        )

    private companion object {
        const val JOURNAL_PATH = "/journal"
    }
}
