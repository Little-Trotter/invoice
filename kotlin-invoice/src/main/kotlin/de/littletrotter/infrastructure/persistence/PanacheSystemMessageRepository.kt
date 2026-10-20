package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.message.SystemMessageReceipt
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.UnreadWindow
import de.littletrotter.domain.repository.SystemMessageRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.TypedQuery
import jakarta.transaction.Transactional
import java.time.OffsetDateTime

/**
 * [SystemMessageRepository] backed by JPA/Panache-style JPQL, same hand-built
 * `StringBuilder` filter approach as [PanacheRecipientRepository.findAll]: only the predicates a
 * given call actually needs get appended, so an unfiltered `find` stays a plain scan instead of a
 * query full of always-true clauses.
 *
 * "Visible to userId" (`m.recipientUserId IS NULL OR m.recipientUserId = :userId`) is always the
 * first predicate — every read here is scoped to one user, per [SystemMessageRepository]'s KDoc.
 */
@ApplicationScoped
class PanacheSystemMessageRepository : BasePanacheRepository(), SystemMessageRepository {

    private companion object {
        private const val PARAM_USER_ID = "userId"
        private const val PARAM_ERROR_SEVERITY = "errorSeverity"
        private const val PARAM_ERROR_CUTOFF = "errorCutoff"
        private const val PARAM_CUTOFF = "cutoff"
        private const val VISIBLE_PREDICATE = "(m.recipientUserId IS NULL OR m.recipientUserId = :userId)"
        private const val REFERENCE_PREDICATE =
            "m.messageKey = :messageKey AND m.refType = :refType AND m.refId = :refId"
        private const val UNREAD_PREDICATE =
            "NOT EXISTS (SELECT r FROM SystemMessageReceipt r WHERE r.messageId = m.id AND r.userId = :userId) AND " +
                "((m.severity = :errorSeverity AND m.occurredAt >= :errorCutoff) OR " +
                "(m.severity <> :errorSeverity AND m.occurredAt >= :cutoff))"
    }

    override fun save(message: SystemMessage): SystemMessage = saveEntity(message) { message.id }

    override fun findById(id: Long): SystemMessage? = findByIdInternal(id, SystemMessage::class.java)

    @Transactional
    override fun find(userId: String, filter: SystemMessageFilter, pageIndex: Int, pageSize: Int): List<SystemMessage> {
        val (jpql, params) = filterQuery("SELECT m FROM SystemMessage m", userId, filter)
        val q = em.createQuery("$jpql ORDER BY m.occurredAt DESC, m.id DESC", SystemMessage::class.java)
        params.forEach { (name, value) -> q.setParameter(name, value) }
        return q.setFirstResult(pageIndex * pageSize).setMaxResults(pageSize).resultList
    }

    override fun count(userId: String, filter: SystemMessageFilter): Long {
        val (jpql, params) = filterQuery("SELECT COUNT(m) FROM SystemMessage m", userId, filter)
        val q = em.createQuery(jpql, Long::class.java)
        params.forEach { (name, value) -> q.setParameter(name, value) }
        return q.singleResult
    }

    override fun unreadCountFor(userId: String, window: UnreadWindow): Long =
        em.createQuery(
            "SELECT COUNT(m) FROM SystemMessage m WHERE $VISIBLE_PREDICATE AND $UNREAD_PREDICATE",
            Long::class.java,
        ).setParameter(PARAM_USER_ID, userId).withWindow(window).singleResult

    override fun unreadMessageIdsFor(userId: String, window: UnreadWindow): List<Long> =
        em.createQuery(
            "SELECT m.id FROM SystemMessage m WHERE $VISIBLE_PREDICATE AND $UNREAD_PREDICATE",
            Long::class.java,
        ).setParameter(PARAM_USER_ID, userId).withWindow(window).resultList

    private fun <T> TypedQuery<T>.withWindow(window: UnreadWindow): TypedQuery<T> =
        setParameter(PARAM_ERROR_SEVERITY, SystemMessageSeverity.ERROR)
            .setParameter(PARAM_ERROR_CUTOFF, window.errorCutoff)
            .setParameter(PARAM_CUTOFF, window.cutoff)

