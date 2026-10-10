package de.littletrotter.infrastructure.mail

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.mail.InvoiceMailDraft
import de.littletrotter.application.mail.MailComposeHandover
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.MailDispatchPort
import de.littletrotter.infrastructure.mail.acl.MailComposeRequestTranslator
import de.littletrotter.shared.mail.MailComposeRequest
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/**
 * The compose handover into the mail-dispatch context: `POST {MAIL_BASE_URL}/mail/api/compose`.
 *
 * Transport, authentication headers and error mapping live in [MailApiClient]; this class only
 * produces the body with the contract's own encoder ([MailComposeRequest.encode]) — never Jackson
 * reflection over the data class, so a Kotlin property rename in either repository cannot silently
 * rename a field the other one parses — and reads the two-field answer.
 *
 * **Registered only when the feature is on.** `@LookupIfProperty(name = "mail.enabled",
 * stringValue = "true")` means that with `MAIL_ENABLED=false` this class is not a bean at all;
 * [de.littletrotter.application.mail.InvoiceMailDispatchService] resolves the port through an
 * [jakarta.enterprise.inject.Instance] and finds nothing, so the UI never builds an entry point.
 * That is a runtime switch, not a build-time one: the host image is identical for instances with
 * and without the component, so an `@IfBuildProperty` would bake the decision into the wrong
 * artefact.
 *
 * **Failures throw, they never degrade.** A failed compose handover has no useful fallback: a
 * human pressed a button and is waiting for the next screen. Every failure becomes a
 * [MailDispatchException] carrying a message the UI shows as-is.
 *
 * [compose] carries `@Retry`/`@Timeout`: a couple of fast retries cover a transient connection
 * hiccup before the waiting human sees an error, and the timeout is a hard backstop above the
 * request's own timeouts in case the JDK client ever hangs past them (e.g. an unresponsive DNS
 * lookup). Retrying re-sends the same compose request; the component treats it as a new draft
 * either way, which is acceptable since a human is waiting and would otherwise click again.
 */
@LookupIfProperty(name = "mail.enabled", stringValue = "true")
@ApplicationScoped
class HttpMailDispatchAdapter @Inject constructor(
    private val apiClient: MailApiClient,
    private val objectMapper: ObjectMapper,
) : MailDispatchPort {

    @Retry(
        retryOn = [MailDispatchException::class],
        maxRetries = 2,
        delay = 500,
        delayUnit = ChronoUnit.MILLIS,
        jitter = 200,
        jitterDelayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun compose(draft: InvoiceMailDraft): MailComposeHandover {
        val request = MailComposeRequestTranslator.toComposeRequest(draft)
        return decode(apiClient.postRaw(COMPOSE_PATH, MailComposeRequest.encode(request)))
    }

    /**
     * Reads `{"draftId": "...", "composeUrl": "..."}`.
     *
     * Both fields are required and blank is rejected: a handover that decoded into an empty URL
     * would open an empty view and look like a component bug rather than a contract violation —
     * the same "nothing that can leave the building may default to an empty string" rule the mail
     * contract states for its own payloads.
     */
    private fun decode(body: String): MailComposeHandover {
        val node = try {
            objectMapper.readTree(body)
        } catch (e: JacksonException) {
            throw MailDispatchException("The email component's compose answer is not readable JSON", e)
        }
        val draftId = node.path(FIELD_DRAFT_ID).asText("")
        val url = node.path(FIELD_URL).asText("")
        if (draftId.isBlank() || url.isBlank()) {
            throw MailDispatchException("The email component's compose answer carries no draft id or compose URL")
        }
        return MailComposeHandover(draftId = draftId, composeUrl = url)
    }

    private companion object {
        const val COMPOSE_PATH = "/compose"
        const val FIELD_DRAFT_ID = "draftId"
        const val FIELD_URL = "composeUrl"
    }
}
