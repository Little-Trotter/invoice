package de.littletrotter.application.reference

import de.littletrotter.application.error.ApplicationExceptionTranslator
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TravelExpenseDefinition
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions as MealDeductionSelection
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.domain.compliance.DomainConstraints
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.time.LocalDate

/** Where a per-diem rate is looked up: country (ISO alpha-2) + region. Bundled so
 *  [PerDiemService.calculate]/[PerDiemService.createEntry] (DB-lookup overload) take one
 *  parameter for it instead of two (LongParameterList). */
data class PerDiemRateLookup(val countryIso2: String, val region: String)

/** Which per-diem day a rate applies to: the calendar date and whether it is an arrival/departure
 *  (half) day rather than a full day. Bundled for the same reason as [PerDiemRateLookup]. */
data class PerDiemDay(val date: LocalDate, val isArrivalDeparture: Boolean)

@ApplicationScoped
class PerDiemService @Inject constructor(
    val perDiemRateRepository: PerDiemRateRepository,
) {

    /**
     * Calculates the claimable per diem after subtracting meal deductions and any
     * monetary employer contribution (Arbeitgeberleistung).
     *
     * <p>Deduction amounts follow the rules stored on the [PerDiemRate]:
     * <ul>
     *   <li><b>Inland (DE)</b>: fixed amounts are 20 % / 40 % / 40 % of the
     *       <em>full-day rate</em> (§ 4 Abs. 5 Nr. 5 EStG / BMF), regardless of
     *       whether the applicable rate is full-day or arrival/departure.</li>
     *   <li><b>International</b>: deductions are 20 % / 40 % / 40 % of the
     *       <em>applicable base rate</em> (full-day or arrival/departure).</li>
     * </ul>
     *
     * @param employerContribution monetary amount already paid by the employer toward meals
     */
    fun calculate(
        lookup: PerDiemRateLookup,
        day: PerDiemDay,
        deductions: MealDeductionSelection,
        employerContribution: Money,
    ): BigDecimal {
        // Negative employerContribution is rejected by rate.calculateClaimableAmount() itself
        // (InvalidParameterException, translated below) -- no need to duplicate that guard here.
        val countryEnum = Country.fromAlpha2(lookup.countryIso2) ?: throw ApplicationValidationException("Invalid country: ${lookup.countryIso2}")
        val rate = perDiemRateRepository
            .findByCountryAndRegionAndDate(countryEnum, lookup.region, day.date)
            ?: throw ApplicationValidationException(
                "No per diem rate found for country=${lookup.countryIso2}" +
                    ", region=${lookup.region}, date=${day.date}"
            )

        return try {
            rate.calculateClaimableAmount(
                isArrivalDeparture = day.isArrivalDeparture,
                deductions = deductions,
                employerContribution = employerContribution,
            )
        } catch (e: de.littletrotter.domain.exception.InvalidParameterException) {
            throw ApplicationExceptionTranslator.from(e, "Invalid per-diem calculation")
        }
    }

    /**
     * Creates a [TravelExpenseEntry] with rate snapshots and a pre-calculated amount,
     * factoring in meal deductions and any monetary employer contribution (Arbeitgeberleistung).
     *
     * @param employerContribution monetary amount already paid by the employer toward meals
     */
    fun createEntry(
        lookup: PerDiemRateLookup,
        day: PerDiemDay,
        deductions: MealDeductionSelection,
        employerContribution: Money,
    ): TravelExpenseEntry {
        val countryEnum = Country.fromAlpha2(lookup.countryIso2) ?: throw ApplicationValidationException("Invalid country: ${lookup.countryIso2}")
        val rate = perDiemRateRepository
            .findByCountryAndRegionAndDate(countryEnum, lookup.region, day.date)
            ?: throw ApplicationValidationException(
                "No per diem rate found for country=${lookup.countryIso2}" +
                    ", region=${lookup.region}, date=${day.date}"
            )

        val calculated = calculate(lookup, day, deductions, employerContribution)

        val generatedShortName = listOfNotNull(countryEnum.alpha2, lookup.region.takeIf { it.isNotBlank() }, day.date.toString()).joinToString(" ")
        return buildEntry(
            EntryIdentity(shortName = generatedShortName, country = countryEnum, participant = lookup.region.ifBlank { generatedShortName }),
            day,
            PerDiemMealDeductions(
                breakfast = deductions.breakfast,
                lunch = deductions.lunch,
                dinner = deductions.dinner,
                employerContribution = employerContribution,
                rate = rate,
                calculated = calculated,
                linkRate = false,
            ),
        )
    }

    /**
     * Creates a [TravelExpenseEntry] using a pre-selected [PerDiemRate] without a DB lookup.
     */
    fun createEntry(
        rate: PerDiemRate,
        day: PerDiemDay,
        deductions: MealDeductionSelection,
        employerContribution: Money = Money.ZERO,
    ): TravelExpenseEntry {
        // Negative employerContribution is rejected by rate.calculateClaimableAmount() itself
        // (InvalidParameterException, translated below) -- no need to duplicate that guard here.
        val rateCountry = rate.country ?: throw ApplicationValidationException("Per-Diem-Satz hat kein Land.")
        val calculated = try {
            rate.calculateClaimableAmount(
                isArrivalDeparture = day.isArrivalDeparture,
                deductions = deductions,
                employerContribution = employerContribution,
            )
        } catch (e: de.littletrotter.domain.exception.InvalidParameterException) {
            throw ApplicationExceptionTranslator.from(e, "Invalid per-diem calculation")
        }
        val r = rate.region?.takeIf { it.isNotBlank() }
        val generatedShortName = listOfNotNull(rateCountry.alpha2, r, day.date.toString()).joinToString(" ")
        return buildEntry(
            EntryIdentity(shortName = generatedShortName, country = rateCountry, participant = r ?: generatedShortName),
            day,
            PerDiemMealDeductions(
                breakfast = deductions.breakfast,
                lunch = deductions.lunch,
                dinner = deductions.dinner,
                employerContribution = employerContribution,
                rate = rate,
                calculated = calculated,
                linkRate = true,
            ),
        )
    }

    /** [buildEntry]'s own shortName/country/participant fields, bundled so it takes one
     *  parameter for them instead of three (LongParameterList). */
    private data class EntryIdentity(
        val shortName: String,
        val country: Country,
        val participant: String,
    )

    private data class PerDiemMealDeductions(
        val breakfast: Boolean,
        val lunch: Boolean,
        val dinner: Boolean,
        val employerContribution: Money,
        val rate: PerDiemRate,
        val calculated: BigDecimal,
        val linkRate: Boolean,
    )

    /** Shared builder for [TravelExpenseEntry] used by both [createEntry] overloads — the
     *  DB-lookup path (countryIso2) and the pre-selected-rate path write the same 15 entity
     *  fields to avoid silent drift when a new field is introduced. */
    private fun buildEntry(
        identity: EntryIdentity,
        day: PerDiemDay,
        deductions: PerDiemMealDeductions,
    ): TravelExpenseEntry {
        val (shortName, country, participant) = identity
        return TravelExpenseEntry.create(
            TravelExpenseDefinition(
                shortName = shortName,
                country = country,
                // Neither creation path has a UI form to supply a real description,
                // so the already-computed short name doubles as a sensible placeholder.
                description = shortName,
                participant = participant,
                travelDate = day.date,
                isArrivalDeparture = day.isArrivalDeparture,
                breakfastDeduction = deductions.breakfast,
                lunchDeduction = deductions.lunch,
                dinnerDeduction = deductions.dinner,
                fullDayRateSnapshot = deductions.rate.fullDayRate,
                arrivalDepartureRateSnapshot = deductions.rate.arrivalDepartureRate,
                perDiemRate = if (deductions.linkRate) deductions.rate else null,
                calculatedAmount = Money.of(deductions.calculated),
                employerContribution = deductions.employerContribution,
            ),
        ).also(DomainConstraints::validateTravelExpenseEntry)
    }
}
