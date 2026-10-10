package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.NumberField
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.dto.TravelExpenseDto
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import de.littletrotter.test.FIXED_TODAY

/**
 * [PerDiemDaysEditor] day toggles and rate edge cases: arrival/departure and meal
 * deductions flow into the preview and overview, a cleared employer contribution counts as zero,
 * entries without a date are tolerated (never crash, never saved), and a rate without id yields no
 * commands. Also the snapshot fallback of [resolvePerDiemRateForEditing] for unknown ids and
 * countries.
 */
class PerDiemDaysEditorBoundaryKaribuTest {

    private val perDiemRateQueries: PerDiemRateQueryPort = mock()
    private var changes = 0
    private var period: ClosedRange<LocalDate>? = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 2)

    private val rate = PerDiemRateDto(
        id = 5L,
        country = Country.DE,
        fullDayRate = Money.of("28.00"),
        arrivalDepartureRate = Money.of("14.00"),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun day(date: LocalDate?, participant: String = "Anna", rateId: Long? = 5L, alpha2: String? = "DE", fullDay: String = "28.00", arrival: String = "14.00") =
        TravelExpenseDto(
            id = 1L, invoiceItemId = 1L, travelDate = date, countryAlpha2 = alpha2,
            isArrivalDeparture = false, breakfastDeduction = false, lunchDeduction = false, dinnerDeduction = false,
            calculatedAmount = Money.ZERO, perDiemRateId = rateId, participant = participant,
            fullDayRateSnapshot = Money.of(fullDay), arrivalDepartureRateSnapshot = Money.of(arrival),
        )

    private fun editor(existing: List<TravelExpenseDto> = emptyList()) =
        PerDiemDaysEditor(TripEntryQueries(mock(), perDiemRateQueries), null, existing, { period }, onChange = { changes++ }, today = FIXED_TODAY)

    private fun all(root: Component): List<Component> = listOf(root) + root.children.toList().flatMap { all(it) }

    private fun PerDiemDaysEditor.checkboxes(labelKey: String) =
        all(getComponent()).filterIsInstance<Checkbox>().filter { it.element.getAttribute("aria-label") == UiTranslations.tr(labelKey) }

    private fun PerDiemDaysEditor.contribution() =
        all(getComponent()).filterIsInstance<NumberField>().single { it.label == UiTranslations.tr("invoiceDetail.perDiem.employerContribution") }

    private fun PerDiemDaysEditor.rateCombo(): ComboBox<PerDiemRateDto> =
        all(getComponent()).filterIsInstance<ComboBox<*>>().single { it.label == UiTranslations.tr("invoiceDetail.perDiem.rate") }.requireAs()

    @Test
    fun `happy path - arrival day and meal toggles lower the claimable amount and show in the overview`() {
        val ed = editor(listOf(day(LocalDate.of(2026, 6, 1))))
        val fullDay = ed.currentTotal()
        val before = changes

        ed.checkboxes("invoiceDetail.perDiem.arrivalDeparture").single().value = true
        val arrivalDay = ed.currentTotal()
        ed.checkboxes("invoiceDetail.perDiem.breakfastProvided").single().value = true
        ed.checkboxes("invoiceDetail.perDiem.lunchProvided").single().value = true
        ed.checkboxes("invoiceDetail.perDiem.dinnerProvided").single().value = true

        assertTrue(arrivalDay.amount < fullDay.amount, "$arrivalDay < $fullDay")
        assertTrue(ed.currentTotal().amount <= arrivalDay.amount)
        val row = ed.overviewRows().single()
        assertTrue(row.arrivalDeparture && row.breakfast && row.lunch && row.dinner)
        assertEquals(Money.of("14.00"), row.baseRate)
        assertEquals(4, changes - before)
    }

    @Test
    fun `boundary - a cleared employer contribution counts as zero and a rate change refreshes the preview`() {
        val ed = editor(listOf(day(LocalDate.of(2026, 6, 1))))
        val withoutContribution = ed.currentTotal()

        ed.contribution().value = 5.0
        assertTrue(ed.currentTotal().amount < withoutContribution.amount, "the contribution reduces the claim")
        ed.contribution().value = null
        assertEquals(withoutContribution, ed.currentTotal())

        ed.rateCombo().value = null
        assertEquals(Money.ZERO, ed.currentTotal())
        assertFalse(ed.isValid())
        assertEquals(emptyList<Any>(), ed.currentCommands())
        assertTrue(changes >= 3)
    }

    @Test
    fun `boundary - an entry without date is never out of range and never saved`() {
        val ed = editor(listOf(day(null), day(LocalDate.of(2026, 6, 2), participant = "Ben")))

        assertFalse(ed.overviewRows().last().outOfRange)
        assertNull(ed.overviewRows().last().date)
        assertEquals(listOf(LocalDate.of(2026, 6, 2)), ed.currentCommands().map { it.date })
    }

    @Test
    fun `boundary - adding after a dateless entry uses the trip start, without a period nothing is added`() {
        val ed = editor(listOf(day(null)))

        ed.addButton.click()
        assertEquals(LocalDate.of(2026, 6, 1), ed.overviewRows().first().date)

        val single = editor(listOf(day(null)))
        val empty = editor()
        period = null
        single.addButton.click()
        assertEquals(1, single.overviewRows().size)

        empty.addButton.click()
        assertFalse(empty.hasEntries(), "no trip period, no generated days")
    }

    @Test
    fun `boundary - a selected rate without id yields no commands`() {
        val unsaved = PerDiemRateDto(country = null, region = null, fullDayRate = Money.of("20.00"))
        val ed = editor(listOf(day(LocalDate.of(2026, 6, 1))))

        ed.rateCombo().value = unsaved

        assertEquals(emptyList<Any>(), ed.currentCommands())
        assertTrue(ed.rateCombo().itemLabelGenerator.apply(unsaved).contains("20"))
    }

    @Test
    fun `boundary - unknown persisted rate ids and unreadable countries fall back to the German default`() {
        val german = PerDiemRateDto(id = 1L, country = Country.DE, fullDayRate = Money.of("28.00"), arrivalDepartureRate = Money.of("14.00"))
        val france = PerDiemRateDto(id = 2L, country = Country.FR, fullDayRate = Money.of("50.00"), arrivalDepartureRate = Money.of("35.00"))
        val rates = listOf(german, france)
        val date = LocalDate.now()

        assertSame(german, resolvePerDiemRateForEditing(listOf(day(null, rateId = 999L, alpha2 = "XX")), rates, null, date))
        assertSame(german, resolvePerDiemRateForEditing(listOf(day(null, rateId = null, alpha2 = null)), rates, null, date))
        assertSame(german, resolvePerDiemRateForEditing(listOf(day(null, rateId = null, alpha2 = "FR", fullDay = "51.00", arrival = "35.00")), rates, null, date))
        assertSame(german, resolvePerDiemRateForEditing(listOf(day(null, rateId = null, alpha2 = "FR", fullDay = "50.00", arrival = "30.00")), rates, null, date))
        assertSame(france, resolvePerDiemRateForEditing(listOf(day(null, rateId = null, alpha2 = "FR", fullDay = "50.00", arrival = "35.00")), rates, null, date))
    }
}
