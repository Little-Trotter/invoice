package de.littletrotter.application.mail.port.inbound

import de.littletrotter.domain.shared.InvoiceFormat
import java.time.Instant

/** An attachment staged with the draft; [s3Key] is what the send request references it by. */
data class MailComposeAttachment(
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val s3Key: String,
)

/**
 * A mail the invoicing application handed over and a user is about to review.
 *
 * Carries no subject or body: those come from the template, rendered against [placeholderValues]
 * by the preview port, so the draft never holds a second copy of the wording.
 */
data class MailComposeDraft(
    val draftId: String,
    val referenceKey: String,
    val kind: MailTemplateKind,
    val documentFamily: InvoiceFormat,
    val recipientAddress: String,
    val recipientName: String,
    val placeholderValues: Map<String, String>,
    val attachments: List<MailComposeAttachment>,
    val totalAttachmentBytes: Long,
    val expiresAt: Instant,
)

/** Kind of value a placeholder stands for; decides how the sample is shown and validated. */
enum class MailPlaceholderType { TEXT, DATE, MONEY }

/** One placeholder a template may use, with an example value for template authoring. */
data class MailPlaceholderEntry(
    val key: String,
    val type: MailPlaceholderType,
    val sampleValue: String,
)

/** Which template to render and the values to fill it with. */
data class MailRenderPreviewRequest(
    val kind: MailTemplateKind,
    val documentFamily: InvoiceFormat,
    val placeholderValues: Map<String, String?>,
)

/** The rendered subject and body plus the template version they came from. */
data class MailRenderPreview(
    val subject: String,
    val body: String,
    val language: MailTemplateLanguage,
    val templateVersion: Int,
)
