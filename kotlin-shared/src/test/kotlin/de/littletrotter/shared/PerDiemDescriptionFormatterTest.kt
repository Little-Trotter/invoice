package de.littletrotter.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerDiemDescriptionFormatterTest {
    private val labels = PerDiemDescriptionLabels(
        arrivalDeparture = "An-/Abreise",
        fullDay = "24 Std.",
        breakfastDeduction = "− Früh.",
        lunchDeduction = "− Mittag",
        dinnerDeduction = "− Abend",
    )

    @Test
    fun `combines description day type deductions and participant like the overview`() {
        assertThat(
            formatPerDiemDescription(
                day = PerDiemDayDescriptionInput(
                    description = " Team-Reise ",
                    isArrivalDeparture = true,
                    deductions = PerDiemMealDeductions(
                        breakfast = true,
                        lunch = true,
                        dinner = true,
                    ),
                    participant = " Frau Müller ",
                    includeParticipant = true,
                ),
                labels = labels,
            ),
        ).isEqualTo("Team-Reise · An-/Abreise · − Früh. − Mittag − Abend · Frau Müller")
    }

    @Test
    fun `keeps a full day concise when optional parts are absent`() {
        assertThat(
            formatPerDiemDescription(
                day = PerDiemDayDescriptionInput(
                    description = " ",
                    isArrivalDeparture = false,
                    deductions = PerDiemMealDeductions(
                        breakfast = false,
                        lunch = false,
                        dinner = false,
                    ),
                    participant = "Max Muster",
                    includeParticipant = false,
                ),
                labels = labels,
            ),
        ).isEqualTo("24 Std.")
    }

    @Test
    fun `does not emit a blank participant separator`() {
        assertThat(
            formatPerDiemDescription(
                day = PerDiemDayDescriptionInput(
                    description = null,
                    isArrivalDeparture = true,
                    deductions = PerDiemMealDeductions(
                        breakfast = false,
                        lunch = true,
                        dinner = false,
                    ),
                    participant = " ",
                    includeParticipant = true,
                ),
                labels = labels,
            ),
        ).isEqualTo("An-/Abreise · − Mittag")
    }

    @Test
    fun `participant scope ignores blanks trims names and retains first-seen order`() {
        assertThat(distinctPerDiemParticipants(listOf(" ", null, " Frau Müller ", "Herr Klein", "Frau Müller")))
            .containsExactly("Frau Müller", "Herr Klein")
    }
}
