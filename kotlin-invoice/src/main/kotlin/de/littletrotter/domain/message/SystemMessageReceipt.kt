package de.littletrotter.domain.message

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

/**
 * One user's read receipt for one [SystemMessage] (V918). Plain `messageId: Long`
 * column rather than a `@ManyToOne`, same style as
 * [de.littletrotter.domain.compliance.ValidationFinding.invoiceId]: receipts are written in bulk
 * ([de.littletrotter.application.message.MessageCenterService.markAllRead]) without needing the
 * owning [SystemMessage] loaded into the persistence context first.
 *
 * Unique on `(message_id, user_id)` at the DB (`uq_system_message_receipt_message_user`) — today
 * only `user_id = "system"` rows are ever created (no auth yet), but the shape already carries n
 * users per message for when Authentik lands.
 */
@Entity
@Table(name = "system_message_receipt")
@EntityListeners(CreationStampListener::class)
class SystemMessageReceipt : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "message_id", nullable = false)
    var messageId: Long = 0
        protected set

    @field:Size(max = 255)
    @Column(name = "user_id", nullable = false)
    var userId: String = ""
        protected set

    // Set by the creator from its TimeSource; if it forgets, [CreationStampListener] stamps the
    // insert moment. [StampedOnCreation.UNSET] only exists because JPA needs a no-arg constructor.
    @Column(name = "read_at", nullable = false)
    var readAt: OffsetDateTime = StampedOnCreation.UNSET
        protected set

    fun validate() {
        if (messageId <= 0) {
            throw InvalidParameterException("Meldungs-Id (messageId) muss gesetzt sein.")
        }
        if (userId.isBlank()) {
            throw InvalidParameterException("Benutzer-Id (userId) darf nicht leer sein.")
        }
    }

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (readAt == StampedOnCreation.UNSET) readAt = now
    }

    companion object {

        fun of(messageId: Long, userId: String, readAt: OffsetDateTime): SystemMessageReceipt =
            SystemMessageReceipt().apply {
                this.messageId = messageId
                this.userId = userId
                this.readAt = readAt
                validate()
            }
    }
}
