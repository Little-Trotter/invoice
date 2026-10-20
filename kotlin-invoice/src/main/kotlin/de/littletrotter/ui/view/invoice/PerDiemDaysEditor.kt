package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.TravelExpenseDto
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.invoice.port.inbound.TripEntryQueryPort
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.travel.port.inbound.PerDiemPreviewQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import de.littletrotter.ui.component.ActionIcons

/** PO 14.09.2026 follow-up — shared fixed width for the four symbol/checkbox columns, so a
 *  header glyph and its checkbox below always occupy the same centering box. */

// PO 14.09.2026 follow-up — the date/participant columns need the SAME fixed (not merely minimum)
// width in both buildEntriesHeader and buildEntryRow: a `min-width` lets the actual rendered
// width of a ComboBox/TextField drift from a plain header Span's, which was throwing off every
// column after it, including the symbol columns even though those matched each other exactly.
private const val TEXT_MUTED = "text-muted"
private const val ARIA_LABEL = "aria-label"
private const val TITLE_ATTR = "title"
private const val PER_DIEM_ARRIVAL_DEPARTURE_KEY = "invoiceDetail.perDiem.arrivalDeparture"
private const val PER_DIEM_BREAKFAST_KEY = "invoiceDetail.perDiem.breakfastProvided"
private const val PER_DIEM_LUNCH_KEY = "invoiceDetail.perDiem.lunchProvided"
private const val PER_DIEM_DINNER_KEY = "invoiceDetail.perDiem.dinnerProvided"

/** PO 14.09.2026 follow-up — lets tests scope a lookup to the entries table's own header row,
 *  distinguishing it from [PerDiemDaysEditor]'s symbol legend, which also carries the full label
 *  text (deliberately, unlike the header). */
internal const val PER_DIEM_ENTRIES_HEADER_CLASS = "perdiem-entries-header"

// PO 14.09.2026 follow-up — lunch/dinner lean on the PO's own framing (lunch: quick, soup-like;
// dinner: the more formal company-event meal of a business trip). Arrival/departure moved to a
// VaadinIcon (see [symbolHeaderIcon]) rather than an emoji: raw emoji glyphs render with a fixed,
// unthemed color that read too dark against a dark-mode background, whereas a VaadinIcon inherits
// `color` like any other Lumo-themed element and stays legible in both themes.
private const val SYMBOL_BREAKFAST = "🥐"
private const val SYMBOL_LUNCH = "🥣"
private const val SYMBOL_DINNER = "🍽️"

/** One per-diem entry's mutable UI state — date, its own optional participant and the four
 *  per-day deduction flags. Never itself a persisted entity: [PerDiemDaysEditor.currentCommands]
 *  turns a list of these into [PerDiemEntryCommand]s, the same shape a persisted [TravelExpenseDto]
 *  round-trips through.
 *
 *  [participant] moved here from a single shared header field (PO 13.09.2026 follow-up);
 *  a later revision (PO 14.09.2026) then split the block's own optional description back out to
 *  [PerDiemDaysEditor] itself (see its `descriptionField`), renaming this per-entry field to
 *  [participant] — the underlying domain column
 *  ([de.littletrotter.domain.reference.TravelExpenseEntry.participant]) is per-row for the same
 *  reason: multiple travelers can each carry their own entry, even on the same date. */
private class PerDiemEntry(
    var date: LocalDate?,
    var participant: String,
    var arrivalDeparture: Boolean,
    var breakfast: Boolean,
    var lunch: Boolean,
    var dinner: Boolean,
)

/** One row of [PerDiemDaysEditor.overviewRows] — [TripDialog]'s own read-only rendering of the
 *  Übersicht tab reads only this, never [PerDiemEntry] itself. [outOfRange] mirrors what the
 *  entry's own row already flags in the Bearbeiten tab: the trip period narrowed
 *  after the entry was added, so its date no longer falls inside `periodFrom..periodTo`.
 *
 *  [arrivalDeparture]/[breakfast]/[lunch]/[dinner] mirror [PerDiemEntry]'s own flags — the per-diem
 *  overview ergonomics follow-up (PO 14.09.2026) needs them to render the day type and deduction
 *  markers that explain [amount].
 *
 *  [baseRate] (per-diem-as-a-Posten follow-up, PO 14.09.2026) is the day's own base rate —
 *  [de.littletrotter.application.dto.PerDiemRateDto.baseRateFor]`(arrivalDeparture)` — the same
 *  source previewAmountFor already reads [amount] from, so the two can never disagree; `Money.ZERO`
 *  while no rate is selected, mirroring [amount]'s own fallback. */
