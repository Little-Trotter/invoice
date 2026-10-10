package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.port.inbound.MailBccTestResult
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.outbound.MailTemplateBccTestPort
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailTemplateBccTestResult
import de.littletrotter.shared.mail.MailWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * `POST /mail/api/templates/{kind}/{language}/bcc/test`. Deliberately **no retry**: a test mail is
 * a real delivery, so a blind second attempt after a lost answer would send it twice.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailTemplateBccTestAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailTemplateBccTestPort {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun sendBccTest(kind: MailTemplateKind, language: MailTemplateLanguage, address: String): MailBccTestResult =
        MailTemplateTranslator.toBccTestResult(
            apiClient.post(
                "/templates/${MailTemplateTranslator.pathOf(kind, language)}/bcc/test",
                MailWireCodec.encode(MailTemplateTranslator.toBccTestRequest(address)),
                "template bcc test",
                object : TypeReference<MailTemplateBccTestResult>() {},
            ),
        )
}
