package de.littletrotter.domain.message

import java.time.OffsetDateTime

/**
 * The message center's query dimensions (severity/source/time range/unread-only),
 * same shape as [de.littletrotter.application.dto.StatisticsFilter]: a parameter object
 * instead of a growing positional-argument list on
 * [de.littletrotter.application.message.MessageCenterService.find]/
 * [de.littletrotter.application.message.MessageCenterService.count]. User-visibility scope is not
 * part of this filter — that's the `userId` parameter both methods take alongside it.
 *
 * S107 — lives in the domain, next to [SystemMessage], because the
 * [de.littletrotter.domain.repository.SystemMessageRepository] port takes it as its query
 * parameter object too: one filter from the view down to the JPQL, no field-by-field re-spread.
 *
 * [severity]/[source] are sets rather than single values so the message-center view's
 * multi-select filters (Schweregrad, Quelle) translate into one query with an `IN` predicate
 * instead of N separate lazy-loaded pages merged client-side; an empty set means "no restriction
 * on this dimension" (same meaning `null` had before this became a set).
 */
data class SystemMessageFilter(
    val severity: Set<SystemMessageSeverity> = emptySet(),
    val source: Set<SystemMessageSource> = emptySet(),
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val unreadOnly: Boolean = false,
    /** Required by the repository whenever [unreadOnly] is set; the service supplies it. */
    val unreadWindow: UnreadWindow? = null,
) {
    companion object {
        val EMPTY = SystemMessageFilter()
    }
}
