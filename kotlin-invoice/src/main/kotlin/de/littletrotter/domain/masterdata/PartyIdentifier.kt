package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.Embeddable
import jakarta.validation.constraints.Size

/** Shape shared by [PartyIdentifier]/[ElectronicAddress]/[LegalRegistrationIdentifier] — lets
 *  callers such as [de.littletrotter.domain.invoice.previewPartySnapshotRefresh] render any of
 *  the three the same way without depending on one concrete embeddable. */
interface ValueSchemePair {
    val value: String
    val schemeId: String
}

/** Shared `toString` rendering for [PartyIdentifier]/[ElectronicAddress]/[LegalRegistrationIdentifier] —
 *  one definition instead of the identical `"$schemeId:$value"` template in all three. */
private fun formatValueScheme(schemeId: String, value: String): String = "$schemeId:$value"

/**
 * BT-29/BT-46 party identifier: value and scheme always travel together.
 * @Embeddable (not AttributeConverter) because two columns needed.
 * Plain class with var, not data class: no kotlin-noarg plugin, Hibernate needs no-arg constructor.
 *
 * ST-B3 — [value]/[schemeId]'s `@Size` bounds mirror every column this embeddable is mapped onto
 * (`seller_identifier_value`/`buyer_identifier_value`/`invoice_party_snapshot.identifier_value` =
 * `VARCHAR(255)`, their `_scheme_id` siblings = `VARCHAR(10)`, `V1__baseline_schema.sql`) — all
 * three usages share the same widths, so one bound here covers every call site.
 */
@Embeddable
class PartyIdentifier : ValueSchemePair {
    @field:Size(max = 255)
    override var value: String = ""
    private set

    @field:Size(max = 10)
    override var schemeId: String = ""
    private set

    companion object {
        fun of(value: String, schemeId: String): PartyIdentifier {
            if (value.isBlank()) throw InvalidParameterException("Kennungswert darf nicht leer sein.")
            if (schemeId.isBlank()) throw InvalidParameterException("Kennung ohne Scheme-ID ist nicht zulässig (P1).")
            return PartyIdentifier().apply { this.value = value.trim(); this.schemeId = schemeId.trim() }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PartyIdentifier && value == other.value && schemeId == other.schemeId

    override fun hashCode(): Int = 31 * value.hashCode() + schemeId.hashCode()
    override fun toString(): String = formatValueScheme(schemeId, value)
}

/**
 * BT-34/BT-49 electronic address: value + scheme, always paired.
 * Distinct from [PartyIdentifier] to prevent Business-Term confusion (BT-29/46 vs BT-34/49).
 */
@Embeddable
class ElectronicAddress : ValueSchemePair {
    @field:Size(max = 255)
    override var value: String = ""
    private set

    @field:Size(max = 10)
    override var schemeId: String = ""
    private set

    companion object {
        fun of(value: String, schemeId: String): ElectronicAddress {
            if (value.isBlank()) throw InvalidParameterException("Adresswert darf nicht leer sein.")
            if (schemeId.isBlank()) throw InvalidParameterException("Elektronische Adresse ohne Scheme-ID ist nicht zulässig (P1).")
            return ElectronicAddress().apply { this.value = value.trim(); this.schemeId = schemeId.trim() }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ElectronicAddress && value == other.value && schemeId == other.schemeId

    override fun hashCode(): Int = 31 * value.hashCode() + schemeId.hashCode()
    override fun toString(): String = formatValueScheme(schemeId, value)
}

/** P1 — a BT-30/BT-47 legal registration identifier + its register-specific scheme. */
@Embeddable
class LegalRegistrationIdentifier : ValueSchemePair {
    @field:Size(max = 255)
    override var value: String = ""
    private set

    @field:Size(max = 10)
    override var schemeId: String = ""
    private set

    companion object {
        fun of(value: String, schemeId: String): LegalRegistrationIdentifier {
            if (value.isBlank()) throw InvalidParameterException("Registerkennung darf nicht leer sein.")
            if (schemeId.isBlank()) throw InvalidParameterException("Registerkennung ohne Scheme ist nicht zulässig (P1).")
            return LegalRegistrationIdentifier().apply { this.value = value.trim(); this.schemeId = schemeId.trim() }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LegalRegistrationIdentifier && value == other.value && schemeId == other.schemeId

    override fun hashCode(): Int = 31 * value.hashCode() + schemeId.hashCode()
    override fun toString(): String = formatValueScheme(schemeId, value)
}
