package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * A named collection of [B2bProfileField]s. A [de.littletrotter.domain.masterdata
 * .SenderProfile] carries at most one active profile; its fields then appear on the seller
 * form and/or the invoice form, per field [B2bPlacement].
 */
@Entity
@Table(name = "b2b_profile")
class B2bProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @field:Size(max = 150)
    var name: String = ""

    @Column(columnDefinition = "TEXT")
    var description: String? = null

    var active: Boolean = true

    @OneToMany(mappedBy = "profile", cascade = [CascadeType.ALL], orphanRemoval = true)
    var fields: MutableList<B2bProfileField> = mutableListOf()

    /**
     * anbieter-einvoice-ergonomie §2 — the country scope this profile's SELLER/BOTH-placed fields
     * apply to. **Empty = unscoped**: the fields are visible for every seller, mirroring the
     * `PartyIdentifierSpec.countries == null` "no scope = always visible" semantics — and making
     * the V910 migration behavior-preserving, since every pre-existing profile starts with an
     * empty set. A non-empty set means "only for a seller registered (see
     * [de.littletrotter.domain.masterdata.sellerRegistrationCountries]) in one of these
     * countries" — France/Luxembourg/Belgium Factur-X is simply the first instance of this
     * general rule, not a hardcoded special case.
     */
    @ElementCollection
    @CollectionTable(name = "b2b_profile_country", joinColumns = [JoinColumn(name = "profile_id")])
    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country", nullable = false)
    var countries: MutableSet<Country> = mutableSetOf()

    @Version
    var version: Long? = 0

    fun validate(today: LocalDate) {
        if (name.isBlank()) throw InvalidParameterException("Profilname darf nicht leer sein.")
        val allowedCountryScope = allowedCountryScope(today)
        val disallowed = countries - allowedCountryScope
        if (disallowed.isNotEmpty()) {
            throw InvalidParameterException(
                "Land ${disallowed.first().alpha2} ist für die Länder-Einschränkung eines B2B-Profils " +
                    "nicht freigegeben (erlaubt: ${allowedCountryScope.joinToString { it.alpha2 }})."
            )
        }
    }

    /** fix-einvoice-guided-profile-party-values (F3) — id-based equality, mirroring
     *  [de.littletrotter.domain.masterdata.Recipient.equals]/[de.littletrotter.domain.masterdata
     *  .SenderProfile.equals] verbatim: the guided entry pane's `b2bProfileBox` preselects a
     *  server-resolved [B2bProfile] instance that is never the same object as the one in the
     *  ComboBox's own item list (a different persistence-context load) — without an id-based
     *  `equals`, Vaadin's default identity equality never finds the match and the preselection
     *  silently fails to render. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is B2bProfile) return false
        if (id != null && other.id != null) return id == other.id
        return false
    }

    /** IMP-5 — constant, not `id?.hashCode()`: an id-based hash is unstable across persist (id
     *  changes from null to a value within the same transaction), which would move an
     *  already-inserted-into-a-HashSet/Map entity to the wrong bucket. Safe here since [equals]
     *  stays id-based — see [de.littletrotter.domain.masterdata.SenderProfile.hashCode]'s own
     *  rationale. */
    override fun hashCode(): Int = B2bProfile::class.java.hashCode()

    companion object {
        /** Rejects a candidate list carrying the same [B2bProfileField.term] twice, mirroring
         *  the DB unique index `uq_b2b_profile_field_profile_bt_code`. */
        fun assertUniqueTerms(fields: List<B2bProfileField>) {
            val duplicated = fields.groupBy { it.term }.filterValues { it.size > 1 }.keys
            if (duplicated.isNotEmpty()) {
                throw InvalidParameterException(
                    "Term '${duplicated.first()?.officialCode}' ist mehrfach im Profil vorhanden — je Term ist " +
                        "höchstens ein Profilfeld zulässig."
                )
            }
        }

        /**
         * §2 — the curated set a profile's [countries] may be drawn from: every EU member state
         * (via [Country.supportedSellerVatRegistrationCountries], which already excludes DE)
         * plus [Country.DE] itself — unlike a seller's *additional* VAT registrations, a profile's
         * own scope legitimately includes Germany (e.g. a profile meant only for German sellers).
         * A function rather than a `val` (defaulting to a fixed date used to freeze this at
         * class-load forever): a domain object may not read the clock itself
         * (TimeSourceArchitectureTest), so the caller passes its own `TimeSource.today()`.
         */
        fun allowedCountryScope(today: LocalDate): Set<Country> = Country.supportedSellerVatRegistrationCountries(today) + Country.DE
    }
}
