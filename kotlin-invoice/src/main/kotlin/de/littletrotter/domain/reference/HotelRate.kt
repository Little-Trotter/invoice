package de.littletrotter.domain.reference

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.MoneyConverter
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
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime

/** The editable figures and validity of one hotel rate row, applied as a whole by [HotelRate.redefine]. */
data class HotelRateDefinition(
    val country: Country?,
    val region: String?,
    val ratePerNight: Money,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val isActive: Boolean,
    val source: String?,
)

@Entity
@Table(name = "hotel_rate")
@EntityListeners(CreationStampListener::class)
class HotelRate : NamedEntity, ReferenceRateValidity, StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    override var country: Country? = null
        protected set

    @Column(name = "rate_per_night", precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var ratePerNight: Money = Money.ZERO
        protected set

    override var validFrom: LocalDate? = null
        protected set
    override var validTo: LocalDate? = null
        protected set

    /** R2 — a city with its own BMF rate (e.g. Paris, London, Rom); `null` = the country's
     *  catch-all row. Mirrors [PerDiemRate.region]. */
    @field:Size(max = 255)
    override var region: String? = null
        protected set

    /** R2 — see [PerDiemRate.isActive]. */
    @Column(name = "is_active")
    override var isActive: Boolean = true
        protected set

    /** R2 — see [PerDiemRate.source]. */
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

    /** Replaces every editable field at once; id, creation stamp and revision stay the row's own. */
    fun redefine(def: HotelRateDefinition) {
        country = def.country
        region = def.region
        ratePerNight = def.ratePerNight
        validFrom = def.validFrom
        validTo = def.validTo
        isActive = def.isActive
        source = def.source
    }
}
