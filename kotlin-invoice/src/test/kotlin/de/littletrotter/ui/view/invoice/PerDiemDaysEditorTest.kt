package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.dto.TravelExpenseDto
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.travel.PerDiemCalculator
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import de.littletrotter.test.FIXED_TODAY

/** Moved from the deleted `InvoiceDetailViewPerDiemDialogsTest` alongside
 *  `resolvePerDiemRateForEditing` itself (now on [PerDiemDaysEditor]). */
class PerDiemDaysEditorTest {

    @Test
    fun `resolvePerDiemRateForEditing maps persisted rate id to available rate instance`() {
        val entry = TravelExpenseDto(
            id = 1L,
            invoiceItemId = 1L,
            travelDate = null,
            countryAlpha2 = Country.FR.alpha2,
            isArrivalDeparture = false,
            breakfastDeduction = false,
            lunchDeduction = false,
            dinnerDeduction = false,
            calculatedAmount = Money.ZERO,
            perDiemRateId = 12L,
            participant = "Berlin",
            fullDayRateSnapshot = Money.of("28.00"),
            arrivalDepartureRateSnapshot = Money.of("14.00"),
        )
        val availableRate = PerDiemRateDto(
            id = 12L,
            country = Country.FR,
            region = "Berlin",
            fullDayRate = Money.of("28.00"),
            arrivalDepartureRate = Money.of("14.00"),
        )
        val defaultRate = PerDiemRateDto(id = 99L)

        val resolved = resolvePerDiemRateForEditing(
            entries = listOf(entry),
            availableRates = listOf(availableRate),
            defaultRate = defaultRate,
            date = LocalDate.of(2026, 6, 15),
        )

        assertSame(availableRate, resolved)
    }

    @Test
    fun `resolvePerDiemRateForEditing falls back to snapshot match when rate id is missing`() {
        val entry = TravelExpenseDto(
            id = 1L,
            invoiceItemId = 1L,
            travelDate = null,
            countryAlpha2 = Country.DE.alpha2,
            isArrivalDeparture = false,
            breakfastDeduction = false,
            lunchDeduction = false,
            dinnerDeduction = false,
            calculatedAmount = Money.ZERO,
            perDiemRateId = null,
            participant = "Munich",
            fullDayRateSnapshot = Money.of("47.0"),
            arrivalDepartureRateSnapshot = Money.of("32.00"),
        )
        val availableRate = PerDiemRateDto(
            id = 77L,
            country = Country.DE,
            region = "Munich",
            fullDayRate = Money.of("47.00"),
            arrivalDepartureRate = Money.of("32.0"),
        )
        val defaultRate = PerDiemRateDto(id = 99L)

        val resolved = resolvePerDiemRateForEditing(
            entries = listOf(entry),
            availableRates = listOf(availableRate),
            defaultRate = defaultRate,
            date = LocalDate.of(2026, 6, 15),
        )

        assertSame(availableRate, resolved)
    }

    @Test
    fun `resolvePerDiemRateForEditing returns German rate when no persisted rate can be resolved`() {
        val entry = TravelExpenseDto(
            id = 1L,
            invoiceItemId = 1L,
            travelDate = null,
            countryAlpha2 = Country.FR.alpha2,
            isArrivalDeparture = false,
            breakfastDeduction = false,
            lunchDeduction = false,
            dinnerDeduction = false,
            calculatedAmount = Money.ZERO,
            perDiemRateId = null,
            participant = "Paris",
            fullDayRateSnapshot = Money.of("50.00"),
            arrivalDepartureRateSnapshot = Money.of("35.00"),
        )
        val availableRate = PerDiemRateDto(
            id = 77L,
            country = Country.DE,
            region = null,
            fullDayRate = Money.of("28.00"),
            arrivalDepartureRate = Money.of("14.00"),
        )
        val defaultRate = PerDiemRateDto(id = 99L)

        val resolved = resolvePerDiemRateForEditing(
            entries = listOf(entry),
            availableRates = listOf(availableRate),
            defaultRate = defaultRate,
            date = LocalDate.of(2026, 6, 15),
        )

        assertSame(availableRate, resolved)
    }

