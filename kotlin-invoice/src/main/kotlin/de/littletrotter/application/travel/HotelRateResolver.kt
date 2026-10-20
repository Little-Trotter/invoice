package de.littletrotter.application.travel

import de.littletrotter.application.travel.port.inbound.HotelRatePrefillQueryPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.repository.HotelRateRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/**
 * The hotel-rate lookup de.littletrotter.ui.view.invoice.TravelItemDialog used to run
 * directly against [de.littletrotter.application.reference.port.inbound.HotelRateQueryPort]: pulled out here
 * so the trip dialog, datagen's Python mirror (documented contract) and this
 * package's own tests share one resolution instead of each re-deriving "country → per-night rate"
 * on their own (DRY).
 *
 * The country to resolve by is always the recipient's own billing-address country, never
 * [de.littletrotter.application.dto.InvoiceDetailDto.billingAddress] (which is the sender-side
 * override) — callers look that up via the same input port the dialog already uses
 * ([de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort.findBillingAddress])
 * and pass the result in here.
 */
@ApplicationScoped
class HotelRateResolver @Inject constructor(
    private val hotelRateRepository: HotelRateRepository,
) : HotelRatePrefillQueryPort {
    /** The active per-night rate for [country] on [date] (the caller's today — a bean may not
     *  read the clock itself), or `null` when no rate is configured — callers keep whatever value
     *  the user already entered in that case (the dialog's existing "prefill, don't force"
     *  behaviour). */
    fun resolveRatePerNight(country: Country, date: LocalDate): HotelRate? =
        hotelRateRepository.findByCountryAndDate(country, date)

    override fun ratePerNight(country: Country, date: LocalDate): Money? =
        resolveRatePerNight(country, date)?.ratePerNight
}
