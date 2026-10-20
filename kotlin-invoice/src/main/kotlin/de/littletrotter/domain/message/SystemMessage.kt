package de.littletrotter.domain.message

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import de.littletrotter.domain.system.converters.SystemMessageSeverityConverter
import de.littletrotter.domain.system.converters.SystemMessageSourceConverter

/**
 * A persistent, timestamped system notification (V918). Plain class with
 * body-level `var`s, same shape as [de.littletrotter.domain.masterdata.ArticleTaxVariant]: no
 * kotlin-noarg magic, construction goes through [SystemMessage.of] so [validate] always runs.
 *
 * [recipientUserId] `null` means the message is visible to every user (broadcast) — see the
 * migration's column comment. Read state lives in the separate [SystemMessageReceipt] table, not
 * on this entity, so a message with many recipients (once Authentik lands) doesn't need a
 * receipt row up front for users who never open the message center.
 */
@Entity
@Table(name = "system_message")
@EntityListeners(CreationStampListener::class)
class SystemMessage : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    // Set by the creator from its TimeSource; if it forgets, [CreationStampListener] stamps the
    // insert moment. [StampedOnCreation.UNSET] only exists because JPA needs a no-arg constructor.
    @Column(name = "occurred_at", nullable = false)
    var occurredAt: OffsetDateTime = StampedOnCreation.UNSET
        protected set

    @Convert(converter = SystemMessageSeverityConverter::class)
    @Column(nullable = false)
    var severity: SystemMessageSeverity = SystemMessageSeverity.INFO
        protected set

    @Convert(converter = SystemMessageSourceConverter::class)
    @Column(nullable = false)
    var source: SystemMessageSource = SystemMessageSource.SYSTEM
        protected set

    @field:Size(max = 255)
    @Column(name = "message_key", nullable = false)
    var messageKey: String = ""
        protected set

    /** JSON-encoded array of positional i18n args — see [argsList]/[encodeArgs]. Never read/written
     *  as raw JSON outside this class; producers and the UI go through [argsList]. */
    @Column(name = "message_args", nullable = false, columnDefinition = "TEXT")
    var messageArgs: String = "[]"
        protected set

    @Column(name = "resolved_text", nullable = false, columnDefinition = "TEXT")
    var resolvedText: String = ""
        protected set

    /** Open string (e.g. `"INVOICE"`), deliberately no FK — must survive deletion of the entity
     *  it refers to. */
    @field:Size(max = 50)
    @Column(name = "ref_type")
    var refType: String? = null
        protected set

    @field:Size(max = 50)
    @Column(name = "ref_id")
    var refId: String? = null
        protected set

    /** From [de.littletrotter.application.shared.port.inbound.AuditActorQueryPort.currentActor]. */
    @field:Size(max = 255)
    @Column(nullable = false)
    var actor: String = ""
        protected set

    /** `null` = visible to every user (broadcast). */
    @field:Size(max = 255)
    @Column(name = "recipient_user_id")
    var recipientUserId: String? = null
        protected set

    @Version
    var version: Long? = 0
        protected set

    fun validate() {
        if (messageKey.isBlank()) {
            throw InvalidParameterException("Meldungsschlüssel (messageKey) darf nicht leer sein.")
        }
        if (resolvedText.isBlank()) {
            throw InvalidParameterException("Meldungstext (resolvedText) darf nicht leer sein.")
        }
    }

    /** [messageArgs] decoded back into the positional argument list. */
    fun argsList(): List<String> = decodeArgs(messageArgs)

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (occurredAt == StampedOnCreation.UNSET) occurredAt = now
    }

    companion object {

        /**
         * Reference type of messages that describe a condition that stays true until someone fixes
         * it (e.g. no archive address configured). The retention cleanup spares them; they are
         * withdrawn explicitly once the cause is gone.
         */
        const val STANDING_REF_TYPE = "ARCHIVE_HANDOVER"

        private val ARGS_MAPPER = jacksonObjectMapper()

        fun encodeArgs(args: List<String>): String = ARGS_MAPPER.writeValueAsString(args)

        fun decodeArgs(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return ARGS_MAPPER.readValue<List<String>>(json)
        }

        fun of(
            content: SystemMessageContent,
            actor: String,
            occurredAt: OffsetDateTime,
            recipientUserId: String? = null,
        ): SystemMessage = SystemMessage().apply {
            this.severity = content.severity
            this.source = content.source
            this.messageKey = content.messageKey
            this.messageArgs = encodeArgs(content.args)
            this.resolvedText = content.resolvedText
            this.actor = actor
            this.occurredAt = occurredAt
            this.refType = content.refType
            this.refId = content.refId
            this.recipientUserId = recipientUserId
            validate()
        }
    }
}

enum class SystemMessageSeverity {
    INFO,
    WARNING,
    ERROR,
}

/** Curated source list; producers map onto these, not onto every
 *  domain event. */
enum class SystemMessageSource {
    EXPORT,
    VALIDATION,
    GENERATION,
    INVOICE,
    SYSTEM,
    UI,
}
