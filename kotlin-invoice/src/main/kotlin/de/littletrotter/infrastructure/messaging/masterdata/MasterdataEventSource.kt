package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.reference.TaxRate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/**
 * Loads a master data aggregate by id and turns it into its upsert event; shared by the change
 * recorder (current state at commit time) and the snapshot (current state of the whole inventory),
 * so a replica sees the same event shape on both paths.
 */
@ApplicationScoped
class MasterdataEventSource @Inject constructor(
    private val em: EntityManager,
    private val mapper: MasterdataEventMapper,
) {

    /** The upsert event of aggregate [id], or `null` when it no longer exists. Needs an open session. */
    fun upserted(aggregate: MasterdataAggregate, id: Long): Any? = when (aggregate) {
        MasterdataAggregate.CUSTOMER -> em.find(Recipient::class.java, id)?.let(::customer)
        MasterdataAggregate.SELLER -> em.find(SenderProfile::class.java, id)?.let(::seller)
        MasterdataAggregate.ARTICLE -> em.find(Article::class.java, id)?.let(::article)
        MasterdataAggregate.TAX_RATE -> em.find(TaxRate::class.java, id)?.let(mapper::taxRate)
        MasterdataAggregate.PAYMENT_TERM -> em.find(PaymentTerm::class.java, id)?.let(mapper::paymentTerm)
        MasterdataAggregate.B2B_PROFILE -> em.find(B2bProfile::class.java, id)?.let(mapper::b2bProfile)
        MasterdataAggregate.NUMBER_RANGE -> em.find(NumberRange::class.java, id)
            ?.takeIf { it.subject == NumberRangeSubject.QUOTE }?.let(mapper::numberRange)
    }

    /**
     * Up to [limit] upsert events of [aggregate] with an id above [afterId], in id order, each with
     * its id; one short read transaction per page, so a snapshot never holds a transaction open
     * while it publishes.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun page(aggregate: MasterdataAggregate, afterId: Long, limit: Int): List<Pair<Long, Any>> {
        val ids = idsAfter(aggregate, afterId, limit)
        return ids.mapNotNull { id -> upserted(aggregate, id)?.let { id to it } }
    }

    private fun idsAfter(aggregate: MasterdataAggregate, afterId: Long, limit: Int): List<Long> {
        val cb = em.criteriaBuilder
        val query = cb.createQuery(Long::class.javaObjectType)
        val root = query.from(entityClass(aggregate))
        val id = root.get<Long>("id")
        val after = cb.greaterThan(id, afterId)
        // Only quote ranges are replicated; filtering here keeps pages full, so the page loop's end test stays valid.
        val restriction = if (aggregate == MasterdataAggregate.NUMBER_RANGE) {
            cb.and(after, cb.equal(root.get<NumberRangeSubject>("subject"), NumberRangeSubject.QUOTE))
        } else {
            after
        }
        query.select(id).where(restriction).orderBy(cb.asc(id))
        return em.createQuery(query).setMaxResults(limit).resultList.toList()
    }

    private fun entityClass(aggregate: MasterdataAggregate): Class<*> = when (aggregate) {
        MasterdataAggregate.CUSTOMER -> Recipient::class.java
        MasterdataAggregate.SELLER -> SenderProfile::class.java
        MasterdataAggregate.ARTICLE -> Article::class.java
        MasterdataAggregate.TAX_RATE -> TaxRate::class.java
        MasterdataAggregate.PAYMENT_TERM -> PaymentTerm::class.java
        MasterdataAggregate.B2B_PROFILE -> B2bProfile::class.java
        MasterdataAggregate.NUMBER_RANGE -> NumberRange::class.java
    }

    /**
     * Raises the version of every article that carries a customer-specific reference for
     * [recipientId] and returns their ids. The database removes those references together with the
     * customer, so no article write happens; without a new version a replica would keep the
     * stale reference.
     */
    fun touchArticlesReferencing(recipientId: Long): List<Long> {
        val ids = em.createQuery(
            "SELECT DISTINCT r.article.id FROM ArticleRecipientRef r WHERE r.recipient.id = :recipientId",
            Long::class.javaObjectType,
        ).setParameter("recipientId", recipientId).resultList.toList()
        ids.forEach { id -> em.find(Article::class.java, id)?.let { em.lock(it, LockModeType.OPTIMISTIC_FORCE_INCREMENT) } }
        return ids
    }

    private fun article(article: Article): Any {
        val refs = em.createQuery(
            "SELECT r FROM ArticleRecipientRef r WHERE r.article.id = :articleId ORDER BY r.recipient.id",
            ArticleRecipientRef::class.java,
        ).setParameter("articleId", article.id).resultList.toList()
        return mapper.article(article, refs)
    }

    private fun seller(profile: SenderProfile): Any {
        val addresses = em.createQuery(
            "SELECT a FROM Address a WHERE a.senderProfile.id = :ownerId ORDER BY a.id",
            Address::class.java,
        ).setParameter("ownerId", profile.id).resultList.toList()
        return mapper.seller(profile, addresses)
    }

    private fun customer(recipient: Recipient): Any {
        val addresses = em.createQuery(
            "SELECT a FROM Address a WHERE a.recipient.id = :ownerId ORDER BY a.id",
            Address::class.java,
        ).setParameter("ownerId", recipient.id).resultList.toList()
        val values = em.createQuery(
            "SELECT v FROM RecipientB2bFieldValue v WHERE v.recipientId = :recipientId ORDER BY v.profileFieldId",
            RecipientB2bFieldValue::class.java,
        ).setParameter("recipientId", recipient.id).resultList.toList()
        val terms = values.associate { it.profileFieldId to em.find(B2bProfileField::class.java, it.profileFieldId)?.term }
        return mapper.customer(recipient, addresses, values, terms)
    }
}
