package de.littletrotter.application.dto

import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import java.time.OffsetDateTime

/**
 * The message center's filter: an empty [severity] or [source] set means "no restriction on this
 * dimension"; [from]/[to] bound the time range; [unreadOnly] keeps only messages the user has not
 * read yet.
 */
data class MessageCenterFilter(
    val severity: Set<SystemMessageSeverity> = emptySet(),
    val source: Set<SystemMessageSource> = emptySet(),
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val unreadOnly: Boolean = false,
)
