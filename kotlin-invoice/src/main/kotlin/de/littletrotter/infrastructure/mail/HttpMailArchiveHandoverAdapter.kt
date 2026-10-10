package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverRetryOutcome
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.outbound.MailArchiveHandoverPort
import de.littletrotter.infrastructure.mail.acl.MailArchiveHandoverTranslator
import de.littletrotter.shared.mail.MailArchiveHandoverConfigView
import de.littletrotter.shared.mail.MailArchiveHandoverRetryResult
import de.littletrotter.shared.mail.MailWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * `GET`/`PUT /mail/api/archive-handover` and `POST /mail/api/journal/{id}/retry-archive-handover`.
 *
 * The retry is not repeated on a transport failure: the hand-over mails an archive address and a
 * lost answer would otherwise deliver a second copy; the user sees the error and checks the journal.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailArchiveHandoverAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailArchiveHandoverPort {

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun settings(): ArchiveHandoverSettings =
        MailArchiveHandoverTranslator.toSettings(
            apiClient.get(SETTINGS_PATH, emptyList(), "archive hand-over setting", object : TypeReference<MailArchiveHandoverConfigView>() {}),
        )

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun save(update: ArchiveHandoverSettingsUpdate): ArchiveHandoverSettings =
        MailArchiveHandoverTranslator.toSettings(
            apiClient.put(
                SETTINGS_PATH,
                MailWireCodec.encode(MailArchiveHandoverTranslator.toUpdate(update)),
                "archive hand-over setting",
                object : TypeReference<MailArchiveHandoverConfigView>() {},
            ),
        )

    @Timeout(value = 60_000, unit = ChronoUnit.MILLIS)
    override fun retry(journalId: Long): ArchiveHandoverRetryOutcome =
        MailArchiveHandoverTranslator.toRetryOutcome(
            apiClient.post(
                "/journal/$journalId/retry-archive-handover",
                null,
                "archive hand-over retry",
                object : TypeReference<MailArchiveHandoverRetryResult>() {},
            ),
        )

    private companion object {
        const val SETTINGS_PATH = "/archive-handover"
    }
}
