package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.shared.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * The per-customer value of one [B2bPlacement.RECIPIENT]-placed [B2bProfileField],
 * maintained on the "B2B-Felder" section of the customer (Kunde) form
 * ([de.littletrotter.ui.view.masterdata.RecipientView]) and read back into that customer's
 * invoices as a customer-specific default value, overriding the field's own
 * [B2bProfileField.defaultValue] (resolution order: invoice value > recipient value > profile
 * default value — see [de.littletrotter.application.invoice.B2bProfileRuleSet]).
 *
 * A dedicated table (`recipient_b2b_field_value`), not a fourth [B2bFieldOwnerType] row on the
 * generic `b2b_field_value` table [B2bFieldValue] backs: a `recipient_b2b_field_value` row
 * references its [B2bProfileField] by row id ([profileFieldId]), not by `bt_code`, because the
 * same `bt_code` can be RECIPIENT-placed in one profile and INVOICE-placed in another — keying by
 * the concrete profile field (not the catalog term) is what makes `(recipientId, profileFieldId)`
 * an unambiguous unique pair (`uq_recipient_b2b_field_value_recipient_field`, migration V910).
 * Plain `Long` id columns instead of `@ManyToOne` relations, matching [B2bFieldValue]'s
 * `ownerId`/`btCode` idiom: this table is written/read one row at a time by
 * [de.littletrotter.application.reference.B2bFieldValueService], never traversed as an object graph.
 */
@Entity
@Table(name = "recipient_b2b_field_value")
class RecipientB2bFieldValue : AuditableEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "recipient_id", nullable = false)
    var recipientId: Long = 0
        protected set

    @Column(name = "b2b_profile_field_id", nullable = false)
    var profileFieldId: Long = 0
        protected set

    @Column(name = "value", columnDefinition = "TEXT")
    var value: String? = null
        protected set

    @Version
    var version: Long? = 0
        protected set

    /** Replaces the stored text; blank values are deleted by the caller, not stored. */
    fun changeValue(newValue: String?) {
        value = newValue
    }

    fun validate() {
        if (recipientId <= 0) throw InvalidParameterException("Kunde muss gesetzt sein.")
        if (profileFieldId <= 0) throw InvalidParameterException("Profilfeld muss gesetzt sein.")
    }

    companion object {
        /** An empty value slot of [profileFieldId] for the customer [recipientId]. */
        fun forRecipient(recipientId: Long, profileFieldId: Long): RecipientB2bFieldValue =
            RecipientB2bFieldValue().apply {
                this.recipientId = recipientId
                this.profileFieldId = profileFieldId
            }
    }
}