    @Test
    fun `new trips default to an applicable German rate without a configured default`() {
        val date = LocalDate.of(2026, 9, 15)
        val german = PerDiemRateDto(id = 1L, country = Country.DE, validFrom = date, validTo = date)
        val foreign = PerDiemRateDto(id = 2L, country = Country.FR, isDefault = true)
        val expired = PerDiemRateDto(id = 3L, country = Country.DE, validTo = date.minusDays(1))
        val future = PerDiemRateDto(id = 4L, country = Country.DE, validFrom = date.plusDays(1))
        val inactive = PerDiemRateDto(id = 5L, country = Country.DE, isActive = false)
        val regional = PerDiemRateDto(id = 6L, country = Country.DE, region = "Berlin")
        val rates = listOf(foreign, expired, future, inactive, regional, german)

        assertSame(german, resolvePerDiemRateForEditing(emptyList(), rates, null, date))
        assertSame(german, resolvePerDiemRateForEditing(emptyList(), rates, foreign, date))
        assertEquals(null, resolvePerDiemRateForEditing(emptyList(), listOf(foreign, expired, future, inactive, regional), foreign, date))
    }

    @Test
    fun `configured German default wins and otherwise newest applicable German rate wins`() {
        val date = LocalDate.of(2026, 9, 15)
        val older = PerDiemRateDto(id = 1L, country = Country.DE, validFrom = date.minusYears(1))
        val newer = PerDiemRateDto(id = 2L, country = Country.DE, validFrom = date)
        assertSame(older, resolvePerDiemRateForEditing(emptyList(), listOf(newer, older), older, date))
        assertSame(newer, resolvePerDiemRateForEditing(emptyList(), listOf(older, newer), null, date))
    }

    // `applyPerDiemEntriesToItem`/`recalcPerDiemItem` moved from this file into
    // InvoiceLifecycleService.addItem/updateItem's PER_DIEM branch (the UI no longer builds or
    // mutates an InvoiceItem for writes, only InvoiceItemCommand); their behavior is now covered
    // there (InvoiceLifecycleServiceTest's PER_DIEM addItem/updateItem tests).

