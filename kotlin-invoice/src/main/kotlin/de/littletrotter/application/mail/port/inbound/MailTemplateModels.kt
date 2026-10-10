package de.littletrotter.application.mail.port.inbound

import java.time.Instant

/** The current version of one template, as the template list and editor show it. */
data class MailTemplate(
    val id: Long,
    val kind: MailTemplateKind,
    val language: MailTemplateLanguage,
    val version: Int,
    val subject: String,
    val body: String,
    val bccAddress: String?,
    val seededDefault: Boolean,
    val createdAt: Instant,
)

/** One stored version in a template's history; [current] marks the version in use. */
data class MailTemplateVersion(
    val kind: MailTemplateKind,
    val language: MailTemplateLanguage,
    val version: Int,
    val subject: String,
    val body: String,
    val bccAddress: String?,
    val seededDefault: Boolean,
    val createdAt: Instant,
    val current: Boolean,
)

/** New content for a template; saving always appends a version, history is never rewritten. */
data class MailTemplateDraft(
    val kind: MailTemplateKind,
    val language: MailTemplateLanguage,
    val subject: String,
    val body: String,
    val bccAddress: String? = null,
)

/** Where the "test to me" mail went; the recipient is the acting user, never an input. */
data class MailTestToMeResult(val recipientAddress: String, val messageId: String, val sentAt: Instant)

/** Where the "test to BCC" mail went; the address is the one the author is about to save. */
data class MailBccTestResult(val bccAddress: String, val messageId: String, val sentAt: Instant)
