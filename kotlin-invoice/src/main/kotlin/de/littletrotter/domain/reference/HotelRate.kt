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

@Entity
@Table(name = "hotel_rate")
@EntityListeners(CreationStampListener::class)
class HotelRate : NamedEntity, ReferenceRateValidity, StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country")
    override var country: Country? = null

    @Column(name = "rate_per_night", precision = 10, scale = 2)
    @Convert(converter = MoneyConverter::class)
    var ratePerNight: Money = Money.ZERO

    override var validFrom: LocalDate? = null
    override var validTo: LocalDate? = null

    /** R2 — a city with its own BMF rate (e.g. Paris, London, Rom); `null` = the country's
     *  catch-all row. Mirrors [PerDiemRate.region]. */
    @field:Size(max = 255)
    override var region: String? = null

    /** R2 — see [PerDiemRate.isActive]. */
    @Column(name = "is_active")
    override var isActive: Boolean = true

    /** R2 — see [PerDiemRate.source]. */
    var source: String? = null

    var createdAt: OffsetDateTime? = null

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0
}
