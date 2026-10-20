package de.littletrotter.application.travel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.reference.PerDiemService
import de.littletrotter.application.reference.toDto
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * Unit tests for [PerDiemCalculator]: resolving a rate by id, building a
 * [de.littletrotter.domain.reference.TravelExpenseEntry] from a [PerDiemEntryCommand], and the
 * live-preview claimable amount, all delegating to [de.littletrotter.domain.reference.PerDiemRate]
 * (no second formula).
 */
class PerDiemCalculatorTest {

    private val perDiemRateRepository: PerDiemRateRepository = mock()
    private val calculator = PerDiemCalculator(perDiemRateRepository, PerDiemService(perDiemRateRepository))

    private fun rate(id: Long = 1L, fullDay: String = "28.00", arrivalDeparture: String = "14.00", inland: Boolean = true) =
        PerDiemRate().edit {
            this.id = id
            country = Country.DE
            fullDayRate = Money.of(fullDay)
            arrivalDepartureRate = Money.of(arrivalDeparture)
            inlandRules = inland
            isActive = true
        }

    @Test
    fun `resolveRate returns the repository row when found`() {
        val theRate = rate()
        whenever(perDiemRateRepository.findById(1L)).thenReturn(theRate)

        assertEquals(theRate, calculator.resolveRate(1L))
    }

    @Test
    fun `resolveRate throws for an unknown rate id`() {
        whenever(perDiemRateRepository.findById(999L)).thenReturn(null)

        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            de.littletrotter.application.error.ApplicationValidationException::class.java,
        ) { calculator.resolveRate(999L) }
        assertTrue(ex.message!!.contains("999"))
    }

    @Test
    fun `buildEntry resolves the rate, snapshots amounts and applies the command's own description and participant`() {
        val theRate = rate(fullDay = "28.00", arrivalDeparture = "14.00")
        whenever(perDiemRateRepository.findById(4L)).thenReturn(theRate)
        val command = PerDiemEntryCommand(
            date = LocalDate.of(2026, 9, 11),
            isArrivalDeparture = false,
            breakfast = false, lunch = false, dinner = false,
            description = "Team-Reise Bayern",
            participant = "Kundentermin Bayern",
            employerContribution = Money.ZERO,
            perDiemRateId = 4L,
        )

        val entry = calculator.buildEntry(command)

        assertEquals(theRate, entry.perDiemRate)
        assertEquals("Team-Reise Bayern", entry.description)
        assertEquals("Kundentermin Bayern", entry.participant)
        assertEquals(Money.of("28.00"), entry.calculatedAmount)
        assertEquals(Money.of("28.00"), entry.fullDayRateSnapshot)
        assertEquals(Money.of("14.00"), entry.arrivalDepartureRateSnapshot)
    }

    @Test
    fun `buildEntry always overwrites whatever description and participant PerDiemService set, unconditionally`() {
        val theRate = rate()
        whenever(perDiemRateRepository.findById(4L)).thenReturn(theRate)
        val command = PerDiemEntryCommand(
            date = LocalDate.of(2026, 9, 11), isArrivalDeparture = false,
            breakfast = false, lunch = false, dinner = false,
            description = "Dienstreise ohne Region", participant = "Team ohne Region",
            employerContribution = Money.ZERO, perDiemRateId = 4L,
        )

        val entry = calculator.buildEntry(command)

        // PerDiemService.createEntry(rate, ...) would otherwise have fallen back to its own
        // generated short name (rate() never sets a region) — buildEntry's own unconditional
        // assignment must win regardless, since command.description/command.participant are
        // validated non-blank upstream (TripCommandValidator) and are always the entry's real
        // values.
        assertEquals("Dienstreise ohne Region", entry.description)
        assertEquals("Team ohne Region", entry.participant)
    }

    @Test
    fun `buildEntries builds one entry per command, preserving order`() {
        val theRate = rate()
        whenever(perDiemRateRepository.findById(4L)).thenReturn(theRate)
        val commands = listOf(
            PerDiemEntryCommand(LocalDate.of(2026, 2, 8), false, false, false, false, "Team-Reise", "Anreise", Money.ZERO, 4L),
            PerDiemEntryCommand(LocalDate.of(2026, 2, 9), true, false, false, false, "Team-Reise", "Vor Ort", Money.ZERO, 4L),
        )

        val entries = calculator.buildEntries(commands)

        assertEquals(2, entries.size)
        assertEquals(LocalDate.of(2026, 2, 8), entries[0].travelDate)
        assertEquals(LocalDate.of(2026, 2, 9), entries[1].travelDate)
        assertEquals(Money.of("28.00"), entries[0].calculatedAmount)
        assertEquals(Money.of("14.00"), entries[1].calculatedAmount)
    }

    @Test
    fun `previewClaimableAmount matches PerDiemRateDto's own formula directly`() {
        val theRate = rate(fullDay = "28.00", arrivalDeparture = "14.00").toDto()

        val deductions = PerDiemMealDeductions(breakfast = true, lunch = false, dinner = false)
        val preview = calculator.previewClaimableAmount(
            theRate, isArrivalDeparture = false, deductions = deductions,
        )

        val direct = theRate.calculateClaimableAmount(isArrivalDeparture = false, deductions = deductions)
        assertEquals(direct, preview)
    }

    // ── datagen fixture (dev-key exempted) ──

    /**
     * The JSON test vectors datagen's Python mirror uses (`per_diem_vectors.json`, copied here
     * verbatim from little-trotter-admin-tools) loaded and run through the real
     * [PerDiemCalculator]/[PerDiemRate.calculateClaimableAmount] formula.
     *
     * Every vector carries the applicable inland/international rule and employer contribution,
     * so the shared contract can assert the exact amount for every branch of the domain formula.
     */
    @TestFactory
    fun `per-diem calculation vectors from the datagen fixture`(): List<DynamicTest> {
        val mapper = ObjectMapper()
        val root: JsonNode = mapper.readTree(
            javaClass.classLoader.getResourceAsStream("travel/per_diem_vectors.json")
                ?: error("per_diem_vectors.json missing from test resources"),
        )
        val vectors = root.get("vectors")
        assertTrue(vectors.size() >= 7, "the fixture must still carry all its vectors")

        return vectors.map { vector ->
            val id = vector.get("id").asText()
            DynamicTest.dynamicTest(id) {
                val theRate = rate(
                    fullDay = vector.get("full_day_rate").asText(),
                    arrivalDeparture = vector.get("arrival_departure_rate").asText(),
                    inland = vector.get("inland_rules").asBoolean(),
                ).toDto()
                val claimable = calculator.previewClaimableAmount(
                    theRate,
                    isArrivalDeparture = vector.get("is_arrival_departure").asBoolean(),
                    deductions = PerDiemMealDeductions(
                        breakfast = vector.get("breakfast").asBoolean(),
                        lunch = vector.get("lunch").asBoolean(),
                        dinner = vector.get("dinner").asBoolean(),
                    ),
                    employerContribution = Money.of(vector.get("employer_contribution").asText()),
                )
                val expected = Money.of(vector.get("expected_calculated_amount").asText())
                assertEquals(expected, claimable, "vector $id")
            }
        }
    }
}
