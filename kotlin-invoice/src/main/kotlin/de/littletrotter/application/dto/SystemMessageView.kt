package de.littletrotter.application.dto

import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import java.time.OffsetDateTime

/**
 * Read model of one message-center row. [args] is the decoded positional i18n argument list;
 * [resolvedText] the text stored at record time, the fallback when [messageKey] no longer
 * resolves to a translation.
 */
data class SystemMessageView(
    val id: Long?,
    val occurredAt: OffsetDateTime,
    val severity: SystemMessageSeverity,
    val source: SystemMessageSource,
    val messageKey: String,
    val args: List<String>,
    val resolvedText: String,
    val refType: String?,
    val refId: String?,
)