internal data class PerDiemOverviewRow(
    val date: LocalDate?,
    val participant: String,
    val baseRate: Money,
    val amount: Money,
    val outOfRange: Boolean,
    val arrivalDeparture: Boolean,
    val breakfast: Boolean,
    val lunch: Boolean,
    val dinner: Boolean,
)

/**
 * Resolves which [PerDiemRateDto] an already-saved trip's per-diem entries were calculated with, so
 * re-opening [TripDialog] for editing pre-selects the same rate: prefers the persisted rate id,
 * then a snapshot match (country/amounts) for legacy rows without one, then an active German
 * country-wide rate. A configured German default wins among applicable German rates.
 *
 * Moved here from the deleted `PerDiemItemDialog` (still the read-DTO-only shape,
 * [TravelExpenseDto], never a domain entity).
 */
internal fun resolvePerDiemRateForEditing(
    entries: List<TravelExpenseDto>,
    availableRates: List<PerDiemRateDto>,
    defaultRate: PerDiemRateDto?,
    date: LocalDate,
): PerDiemRateDto? {
    val germanDefault = availableRates.filter { it.isApplicableFor(Country.DE, null, date) }
        .sortedWith(compareByDescending<PerDiemRateDto> { it.id == defaultRate?.id }
            .thenByDescending { it.validFrom }.thenBy { it.id })
        .firstOrNull()
    if (entries.isEmpty()) return germanDefault

    val persistedRateId = entries.firstNotNullOfOrNull { it.perDiemRateId }
    if (persistedRateId != null) {
        availableRates.firstOrNull { it.id == persistedRateId }?.let { return it }
    }

    val firstEntry = entries.first()
    val firstCountry = firstEntry.countryAlpha2?.let { runCatching { Country.fromAlpha2(it) }.getOrNull() }
    val firstFullDay = firstEntry.fullDayRateSnapshot.setScale(2, RoundingMode.HALF_UP)
    val firstArrivalDeparture = firstEntry.arrivalDepartureRateSnapshot.setScale(2, RoundingMode.HALF_UP)

    // participant (formerly description) is a free-text per-entry field, not reliably rate-related
    // (PO 13.09.2026 follow-up, later renamed) — the fallback match drops it and
    // relies on country + snapshot amounts.
    return availableRates.firstOrNull { rate ->
        rate.country == firstCountry &&
            rate.fullDayRate.setScale(2, RoundingMode.HALF_UP) == firstFullDay &&
            rate.arrivalDepartureRate.setScale(2, RoundingMode.HALF_UP) == firstArrivalDeparture
    } ?: germanDefault
}

