package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.outbound.MailTemplateResetPort
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailTemplateView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * `POST /mail/api/templates/{kind}/{language}/reset`. Deliberately **no retry**: a reset appends a
 * version, so a blind second attempt after a lost answer would add a duplicate.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailTemplateResetAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailTemplateResetPort {

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun reset(kind: MailTemplateKind, language: MailTemplateLanguage): MailTemplate =
        MailTemplateTranslator.toTemplate(
            apiClient.post(
                "/templates/${MailTemplateTranslator.pathOf(kind, language)}/reset",
                null,
                "template reset",
                object : TypeReference<MailTemplateView>() {},
            ),
        )
}
