package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateVersion
import de.littletrotter.application.mail.port.inbound.MailTemplateQueryPort
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailTemplateVersionView
import de.littletrotter.shared.mail.MailTemplateView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * Template reads against `GET /mail/api/templates` and `…/{kind}/{language}/versions`.
 *
 * The component has no single-template read, so [findTemplate] filters the (small, fixed-size)
 * list instead of the host inventing an endpoint the contract does not carry.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailTemplateQueryAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailTemplateQueryPort {

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun listTemplates(): List<MailTemplate> =
        apiClient.get(TEMPLATES_PATH, emptyList(), "template list", object : TypeReference<List<MailTemplateView>>() {})
            .map(MailTemplateTranslator::toTemplate)

    override fun findTemplate(kind: MailTemplateKind, language: MailTemplateLanguage): MailTemplate? =
        listTemplates().firstOrNull { it.kind == kind && it.language == language }

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun listVersions(kind: MailTemplateKind, language: MailTemplateLanguage): List<MailTemplateVersion> =
        apiClient.get(
            "$TEMPLATES_PATH/${MailTemplateTranslator.pathOf(kind, language)}/versions",
            emptyList(),
            "template versions",
            object : TypeReference<List<MailTemplateVersionView>>() {},
        ).map(MailTemplateTranslator::toVersion)

    private companion object {
        const val TEMPLATES_PATH = "/templates"
    }
}
