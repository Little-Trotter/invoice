package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.reference.converters.VehicleTypeConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.NamedEntity
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.shared.Money
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * R2 — the German business-mileage rate depends on the vehicle, not the destination country
 * (§ 9 Abs. 1 Nr. 4a EStG / § 5 BRKG): a car and "another motor vehicle" (e.g. motorcycle) get
 * different flat rates, applied identically whether the trip is domestic or abroad. Simplified
 * relative to § 5 BRKG's actual conditional structure — see [MileageRate]'s own KDoc.
 */
enum class VehicleType { CAR, OTHER_MOTOR_VEHICLE }

/** The editable rate and validity of one mileage rate row, applied as a whole by [MileageRate.redefine]. */
data class MileageRateDefinition(
    val country: Country?,
    val vehicleType: VehicleType?,
    val ratePerKm: BigDecimal,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val isActive: Boolean,
    val source: String?,
)

/**
 * R2 — `country` stays on this entity for the V901 legacy rows (deactivated, not deleted; a
 * historical invoice's [de.littletrotter.domain.reference.TravelExpenseEntry] may still point at
 * one), but resolution for NEW positions no longer looks at it at all — see [isApplicableFor].
 *
 * The seeded two-row model (`CAR` 0,30 €/km, `OTHER_MOTOR_VEHICLE` 0,20 €/km) simplifies § 5
 * BRKG's real, conditional structure: § 5 Abs. 1 (Standardsatz, 0,20 €/km for ANY motor vehicle,
 * capped at €130 per trip) vs. § 5 Abs. 2 (erhöhter Satz, 0,30 €/km, car only, requires a
 * written "erhebliches dienstliches Interesse" finding made *before* the trip). Neither the
 * per-trip cap nor the written-finding requirement is enforced here — see R2's Handoff.
 */
@Entity
@Table(name = "mileage_rate")
@EntityListeners(CreationStampListener::class)
class MileageRate : NamedEntity, StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    var country: Country? = null
        protected set

    @Column(name = "rate_per_km", precision = 10, scale = 4)
    var ratePerKm: BigDecimal = BigDecimal.ZERO
        protected set

    var validFrom: LocalDate? = null
        protected set
    var validTo: LocalDate? = null
        protected set

    @Convert(converter = VehicleTypeConverter::class)
    @Column(name = "vehicle_type")
    var vehicleType: VehicleType? = null
        protected set

    @Column(name = "is_active")
    var isActive: Boolean = true
        protected set

    var source: String? = null
        protected set

    var createdAt: OffsetDateTime? = null
        protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0
        protected set

    /** Pre-R2 country-based lookup — kept only for the deactivated V901 legacy rows; no new
     *  code path should call this (use [isApplicableFor] with a [VehicleType] instead). */
    fun isApplicableFor(requestCountry: Country, date: LocalDate): Boolean {
        val countryMatches = country == requestCountry
        val startsBeforeOrAt = validFrom == null || !validFrom!!.isAfter(date)
        val endsAfterOrAt = validTo == null || !validTo!!.isBefore(date)
        return countryMatches && startsBeforeOrAt && endsAfterOrAt
    }

    /** R2 — the actual resolution: vehicle type + date, never country. */
    fun isApplicableFor(requestVehicleType: VehicleType, date: LocalDate): Boolean {
        val vehicleMatches = vehicleType == requestVehicleType
        val startsBeforeOrAt = validFrom == null || !validFrom!!.isAfter(date)
        val endsAfterOrAt = validTo == null || !validTo!!.isBefore(date)
        return isActive && vehicleMatches && startsBeforeOrAt && endsAfterOrAt
    }

    /**
     * Q2: the result is a monetary amount, so it is computed via [Money] even though [ratePerKm]
     * itself stays a plain rate (task Q3 decision — 4 decimals are a deliberate precision, not
     * an unrounded intermediate value; [Money] would normalize it away).
     */
    fun calculateAmount(kilometers: BigDecimal): Money {
        if (kilometers < BigDecimal.ZERO) throw InvalidParameterException("Kilometer dürfen nicht negativ sein.")
        return Money.exact(ratePerKm) * kilometers
    }

    /** Replaces every editable field at once; id, creation stamp and revision stay the row's own. */
    fun redefine(def: MileageRateDefinition) {
        country = def.country
        vehicleType = def.vehicleType
        ratePerKm = def.ratePerKm
        validFrom = def.validFrom
        validTo = def.validTo
        isActive = def.isActive
        source = def.source
    }

    fun validate() {
        if (country == null) throw InvalidParameterException("Land darf nicht leer sein.")
        if (ratePerKm < BigDecimal.ZERO) throw InvalidParameterException("Satz pro km darf nicht negativ sein.")
        if (validFrom != null && validTo != null && validFrom!!.isAfter(validTo!!))
            throw InvalidParameterException("Gültigkeitsbeginn darf nicht nach Gültigkeitsende liegen.")
    }
}
