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
import de.littletrotter.domain.repository.MasterdataChangeListener
import de.littletrotter.infrastructure.messaging.OutboxAppended
import de.littletrotter.infrastructure.messaging.OutboxRepository
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Turns every master data write into exactly one outbox event per aggregate and transaction.
 *
 * The persistence adapters report each saved or removed entity ([saved], [deleting]); a child
 * (address, customer-side B2B value, profile field, customer-specific article reference) counts as a change of its owner. The changes
 * of one transaction are collected, and just before the commit the final state of every touched
 * aggregate is read and appended to the outbox in that same transaction, so event and mutation
 * commit or roll back together and several writes to one aggregate yield a single event. Doing it
 * here, behind the repositories, covers every path that writes master data, not only the UI.
 *
 * Without the offer module (`quote.enabled=false`) nothing is recorded.
 */
@ApplicationScoped
class MasterdataChangeRecorder @Inject constructor(
    private val registry: TransactionSynchronizationRegistry,
    private val source: MasterdataEventSource,
    private val mapper: MasterdataEventMapper,
    private val outbox: OutboxRepository,
    private val appended: Event<OutboxAppended>,
    @param:ConfigProperty(name = "quote.enabled", defaultValue = "false")
    private val enabled: Boolean,
) : MasterdataChangeListener {

    private data class Key(val aggregate: MasterdataAggregate, val id: Long)

    override fun saved(entity: Any) {
        if (enabled) resolve(entity)?.let { (aggregate, id) -> record(Key(aggregate, id), deleted = false) }
    }

    override fun deleting(entity: Any) {
        if (!enabled) return
        val (aggregate, id) = resolve(entity) ?: return
        // The database cascades a removed customer into its article references; the owning
        // articles change without any article write, so they are republished here.
        if (entity is Recipient) source.touchArticlesReferencing(id).forEach { record(Key(MasterdataAggregate.ARTICLE, it), deleted = false) }
        // A removed child changes its owner; only a removed root aggregate disappears.
        record(Key(aggregate, id), deleted = isRoot(entity))
    }

    private fun isRoot(entity: Any) = entity !is Address && entity !is RecipientB2bFieldValue && entity !is B2bProfileField && entity !is ArticleRecipientRef

    private fun resolve(entity: Any): Pair<MasterdataAggregate, Long>? = when (entity) {
        is Recipient -> pair(MasterdataAggregate.CUSTOMER, entity.id)
        is Address -> entity.recipient?.let { pair(MasterdataAggregate.CUSTOMER, it.id) }
            ?: entity.senderProfile?.let { pair(MasterdataAggregate.SELLER, it.id) }
        is RecipientB2bFieldValue -> pair(MasterdataAggregate.CUSTOMER, entity.recipientId)
        is SenderProfile -> pair(MasterdataAggregate.SELLER, entity.id)
        is Article -> pair(MasterdataAggregate.ARTICLE, entity.id)
        is ArticleRecipientRef -> entity.article?.let { pair(MasterdataAggregate.ARTICLE, it.id) }
        is TaxRate -> pair(MasterdataAggregate.TAX_RATE, entity.id)
        is PaymentTerm -> pair(MasterdataAggregate.PAYMENT_TERM, entity.id)
        // Only quote ranges are replicated; the other subjects stay internal to the invoice module.
        is NumberRange -> if (entity.subject == NumberRangeSubject.QUOTE) pair(MasterdataAggregate.NUMBER_RANGE, entity.id) else null
        is B2bProfile -> pair(MasterdataAggregate.B2B_PROFILE, entity.id)
        is B2bProfileField -> entity.profile?.let { pair(MasterdataAggregate.B2B_PROFILE, it.id) }
        else -> null
    }

    private fun pair(aggregate: MasterdataAggregate, id: Long?) = aggregate to checkNotNull(id) {
        "${aggregate.type} written without an id"
    }

    private fun record(key: Key, deleted: Boolean) {
        check(registry.transactionStatus == Status.STATUS_ACTIVE) {
            "master data change of ${key.aggregate.type} ${key.id} outside a transaction"
        }
        val existing = registry.getResource(RESOURCE) as? Pending
        val pending = existing ?: Pending().also {
            registry.putResource(RESOURCE, it)
            registry.registerInterposedSynchronization(Flush(it))
            // Observed after the commit: the relay runs once the rows are visible.
            appended.fire(OutboxAppended("masterdata"))
        }
        // The last word wins: a removal after a write ends as a removal.
        pending.changes[key] = deleted
    }

    /** The changes of one transaction in first-touch order; `true` marks a removal. */
    private class Pending {
        val changes: MutableMap<Key, Boolean> = LinkedHashMap()
    }

    private inner class Flush(private val pending: Pending) : Synchronization {
        override fun beforeCompletion() {
            if (registry.transactionStatus != Status.STATUS_ACTIVE) return
            pending.changes.forEach { (key, deleted) -> append(key, deleted) }
        }

        override fun afterCompletion(status: Int) = Unit
    }

    private fun append(key: Key, deleted: Boolean) {
        val upserted = if (deleted) null else source.upserted(key.aggregate, key.id)
        val subject = if (upserted == null) key.aggregate.deletedSubject else key.aggregate.upsertedSubject
        val event = upserted ?: mapper.deleted(key.aggregate, key.id)
        outbox.append(key.aggregate.type, key.id.toString(), subject, MasterdataWireCodec.encode(event))
    }

    private companion object {
        const val RESOURCE = "masterdata.pending-changes"
    }
}
