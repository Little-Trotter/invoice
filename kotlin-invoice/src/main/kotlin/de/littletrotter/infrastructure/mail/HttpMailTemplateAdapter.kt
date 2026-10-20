package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateDraft
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTestToMeResult
import de.littletrotter.application.mail.port.outbound.MailCallerProvider
import de.littletrotter.application.mail.port.outbound.MailTemplateWritePort
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailTemplateView
import de.littletrotter.shared.mail.MailTestSendResult
import de.littletrotter.shared.mail.MailWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * Template writes against `POST /mail/api/templates`, `…/versions/{v}/restore` and `…/test-to-me`.
 *
 * Deliberately **no retry**: saving and restoring each append a version, and a test mail is a real
 * delivery, so a blind second attempt after a lost answer would duplicate it. The caller sees the
 * failure and decides.
 *
 * "Test to me" refuses up front when the identity provider released no e-mail claim: the component
 * would reject the missing header anyway, but a message naming the cause is the one a person can act on.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailTemplateAdapter @Inject constructor(
    private val apiClient: MailApiClient,
    private val callerProvider: MailCallerProvider,
) : MailTemplateWritePort {

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun save(draft: MailTemplateDraft): MailTemplate =
        MailTemplateTranslator.toTemplate(
            apiClient.post(
                TEMPLATES_PATH,
                MailWireCodec.encode(MailTemplateTranslator.toUpdate(draft)),
                "template save",
                object : TypeReference<MailTemplateView>() {},
            ),
        )

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun restoreVersion(kind: MailTemplateKind, language: MailTemplateLanguage, version: Int): MailTemplate =
        MailTemplateTranslator.toTemplate(
            apiClient.post(
                "$TEMPLATES_PATH/${MailTemplateTranslator.pathOf(kind, language)}/versions/$version/restore",
                null,
                "template restore",
                object : TypeReference<MailTemplateView>() {},
            ),
        )

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun sendTestToMe(kind: MailTemplateKind, language: MailTemplateLanguage): MailTestToMeResult {
        if (callerProvider.currentCaller().email == null) {
            throw MailDispatchException("Your login carries no e-mail address, so the test mail has no recipient")
        }
        return MailTemplateTranslator.toTestToMeResult(
            apiClient.post(
                "$TEMPLATES_PATH/${MailTemplateTranslator.pathOf(kind, language)}/test-to-me",
                null,
                "template test mail",
                object : TypeReference<MailTestSendResult>() {},
            ),
        )
    }

    private companion object {
        const val TEMPLATES_PATH = "/templates"
    }
}