    override fun findReceipt(messageId: Long, userId: String): SystemMessageReceipt? =
        em.createQuery(
            "SELECT r FROM SystemMessageReceipt r WHERE r.messageId = :messageId AND r.userId = :userId",
            SystemMessageReceipt::class.java,
        ).setParameter("messageId", messageId).setParameter(PARAM_USER_ID, userId).resultList.firstOrNull()

    // A plain persist would let a double click or a second browser tab race past the exists-check
    // and fail on the unique index; the database arbitrates instead and the first read_at wins.
    // The returned entity is the persisted row, not necessarily the instance passed in.
    override fun saveReceipt(receipt: SystemMessageReceipt): SystemMessageReceipt {
        receipt.validate()
        em.createNativeQuery(
            "INSERT INTO system_message_receipt (message_id, user_id, read_at) " +
                "VALUES (:messageId, :userId, :readAt) ON CONFLICT (message_id, user_id) DO NOTHING",
        ).setParameter("messageId", receipt.messageId)
            .setParameter(PARAM_USER_ID, receipt.userId)
            .setParameter("readAt", receipt.readAt)
            .executeUpdate()
        return checkNotNull(findReceipt(receipt.messageId, receipt.userId)) {
            "Receipt for message ${receipt.messageId} vanished right after insert."
        }
    }

    override fun deleteOlderThan(cutoff: OffsetDateTime): Int =
        em.createQuery("DELETE FROM SystemMessage m WHERE m.occurredAt < :cutoff AND (m.refType IS NULL OR m.refType <> :standing)")
            .setParameter(PARAM_CUTOFF, cutoff)
            .setParameter("standing", SystemMessage.STANDING_REF_TYPE)
            .executeUpdate()

    override fun existsByReference(messageKey: String, refType: String, refId: String): Boolean =
        em.createQuery(
            "SELECT COUNT(m) FROM SystemMessage m WHERE $REFERENCE_PREDICATE",
            Long::class.java,
        ).bindReference(messageKey, refType, refId).singleResult > 0

    override fun deleteByReference(messageKey: String, refType: String, refId: String): Int =
        em.createQuery("DELETE FROM SystemMessage m WHERE $REFERENCE_PREDICATE")
            .setParameter("messageKey", messageKey)
            .setParameter("refType", refType)
            .setParameter("refId", refId)
            .executeUpdate()

    private fun <T> TypedQuery<T>.bindReference(messageKey: String, refType: String, refId: String): TypedQuery<T> =
        setParameter("messageKey", messageKey).setParameter("refType", refType).setParameter("refId", refId)

    private fun filterQuery(select: String, userId: String, filter: SystemMessageFilter): Pair<String, Map<String, Any>> {
        val predicates = mutableListOf(VISIBLE_PREDICATE)
        val params = mutableMapOf<String, Any>(PARAM_USER_ID to userId)
        // Multi-select severity/source filters: an empty set means "no restriction" (same
        // as the old null), a non-empty set becomes a single IN predicate rather than one query
        // per selected value.
        if (filter.severity.isNotEmpty()) { predicates += "m.severity IN :severity"; params["severity"] = filter.severity }
        if (filter.source.isNotEmpty()) { predicates += "m.source IN :source"; params["source"] = filter.source }
        filter.from?.let { predicates += "m.occurredAt >= :from"; params["from"] = it }
        filter.to?.let { predicates += "m.occurredAt <= :to"; params["to"] = it }
        if (filter.unreadOnly) {
            val window = requireNotNull(filter.unreadWindow) { "unreadOnly needs an unreadWindow" }
            predicates += UNREAD_PREDICATE
            params[PARAM_ERROR_SEVERITY] = SystemMessageSeverity.ERROR
            params[PARAM_ERROR_CUTOFF] = window.errorCutoff
            params[PARAM_CUTOFF] = window.cutoff
        }
        return "$select WHERE ${predicates.joinToString(" AND ")}" to params
    }
}