    /**
     * The component's own behaviour (individually addable entries restricted to the
     * trip period, one at a time via "+", instead of a start/end date range that regenerated a
     * day row per calendar day): needs a live Vaadin environment (its fields are real
     * `ComboBox`/`Button` components), so nested here the same way the pure-function tests above
     * stay JVM-only.
     */
    @Nested
    inner class AddableEntries {
        private val perDiemRateQueries: PerDiemRateQueryPort = mock()
        private val perDiemCalculator: PerDiemCalculator = mock()

        @BeforeEach
        fun setup() {
            MockVaadin.setup()
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(emptyList())
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
        }

        @AfterEach
        fun teardown() {
            try { MockVaadin.tearDown() } catch (_: Exception) { }
        }

        private val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))

        private fun editor(
            existingDays: List<TravelExpenseDto> = emptyList(),
            period: ClosedRange<LocalDate>? = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 5),
            onChange: () -> Unit = {},
        ) = PerDiemDaysEditor(TripEntryQueries(mock(), perDiemRateQueries), perDiemCalculator, existingDays, { period }, onChange, today = FIXED_TODAY)

        private fun flatten(component: com.vaadin.flow.component.Component): List<com.vaadin.flow.component.Component> =
            listOf(component) + component.children.toList().flatMap { flatten(it) }

        // The add button now lives in TripDialog's shared button row, not editor.getComponent()'s
        // own tree (ergonomics follow-up) — read the exposed property directly.
        private fun addButton(editor: PerDiemDaysEditor): Button = editor.addButton

        private fun entryDateBoxes(editor: PerDiemDaysEditor): List<ComboBox<LocalDate>> =
            flatten(editor.getComponent()).filterIsInstance<ComboBox<*>>()
                .filter { it.element.getAttribute("aria-label") == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.perDiem.date") }
                .map { it.requireAs<ComboBox<LocalDate>>() }

        private fun entryParticipantFields(editor: PerDiemDaysEditor): List<TextField> =
            flatten(editor.getComponent()).filterIsInstance<TextField>()
                .filter { it.element.getAttribute("aria-label") == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.perDiem.entry.participant") }

        @Test
        fun `a brand-new trip starts with zero entries`() {
            val editor = editor()

            assertTrue(editor.currentCommands().isEmpty())
            assertEquals(Money.ZERO, editor.currentTotal())
            assertTrue(editor.isValid(), "no entries at all is a valid (empty) section")
            assertFalse(editor.hasEntries(), "TripDialog hides the whole section until the first entry is added")
        }

        @Test
        fun `the rate combo pre-selects a usable value even when the default lookup returns a different object instance than the item list`() {
            // "bitte Satz auswählen" was firing despite a real default being configured: two
            // separate JPA queries (findDefault vs findAll -- exactly how the real repository
            // calls work) returning two DIFFERENT object instances for the same DB row must not
            // leave the combo looking unselected, even though PerDiemRateDto's own value equality
            // already covers the common case.
            val rateInList = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
            val rateFromDefaultLookup = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rateInList))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rateFromDefaultLookup)
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))

            addButton(editor).click()
            entryParticipantFields(editor).single().value = "Kundentermin"
            editor.descriptionField.value = "Team-Reise Hamburg"

            assertTrue(editor.isValid(), "the pre-selected default rate must count as selected")
            assertEquals(1, editor.currentCommands().size, "currentCommands must resolve a non-null rate id")
        }

        @Test
        fun `the plus button fills in one entry per day of the whole trip period on the first click`() {
            // A user who set a multi-day trip and clicked "+" once expected an entry for every
            // day, not just one; the default() period here is 6/1..6/5 (5 days).
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor()

            addButton(editor).click()

            val commands = editor.currentCommands()
            assertEquals(
                listOf(
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3),
                    LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 5),
                ),
                commands.map { it.date }.sorted(),
                "one entry per day of the trip period, not just the first day",
            )
            assertTrue(editor.hasEntries(), "TripDialog reveals the section once the first entry exists")
        }

        @Test
        fun `a further click after the first only adds one more entry, defaulting to the last entry's date`() {
            // "es muss möglich sein mehr Verpflegungspauschalen anzulegen": the bulk-fill only
            // happens once, on an empty section; every later click
            // goes back to adding exactly one more entry (e.g. a second participant on a day the
            // bulk-fill already covered).
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor()

            addButton(editor).click() // bulk-fills 6/1..6/5 (5 entries)
            addButton(editor).click() // one more, defaulting to the last entry's own date (6/5)

            val commands = editor.currentCommands()
            assertEquals(6, commands.size)
            assertEquals(2, commands.count { it.date == LocalDate.of(2026, 6, 5) }, "the extra entry defaults to the previous entry's date")
        }

        @Test
        fun `two clicks on a one-day trip add two entries both on that date, ready for two participants on one day`() {
            // The confirmed PO mockup shows two different participants both dated
            // 12.03.2026; defaulting every new entry to the previous one's own date (rather than
            // "the next free date", which no longer exists as a concept) is what makes entering
            // them one after another convenient. A one-day period keeps the bulk-fill click (PO
            // 14.09.2026 follow-up) down to a single entry, isolating this from that behavior.
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))

            addButton(editor).click()
            addButton(editor).click()

            val commands = editor.currentCommands()
            assertEquals(2, commands.size)
            assertTrue(commands.all { it.date == LocalDate.of(2026, 6, 1) }, "a second '+' click defaults to the same date as the previous entry")
        }

        @Test
        fun `two non-adjacent entries are both valid once each has its own participant and the block has a description`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            // A one-day seed period keeps the first click's bulk-fill to a single entry, matching
            // this test's own "add one, then add another" setup; the
            // period only gates which dates the pickers offer, not what date currentCommands()
            // ends up with once set explicitly below.
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))
            addButton(editor).click()
            val firstDateBox = entryDateBoxes(editor).first()
            firstDateBox.value = LocalDate.of(2026, 6, 1)

            addButton(editor).click()
            // second entry defaults to the first entry's own date; move it to a non-adjacent date.
            val secondDateBox = entryDateBoxes(editor).last()
            secondDateBox.value = LocalDate.of(2026, 6, 5)

            entryParticipantFields(editor)[0].value = "Kundentermin Vormittag"
            entryParticipantFields(editor)[1].value = "Kundentermin Nachmittag"
            editor.descriptionField.value = "Team-Reise Hamburg"

            val commands = editor.currentCommands().sortedBy { it.date }
            assertEquals(listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5)), commands.map { it.date })
            assertTrue(editor.isValid())
            assertEquals(
                setOf("Kundentermin Vormittag", "Kundentermin Nachmittag"),
                commands.map { it.participant }.toSet(),
                "each entry keeps its own, independently editable participant",
            )
            assertTrue(
                commands.all { it.description == "Team-Reise Hamburg" },
                "the block's own description is denormalized identically onto every entry",
            )
        }

        @Test
        fun `entries without participant or description remain valid when another day is added`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor()

            addButton(editor).click()

            assertTrue(editor.isValid())
            addButton(editor).click()
            assertTrue(editor.isValid())
            assertEquals(6, editor.currentCommands().size)
            assertTrue(editor.currentCommands().all { it.description.isEmpty() && it.participant.isEmpty() })
        }

        @Test
        fun `a blank block description is optional with a named participant`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            // One-day period -> the first click's bulk-fill yields exactly one entry, matching
            // this test's own single-participant setup.
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))
            addButton(editor).click()
            entryParticipantFields(editor).single().value = "Kundentermin"

            assertTrue(editor.isValid(), "the block description is optional")

            editor.descriptionField.value = "Team-Reise Hamburg"

            assertTrue(editor.isValid(), "now valid once the block description is filled in too")
        }

        @Test
        fun `a date picker offers the full trip period, including a date already used by another entry`() {
            // The former "one entry per date" restriction is gone: the confirmed PO
            // mockup shows two participants sharing one date, so a date must never be excluded
            // just because another entry already uses it.
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor()
            addButton(editor).click() // bulk-fills 6/1..6/5 (5 entries)
            addButton(editor).click() // one more, defaulting to the last entry's own date (6/5)

            val secondDateBoxItems = entryDateBoxes(editor).last().genericDataView.items.toList()

            assertTrue(secondDateBoxItems.contains(LocalDate.of(2026, 6, 1)), "a date already used by another entry must still be offered")
            assertEquals(5, secondDateBoxItems.size, "the whole trip period (6/1..6/5) is offered, never filtered")
        }

        @Test
        fun `two entries on the same date with different participants are both retained and priced independently`() {
            // The exact scenario from the confirmed PO mockup: Frau Müller and Herr Klein both
            // dated 12.03.2026. A one-day seed period keeps the first click's bulk-fill (PO
            // 14.09.2026 follow-up) down to a single entry, isolating this from that behavior.
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))
            addButton(editor).click() // defaults to 6/1
            addButton(editor).click() // defaults to 6/1 too
            entryParticipantFields(editor)[0].value = "Frau Müller"
            entryParticipantFields(editor)[1].value = "Herr Klein"
            editor.descriptionField.value = "Team-Reise Berlin"

            val commands = editor.currentCommands()

            assertEquals(2, commands.size, "both entries must be retained, not merged or deduplicated by date")
            assertTrue(commands.all { it.date == LocalDate.of(2026, 6, 1) })
            assertEquals(setOf("Frau Müller", "Herr Klein"), commands.map { it.participant }.toSet())
            assertTrue(editor.isValid())
        }

        @Test
        fun `the add button stays enabled no matter how many entries already exist, as long as a trip period is set`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 2))

            addButton(editor).click() // bulk-fills 6/1..6/2 (2 entries)
            addButton(editor).click() // +1
            addButton(editor).click() // +1

            assertTrue(addButton(editor).isEnabled, "there is no longer an 'every date claimed' concept")
            assertEquals(4, editor.currentCommands().size)
        }

        @Test
        fun `the add button is disabled while no trip period is set`() {
            val editor = editor(period = null)

            assertFalse(addButton(editor).isEnabled, "there is no date at all to default a new entry to")
        }

        @Test
        fun `entries without a selected rate are invalid`() {
            val editor = editor()
            addButton(editor).click()

            assertFalse(editor.isValid(), "an entry with no per-diem rate selected must not validate")
            assertTrue(editor.currentCommands().isEmpty(), "no resolvable rate id -> no commands built")
        }

        @Test
        fun `narrowing the trip period after entries exist flags out-of-range entries instead of deleting them`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            // One-day seed period keeps the first click's bulk-fill down to a single entry, so
            // this test can build its own two-entry (6/1, 6/2) setup exactly
            // as before.
            var period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1)
            val editor = PerDiemDaysEditor(TripEntryQueries(mock(), perDiemRateQueries), perDiemCalculator, emptyList(), { period }, onChange = {}, today = FIXED_TODAY)
            addButton(editor).click() // defaults to 6/1
            addButton(editor).click() // defaults to 6/1 too; move it to 6/2
            entryDateBoxes(editor).last().value = LocalDate.of(2026, 6, 2)

            period = LocalDate.of(2026, 6, 2)..LocalDate.of(2026, 6, 5)
            editor.notifyTripPeriodChanged()

            assertEquals(2, editor.currentCommands().size, "narrowing must never silently delete an entry")
            val rows = editor.overviewRows()
            assertTrue(rows.single { it.date == LocalDate.of(2026, 6, 1) }.outOfRange)
            assertFalse(rows.single { it.date == LocalDate.of(2026, 6, 2) }.outOfRange)
        }

        @Test
        fun `overviewRows carries each entry's own day-type and meal flags for the Übersicht tab`() {
            val existingDays = listOf(
                TravelExpenseDto(
                    id = 1L, invoiceItemId = 1L, travelDate = LocalDate.of(2026, 6, 1), countryAlpha2 = "DE",
                    isArrivalDeparture = false, breakfastDeduction = true, lunchDeduction = false, dinnerDeduction = true,
                    calculatedAmount = Money.ZERO, perDiemRateId = 5L, participant = "Max Muster",
                    fullDayRateSnapshot = Money.of("28.00"), arrivalDepartureRateSnapshot = Money.of("14.00"),
                    description = "Team-Reise",
                ),
            )
            val editor = editor(existingDays = existingDays)

            val row = editor.overviewRows().single()

            assertFalse(row.arrivalDeparture)
            assertTrue(row.breakfast)
            assertFalse(row.lunch)
            assertTrue(row.dinner)
        }

        @Test
        fun `overviewRows carries the day's own base rate, the same source previewAmountFor reads from`() {
            val perDiemRate = PerDiemRateDto(
                id = 5L,
                country = Country.DE,
                fullDayRate = Money.of("28.00"),
                arrivalDepartureRate = Money.of("14.00"),
            )
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(perDiemRate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(perDiemRate)
            val existingDays = listOf(
                TravelExpenseDto(
                    id = 1L, invoiceItemId = 1L, travelDate = LocalDate.of(2026, 6, 1), countryAlpha2 = "DE",
                    isArrivalDeparture = true, breakfastDeduction = false, lunchDeduction = false, dinnerDeduction = false,
                    calculatedAmount = Money.ZERO, perDiemRateId = 5L, participant = "Max Muster",
                    fullDayRateSnapshot = Money.of("28.00"), arrivalDepartureRateSnapshot = Money.of("14.00"),
                    description = "Team-Reise",
                ),
                TravelExpenseDto(
                    id = 2L, invoiceItemId = 1L, travelDate = LocalDate.of(2026, 6, 2), countryAlpha2 = "DE",
                    isArrivalDeparture = false, breakfastDeduction = false, lunchDeduction = false, dinnerDeduction = false,
                    calculatedAmount = Money.ZERO, perDiemRateId = 5L, participant = "Max Muster",
                    fullDayRateSnapshot = Money.of("28.00"), arrivalDepartureRateSnapshot = Money.of("14.00"),
                    description = "Team-Reise",
                ),
            )
            val editor = editor(existingDays = existingDays)

            val rows = editor.overviewRows().sortedBy { it.date }

            assertEquals(Money.of("14.00"), rows[0].baseRate, "an arrival/departure day's own base rate")
            assertEquals(Money.of("28.00"), rows[1].baseRate, "a full day's own base rate")
        }

        @Test
        fun `a one-entry trip with a selected rate produces exactly one command and a matching preview total`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            whenever(
                perDiemCalculator.previewClaimableAmount(
                    rate = rate, isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), employerContribution = Money.ZERO,
                ),
            ).thenReturn(Money.of("28.00"))
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))

            addButton(editor).click()
            entryParticipantFields(editor).single().value = "Kundentermin"
            editor.descriptionField.value = "Team-Reise Hamburg"

            val commands = editor.currentCommands()
            assertEquals(1, commands.size)
            assertEquals(LocalDate.of(2026, 6, 1), commands[0].date)
            assertEquals(5L, commands[0].perDiemRateId)
            assertEquals("Kundentermin", commands[0].participant)
            assertEquals("Team-Reise Hamburg", commands[0].description)
            assertEquals(Money.of("28.00"), editor.currentTotal())
            assertTrue(editor.isValid())
        }

        @Test
        fun `existing persisted entries load without needing the plus button`() {
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val existing = TravelExpenseDto(
                id = 1L, invoiceItemId = 1L, travelDate = LocalDate.of(2026, 6, 1),
                countryAlpha2 = "DE", isArrivalDeparture = true, breakfastDeduction = false,
                lunchDeduction = false, dinnerDeduction = false, calculatedAmount = Money.of("28.00"),
                perDiemRateId = 5L, fullDayRateSnapshot = Money.of("28.00"), arrivalDepartureRateSnapshot = Money.of("28.00"),
                description = "Team-Reise Berlin",
                participant = "Kundentermin Berlin",
            )
            val editor = editor(existingDays = listOf(existing))

            assertEquals("Team-Reise Berlin", editor.descriptionField.value, "the block description field pre-fills from the first existing day")
            val commands = editor.currentCommands()
            assertEquals(1, commands.size)
            assertEquals(LocalDate.of(2026, 6, 1), commands[0].date)
            assertTrue(commands[0].isArrivalDeparture)
            assertEquals("Kundentermin Berlin", commands[0].participant, "a persisted entry's own participant loads into its per-entry field")
            assertEquals("Team-Reise Berlin", commands[0].description)
        }

        @Test
        fun `a legend under the entries table spells out what each header symbol means`() {
            // A hover-only tooltip is easy to miss.
            whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
            whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
            val editor = editor(period = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 1))

            addButton(editor).click()

            // The legend spreads its text across a few Spans (an icon+label pair for
            // arrival/departure, one combined Span for the three meals) -- join everything under
            // the legend row rather than assume it is a single Span.
            val legendText = flatten(editor.getComponent())
                .filterIsInstance<com.vaadin.flow.component.html.Span>()
                .joinToString(" ") { it.text ?: "" }

            listOf(
                "invoiceDetail.perDiem.arrivalDeparture",
                "invoiceDetail.perDiem.breakfastProvided",
                "invoiceDetail.perDiem.lunchProvided",
                "invoiceDetail.perDiem.dinnerProvided",
            ).forEach { key ->
                assertTrue(legendText.contains(de.littletrotter.ui.i18n.UiTranslations.tr(key)), "legend missing '$key'")
            }
        }
    }
}
