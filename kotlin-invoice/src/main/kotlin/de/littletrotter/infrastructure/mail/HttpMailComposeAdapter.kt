package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.MailNotFoundException
import de.littletrotter.application.mail.port.inbound.MailComposeDraft
import de.littletrotter.application.mail.port.inbound.MailComposeDraftQueryPort
import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailRenderPreview
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewRequest
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.infrastructure.mail.acl.MailComposeTranslator
import de.littletrotter.infrastructure.mail.acl.MailTemplateTranslator
import de.littletrotter.shared.mail.MailDraftView
import de.littletrotter.shared.mail.MailPlaceholderCatalog
import de.littletrotter.shared.mail.MailRenderPreview as ContractRenderPreview
import de.littletrotter.shared.mail.MailWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.net.URLEncoder
import java.time.temporal.ChronoUnit

/**
 * Compose-view reads against `GET /mail/api/compose/{id}`, `GET /mail/api/placeholders` and
 * `POST /mail/api/render-preview`.
 *
 * All three are idempotent (the preview only renders), so each carries the same short retry and
 * timeout as the other reads. Like every mail adapter it exists only when `mail.enabled` is true.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailComposeAdapter @Inject constructor(
    private val apiClient: MailApiClient,
) : MailComposeDraftQueryPort, MailPlaceholderCatalogQueryPort, MailRenderPreviewQueryPort {

    @Retry(retryOn = [MailDispatchException::class], abortOn = [MailNotFoundException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun findDraft(draftId: String): MailComposeDraft? =
        try {
            MailComposeTranslator.toDraft(
                apiClient.get(
                    "/compose/${URLEncoder.encode(draftId, Charsets.UTF_8)}",
                    emptyList(),
                    "compose draft",
                    object : TypeReference<MailDraftView>() {},
                ),
            )
        } catch (_: MailNotFoundException) {
            null
        }

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun placeholders(kind: MailTemplateKind): List<MailPlaceholderEntry> =
        apiClient.get(
            "/placeholders",
            listOf("kind" to MailTemplateTranslator.kindFor(kind).name),
            "placeholder catalogue",
            object : TypeReference<MailPlaceholderCatalog>() {},
        ).entries.map(MailComposeTranslator::toPlaceholder)

    @Retry(retryOn = [MailDispatchException::class], maxRetries = 2, delay = 300, delayUnit = ChronoUnit.MILLIS)
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun renderPreview(request: MailRenderPreviewRequest): MailRenderPreview =
        MailComposeTranslator.toPreview(
            apiClient.post(
                "/render-preview",
                MailWireCodec.encode(MailComposeTranslator.toRequest(request)),
                "render preview",
                object : TypeReference<ContractRenderPreview>() {},
            ),
        )
}