/**
 * The "Verpflegungspauschalen" section of [TripDialog], rewritten from an earlier
 * start/end date-range generator to individually addable entries.
 *
 * A confirmed PO mockup (PO 14.09.2026) settled the final structure: ONE per-diem block
 * per trip (never one per participant) with its own optional block-level [descriptionField]
 * (shown first, before `employerContribution`/`perDiemRateCombo`),
 * and a per-entry [PerDiemEntry.participant] (the traveler, renamed from the former per-entry
 * `description`). Multiple participants can now share the same date. The former "one entry per
 * date" restriction (`usedDates`/`firstFreeDate`/date filtering in `availableDatesFor`) is gone:
 * [availableDatesFor] now simply offers the whole [tripPeriodDates], and the "+" button is
 * disabled only while [tripPeriod] itself is unset (there is then no date at all to default a new
 * entry to).
 *
 * PO 14.09.2026 follow-up — [addEntry]'s very first click (on an empty section) fills in one entry
 * per day of the whole trip period at once, not just one; every later click still adds just one
 * more, defaulting to the previous entry's own date, which is what makes entering a second/third
 * participant for an already-covered day convenient. See [addEntry]'s own KDoc.
 *
 * The date range itself is never entered here: [tripPeriod] reads [TripDialog]'s
 * own `periodFromField`/`periodToField`, the one place a trip's period is entered.
 * [TripDialog] calls [notifyTripPeriodChanged] whenever those fields change so an entry whose date
 * fell out of the new period gets flagged (icon + tooltip) rather than silently dropped — the user
 * decides whether to delete it or the period edit was a mistake.
 *
 * Employer-contribution/rate stay trip-wide fields, unchanged from the original implementation.
 *
 * The whole section (both [TripDialog] tab blocks) stays hidden until the first entry is added —
 * [PerDiemDaysEditor] itself has no notion of "hidden"; [TripDialog] reads [hasEntries] from its
 * own [onChange] callback, fired on every [render].
 *
 * The live total ([currentTotal]) goes through [perDiemCalculator]'s
 * [PerDiemPreviewQueryPort.previewClaimableAmount], the same formula [de.littletrotter.application
 * .invoice.TripService] persists, so the footer preview, this section's own subtotal and
 * [overviewRows]' per-row amounts can never drift from what a save will actually compute; `null`
 * (a test that does not wire the resolver) falls back to
 * [de.littletrotter.application.dto.PerDiemRateDto.calculateClaimableAmount] directly, the same
 * formula [PerDiemPreviewQueryPort] itself wraps.
 */
