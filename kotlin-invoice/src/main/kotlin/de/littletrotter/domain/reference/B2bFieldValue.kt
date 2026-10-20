package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.converters.B2bFieldOwnerTypeConverter
import de.littletrotter.domain.reference.converters.B2bTermConverter
import de.littletrotter.shared.b2b.B2bFieldKind
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The value of one [B2bTermBinding.FREE] [B2bTerm] on one owning record.
 * Edition-neutral and populated separately for the seller, article template and invoice/position
 * layers; this class only creates the table and the persistence plumbing. Text-typed on purpose (`value`): the
 * owning [B2bFieldOwnerType]/[B2bTerm.fieldKind] decide how a caller parses it, the table itself
 * stays a generic key-value store like the rest of the design (point 6).
 *
 * At most one row per `(ownerType, ownerId, term)` (DB unique index
 * `uq_b2b_field_value_owner_bt_code`).
 */
@Entity
@Table(name = "b2b_field_value")
class B2bFieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Convert(converter = B2bFieldOwnerTypeConverter::class)
    @Column(name = "owner_type", nullable = false)
    var ownerType: B2bFieldOwnerType = B2bFieldOwnerType.SENDER_PROFILE
        protected set

    @Column(name = "owner_id", nullable = false)
    var ownerId: Long = 0
        protected set

    /** Persisted as the [B2bTerm]'s official BT number ([B2bTermConverter]). */
    @Convert(converter = B2bTermConverter::class)
    @Column(name = "bt_code", nullable = false)
    var term: B2bTerm? = null
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

    /**
     * The save path hard-checks `contains(value)` alongside the existing `maxLength` rule; both
     * checks now live here, next to each other, since neither previously existed on this class at
     * all — `saveFieldValues`
     * only ever called `ownerId`/`term` presence before this task. Both guard a *programmatic*
     * writer (a script, an import, a future API), not the ComboBox: [B2bFieldWidgetFactory
     * ][de.littletrotter.ui.component.B2bFieldWidgetFactory]'s ENUM ComboBox already makes an
     * invalid value impossible to *enter* by construction; this is the last-stop check for a value
     * that reaches this class some other way. Skipped for a blank/`null` [value] — `saveFieldValues`
     * deletes the row instead of calling `validate()` on the delete path, so this only ever sees a
     * genuinely present value, but stays defensive for any other caller.
     */
    fun validate() {
        val t = requireOwnerAndTerm()
        val v = value
        if (v.isNullOrBlank()) return
        validateLengthAndDomain(t, v)
        validateFormat(t, v)
    }

    // ThrowsCount — validate()'s six checks split across three helpers so each stays
    // under ThrowsCount on its own; behaviour is unchanged.
    private fun requireOwnerAndTerm(): B2bTerm {
        if (ownerId <= 0) throw InvalidParameterException("Eigentümer-ID muss gesetzt sein.")
        return term ?: throw InvalidParameterException("Business-Term darf nicht fehlen.")
    }

    private fun validateLengthAndDomain(t: B2bTerm, v: String) {
        t.maxLength?.let { max ->
            if (v.length > max) {
                throw InvalidParameterException(
                    "${t.officialCode}: Wert überschreitet die zulässige Länge von $max Zeichen."
                )
            }
        }
        t.valueDomain?.let { domain ->
            if (!domain.contains(v)) {
                throw InvalidParameterException("${t.officialCode}: '$v' ist kein gültiger Code der Liste $domain.")
            }
        }
    }

    // saveFieldValues had no server-side format check for NUMBER/DATE kinds at all: the widget
    // layer's DatePicker (silently) refuses a malformed date and the plain
    // TextField never validated a number in the first place, but a programmatic writer that
    // reaches this class directly (the same "not the ComboBox" reasoning as maxLength/
    // valueDomain above) bypassed both. ISO-8601 (`LocalDate.parse`) is the exact rule
    // B2bFieldWidgetFactory.setWidgetValue's own DatePicker branch already applies.
    private fun validateFormat(t: B2bTerm, v: String) {
        if (t.fieldKind == B2bFieldKind.NUMBER && v.toBigDecimalOrNull() == null) {
            throw InvalidParameterException("${t.officialCode}: '$v' ist keine gültige Zahl.")
        }
        if (t.fieldKind == B2bFieldKind.DATE) {
            try {
                LocalDate.parse(v)
            } catch (_: DateTimeParseException) {
                throw InvalidParameterException("${t.officialCode}: '$v' ist kein gültiges Datum (ISO-8601, z. B. 2026-08-30).")
            }
        }
    }

    companion object {
        /** An empty value slot of [term] on the record ([ownerType], [ownerId]). */
        fun forOwner(ownerType: B2bFieldOwnerType, ownerId: Long, term: B2bTerm): B2bFieldValue =
            B2bFieldValue().apply {
                this.ownerType = ownerType
                this.ownerId = ownerId
                this.term = term
            }
    }
}
