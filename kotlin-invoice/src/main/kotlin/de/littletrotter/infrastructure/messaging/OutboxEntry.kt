package de.littletrotter.infrastructure.messaging

/** Lifecycle of an outbox row; the ordinal is the persisted `outbox.status` value. */
enum class OutboxStatus { PENDING, PUBLISHED, FAILED }

/** One event waiting to be published; [id] is also the JetStream de-duplication id. */
data class OutboxEntry(
    val id: Long,
    val aggregateType: String,
    val aggregateId: String,
    val subject: String,
    val payload: String,
    val attempts: Int,
)

/**
 * Fired by a producer after it appended an outbox row, observed by [OutboxRelay] once the
 * surrounding transaction has committed — the relay never sees a row that could still roll back.
 */
class OutboxAppended(val subject: String)