internal class PerDiemDaysEditor(
    tripEntryQueryPort: TripEntryQueryPort,
    private val perDiemCalculator: PerDiemPreviewQueryPort?,
    existingDays: List<TravelExpenseDto>,
    /** Supplies the trip's current `periodFrom..periodTo` — `null` while either end is unset or
     *  the range is inverted. Read fresh on every [render]/[notifyTripPeriodChanged] call, never
     *  cached, so it always reflects [TripDialog]'s own header fields at call time. */
    private val tripPeriod: () -> ClosedRange<LocalDate>?,
    private val onChange: () -> Unit,
    // Appended after onChange (not before): keeping onChange the last declared parameter is
    // what lets its existing trailing-lambda call sites keep compiling. See TripDialog.today's
    // own KDoc.
    private val today: LocalDate,
) : TranslationProvider {
    private val root = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    override fun getComponent(): Component = root

    private val entries = existingDays.map { day ->
        PerDiemEntry(day.travelDate, day.participant, day.isArrivalDeparture, day.breakfastDeduction, day.lunchDeduction, day.dinnerDeduction)
    }.toMutableList()

    /** The whole per-diem block's own optional description (PO mockup: "Beschreibung,
     *  Zuschuss, Satz", in that order), an own field on [PerDiemDaysEditor] itself, not part of
     *  [PerDiemEntry] — the same "one shared value denormalized onto every row" shape
     *  [employerContribution] already has. Exposed `internal` so [TripDialog] can render it inside
     *  the read-only Übersicht tab as well (see that class's own KDoc). */
    internal val descriptionField = TextField(tr("invoiceDetail.perDiem.description")).apply {
        placeholder = tr("invoiceDetail.perDiem.description.placeholder")
        setWidthFull()
        value = existingDays.firstOrNull()?.description ?: ""
    }

    private val employerContribution = NumberField(tr("invoiceDetail.perDiem.employerContribution")).apply {
        suffixComponent = Span("€")
        min = 0.0
        step = 0.1
        setWidthFull()
        value = existingDays.firstOrNull()?.employerContribution?.amount?.toDouble() ?: 0.0
    }

    private val availablePerDiemRates = tripEntryQueryPort.findAllPerDiemRates()
    private val defaultPerDiemRate = tripEntryQueryPort.findDefaultPerDiemRate()
    private val perDiemRateCombo = ComboBox<PerDiemRateDto>(tr("invoiceDetail.perDiem.rate")).apply {
        setItems(availablePerDiemRates)
        setItemLabelGenerator { "${it.country?.alpha2 ?: ""} ${it.region ?: ""} – ${MoneyFormatter.format(it.fullDayRate)}" }
        // Re-resolving by id against availablePerDiemRates (the combo's own item list) guarantees
        // the assigned value is one of its own items even when resolvePerDiemRateForEditing's
        // germanDefault fallback compares against a separately-fetched defaultPerDiemRate: without
        // it, ComboBox could fail to recognize the value as selected, and "Bitte einen
        // Verpflegungssatz auswählen" would fire on save despite a real default being configured.
        value = resolvePerDiemRateForEditing(existingDays, availablePerDiemRates, defaultPerDiemRate, today)
            ?.let { resolved -> availablePerDiemRates.firstOrNull { it.id == resolved.id } ?: resolved }
        isRequired = true
        setWidthFull()
    }

    /** Placed by [TripDialog] in the same add-button row as the trip-expense kinds — an
     *  ergonomics follow-up (PO 2026-09-13): one shared row of "+" buttons for every
     *  kind of Posten instead of a visually separate per-diem section. */
    internal val addButton = AppButtons.secondary(tr("invoiceDetail.perDiem.add")) { addEntry() }.apply {
        addThemeVariants(ButtonVariant.LUMO_SMALL)
    }
    private val entriesContainer = VerticalLayout().apply { isPadding = false; isSpacing = true; setWidthFull() }
    private val preview = Span().apply { addClassName(TEXT_MUTED) }
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMANY)

    init {
        // PO mockup order: Beschreibung, Zuschuss, Satz.
        val form = FormLayout(descriptionField, employerContribution, perDiemRateCombo).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("600px", 2))
        }
        root.add(form, entriesContainer, preview)

        descriptionField.addValueChangeListener { refreshPreview() }
        employerContribution.addValueChangeListener { refreshPreview() }
        perDiemRateCombo.addValueChangeListener { refreshPreview() }

        render()
    }

    private fun tripPeriodDates(): List<LocalDate> {
        val period = tripPeriod() ?: return emptyList()
        val totalDays = ChronoUnit.DAYS.between(period.start, period.endInclusive).toInt()
        return (0..totalDays).map { period.start.plusDays(it.toLong()) }
    }

    /** Every trip-period date is always offered, regardless of whether another entry
     *  already uses it: the confirmed PO mockup shows two different participants both dated
     *  12.03.2026, so "already used" is no longer a reason to exclude a date. Falls back to
     *  including the entry's own current date even if it has since fallen outside the trip period
     *  (defends against a narrowed period, same as before this behavior). */
    private fun availableDatesFor(entry: PerDiemEntry): List<LocalDate> {
        val period = tripPeriodDates()
        val current = entry.date
        return if (current != null && current !in period) (period + current).sorted() else period
    }

    private fun isOutOfRange(entry: PerDiemEntry): Boolean {
        val date = entry.date ?: return false
        return date !in tripPeriodDates()
    }

    /** Re-renders every entry row against the current trip period — called by [TripDialog]
     *  whenever `periodFromField`/`periodToField` change, so a narrowed period immediately flags
     *  the entries it now excludes instead of waiting for the next unrelated
     *  edit. Never deletes an entry itself; only [deleteEntry] does. */
    fun notifyTripPeriodChanged() = render()

    /** PO 14.09.2026 follow-up — the very first "+" click, on an otherwise empty section,
     *  fills in one entry per day of the whole trip period at once (a PO who set a 4.9.-14.09.
     *  trip and got back a single 04.09. entry expected all eleven days, not one). Every later
     *  click — the section already has at least one entry — still adds just one more, defaulting
     *  to the previously added entry's own date (unchanged from the earlier shape): that is
     *  what lets a second/third participant be entered for an already-covered day, or the period
     *  topped up further after a manual edit. */
    private fun addEntry() {
        if (entries.isEmpty()) {
            val dates = tripPeriodDates()
            if (dates.isEmpty()) return
            entries += dates.map { date -> newEntry(date) }
        } else {
            val date = entries.last().date ?: tripPeriod()?.start ?: return
            entries += newEntry(date)
        }
        render()
    }

    private fun newEntry(date: LocalDate) =
        PerDiemEntry(date, participant = "", arrivalDeparture = false, breakfast = false, lunch = false, dinner = false)

    private fun deleteEntry(entry: PerDiemEntry) {
        entries -= entry
        render()
    }

    private fun render() {
        entriesContainer.removeAll()
        if (entries.isNotEmpty()) {
            entriesContainer.add(buildEntriesHeader())
            entries.forEach { entry -> entriesContainer.add(buildEntryRow(entry)) }
            entriesContainer.add(buildSymbolLegend())
        }

        // The "+" button is disabled only while the trip period itself is unset (there is
        // then no date at all to default a new entry to); "every date already claimed" no longer
        // exists as a concept.
        val periodSet = tripPeriod() != null
        addButton.isEnabled = periodSet
        addButton.element.setProperty(TITLE_ATTR, if (periodSet) "" else tr("invoiceDetail.perDiem.add.disabledReason"))

        refreshPreview()
    }

    /** The four per-day deduction columns show a compact symbol instead of their full
     *  i18n text (PO mockup: "verwende Symbole" to save space) — emoji-as-icon is already this
     *  project's own convention ([de.littletrotter.ui.view.invoice.InvoiceOverviewPanel]'s
     *  `INVOICE_CATEGORY_GROUPS` uses ⚙/📢/✈ the same way). The full text moves to each symbol's
     *  own `title`/`aria-label` rather than disappearing — the checkboxes in [buildEntryRow] below
     *  already work this way. */
    private fun buildEntriesHeader(): Component =
        HorizontalLayout(
            Span(tr("invoiceDetail.perDiem.column.date")).apply { addClassName("lt-perdiem-col-date") },
            Span(tr("invoiceDetail.perDiem.entry.participant")).apply { addClassName("lt-perdiem-col-participant") },
            symbolHeaderIcon(VaadinIcon.SUITCASE, PER_DIEM_ARRIVAL_DEPARTURE_KEY),
            symbolHeader(SYMBOL_BREAKFAST, PER_DIEM_BREAKFAST_KEY),
            symbolHeader(SYMBOL_LUNCH, PER_DIEM_LUNCH_KEY),
            symbolHeader(SYMBOL_DINNER, PER_DIEM_DINNER_KEY),
        ).apply { setWidthFull(); isPadding = false; isSpacing = true; addClassNames(TEXT_MUTED, PER_DIEM_ENTRIES_HEADER_CLASS) }

    /** PO 14.09.2026 follow-up — a visible legend under the entries table spelling out what each
     *  header symbol means, since a hover-only title/aria-label is easy to miss; reuses the same
     *  i18n text the symbol's own tooltip already carries (no new keys). The arrival/departure
     *  icon has no plain-text glyph of its own (see [symbolHeaderIcon]), so its legend entry uses
     *  a small inline icon instead of an emoji character. */
    private fun buildSymbolLegend(): Component {
        val arrivalDepartureLabel = tr(PER_DIEM_ARRIVAL_DEPARTURE_KEY)
        val arrivalDepartureEntry = HorizontalLayout(
            VaadinIcon.SUITCASE.create().apply { setSize("0.9rem") },
            Span(arrivalDepartureLabel),
        ).apply { isPadding = false; isSpacing = false; addClassName("lt-gap-025") }
        val mealsText = listOf(
            SYMBOL_BREAKFAST to PER_DIEM_BREAKFAST_KEY,
            SYMBOL_LUNCH to PER_DIEM_LUNCH_KEY,
            SYMBOL_DINNER to PER_DIEM_DINNER_KEY,
        ).joinToString("   ") { (symbol, labelKey) -> "$symbol ${tr(labelKey)}" }
        return HorizontalLayout(arrivalDepartureEntry, Span("   $mealsText")).apply {
            isPadding = false; isSpacing = false
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            addClassNames(TEXT_MUTED, "fs-08")
        }
    }

    /** PO 14.09.2026 follow-up — each symbol/checkbox column gets the same fixed width and a
     *  centering flex wrapper ([symbolCell]) so the header glyph sits centered directly above its
     *  checkbox, not left-aligned against it. `flex: none` keeps the fixed width from being
     *  overridden by the parent [HorizontalLayout]'s own flex sizing. */
    private fun symbolCell(content: Component): Component =
        Div(content).apply { addClassName("lt-perdiem-col-symbol") }

    private fun symbolHeader(symbol: String, labelKey: String): Component =
        symbolCell(
            Span(symbol).apply {
                element.setAttribute(TITLE_ATTR, tr(labelKey))
                element.setAttribute(ARIA_LABEL, tr(labelKey))
            },
        )

    /** PO 14.09.2026 follow-up — arrival/departure uses a themed [VaadinIcon] instead of an emoji:
     *  a raw emoji glyph renders with a fixed, unthemed color that reads too dark in dark mode,
     *  while a VaadinIcon inherits `color` like any other Lumo-themed element. */
    private fun symbolHeaderIcon(icon: VaadinIcon, labelKey: String): Component =
        symbolCell(
            icon.create().apply {
                setSize("1rem")
                element.setAttribute(TITLE_ATTR, tr(labelKey))
                element.setAttribute(ARIA_LABEL, tr(labelKey))
            },
        )

    private fun buildEntryRow(entry: PerDiemEntry): Component {
        val dateBox = ComboBox<LocalDate>().apply {
            setItems(availableDatesFor(entry))
            setItemLabelGenerator { dateFormatter.format(it) }
            value = entry.date
            isAllowCustomValue = false
            element.setAttribute(ARIA_LABEL, tr("invoiceDetail.perDiem.date"))
            addClassName("lt-perdiem-col-date")
            addValueChangeListener { event -> entry.date = event.value; render() }
        }
        val participantField = TextField().apply {
            value = entry.participant
            placeholder = tr("invoiceDetail.perDiem.entry.participant.placeholder")
            element.setAttribute(ARIA_LABEL, tr("invoiceDetail.perDiem.entry.participant"))
            addClassName("lt-perdiem-col-participant")
            addValueChangeListener { event -> entry.participant = event.value; refreshPreview() }
        }
        val arrivalDeparture = symbolCell(checkbox(entry.arrivalDeparture, PER_DIEM_ARRIVAL_DEPARTURE_KEY) { entry.arrivalDeparture = it })
        val breakfast = symbolCell(checkbox(entry.breakfast, PER_DIEM_BREAKFAST_KEY) { entry.breakfast = it })
        val lunch = symbolCell(checkbox(entry.lunch, PER_DIEM_LUNCH_KEY) { entry.lunch = it })
        val dinner = symbolCell(checkbox(entry.dinner, PER_DIEM_DINNER_KEY) { entry.dinner = it })

        val outOfRangeIcon = if (isOutOfRange(entry)) {
            VaadinIcon.WARNING.create().apply {
                setSize("1rem")
                addClassName("lt-text-error-strong")
                element.setAttribute(TITLE_ATTR, tr("invoiceDetail.perDiem.entry.outOfRange"))
                element.setAttribute(ARIA_LABEL, tr("invoiceDetail.perDiem.entry.outOfRange"))
            }
        } else {
            null
        }
        val deleteButton = AppButtons.icon(ActionIcons.DELETE, tr("invoiceDetail.perDiem.entry.delete")) {
            deleteEntry(entry)
        }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR) }

        return HorizontalLayout(
            *listOfNotNull(
                dateBox, participantField, arrivalDeparture, breakfast, lunch, dinner, outOfRangeIcon, deleteButton,
            ).toTypedArray(),
        ).apply {
            setWidthFull(); isPadding = false; isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }
    }

    private fun checkbox(initial: Boolean, labelKey: String, onValueChange: (Boolean) -> Unit): Checkbox =
        Checkbox().apply {
            value = initial
            element.setProperty(TITLE_ATTR, tr(labelKey))
            element.setAttribute(ARIA_LABEL, tr(labelKey))
            addValueChangeListener { onValueChange(it.value); refreshPreview() }
        }

    private fun previewAmountFor(rate: PerDiemRateDto, entry: PerDiemEntry, contribution: Money): Money {
        val deductions = PerDiemMealDeductions(entry.breakfast, entry.lunch, entry.dinner)
        return perDiemCalculator?.previewClaimableAmount(
            rate,
            isArrivalDeparture = entry.arrivalDeparture,
            deductions = deductions,
            employerContribution = contribution,
        ) ?: rate.calculateClaimableAmount(
            isArrivalDeparture = entry.arrivalDeparture,
            deductions = deductions,
            employerContribution = contribution,
        )
    }

    private fun contributionAmount(): Money = Money.of(BigDecimal.valueOf(employerContribution.value ?: 0.0))

    /** [TripDialog] toggles both its per-diem [de.littletrotter.ui.component.Section] blocks'
     *  `isVisible` on this — the section only appears once the first entry is added (PO
     *  13.09.2026 follow-up). */
    fun hasEntries(): Boolean = entries.isNotEmpty()

    /** The live claimable total across every entry — same formula [currentCommands] will be
     *  persisted with (see this class's own KDoc). `Money.ZERO` while no rate is selected. */
    fun currentTotal(): Money {
        val rate = perDiemRateCombo.value ?: return Money.ZERO
        val contribution = contributionAmount()
        return entries.fold(Money.ZERO) { acc, entry -> acc + previewAmountFor(rate, entry, contribution) }
    }

    /** [TripDialog]'s own read model for the Übersicht tab's per-diem grid — sorted by date (nulls
     *  last, though every entry added through [addEntry] always carries one) so the overview
     *  reads chronologically regardless of the order entries were added in. */
    fun overviewRows(): List<PerDiemOverviewRow> {
        val rate = perDiemRateCombo.value
        val contribution = contributionAmount()
        return entries.sortedWith(compareBy(nullsLast()) { it.date }).map { entry ->
            val amount = rate?.let { previewAmountFor(it, entry, contribution) } ?: Money.ZERO
            val baseRate = rate?.baseRateFor(entry.arrivalDeparture) ?: Money.ZERO
            PerDiemOverviewRow(
                entry.date,
                entry.participant,
                baseRate,
                amount,
                isOutOfRange(entry),
                entry.arrivalDeparture,
                entry.breakfast,
                entry.lunch,
                entry.dinner,
            )
        }
    }

    private fun refreshPreview() {
        if (entries.isEmpty()) {
            preview.text = tr("invoiceDetail.perDiem.preview.nodata")
            onChange()
            return
        }
        val rate = perDiemRateCombo.value
        if (rate == null) {
            preview.text = tr("invoiceDetail.perDiem.error.rateRequired")
            onChange()
            return
        }
        preview.text = tr("invoiceDetail.perDiem.preview.value", MoneyFormatter.format(currentTotal()))
        onChange()
    }

    /** Optional descriptions and participant names never prevent adding or saving a day. */
    fun isValid(): Boolean = entries.isEmpty() || perDiemRateCombo.value?.id != null

    /** The [PerDiemEntryCommand] list [TripDialog] hands to [de.littletrotter.application.invoice
     *  .TripCommand.perDiemDays] — empty when no entry exists (a trip with no per-diem section at
     *  all, README border case 1). An entry whose date the user managed to leave unset (never
     *  possible through this editor's own UI, since [ComboBox] never offers a clear option here)
     *  is defensively skipped rather than crashing on a `null` date. */
    fun currentCommands(): List<PerDiemEntryCommand> {
        val rate = perDiemRateCombo.value ?: return emptyList()
        val rateId = rate.id ?: return emptyList()
        val contribution = contributionAmount()
        val description = descriptionField.value.trim()
        return entries.mapNotNull { entry ->
            val date = entry.date ?: return@mapNotNull null
            PerDiemEntryCommand(
                date = date,
                isArrivalDeparture = entry.arrivalDeparture,
                breakfast = entry.breakfast,
                lunch = entry.lunch,
                dinner = entry.dinner,
                description = description,
                participant = entry.participant.trim(),
                employerContribution = contribution,
                perDiemRateId = rateId,
            )
        }
    }
}
