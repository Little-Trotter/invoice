package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Hr
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.export.TripWorkbookExportPort
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.port.inbound.TripEntryQueryPort
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.travel.HotelRateResolver
import de.littletrotter.application.travel.MileageRateResolver
import de.littletrotter.application.travel.PerDiemCalculator
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.TripTaxContext
import de.littletrotter.domain.invoice.selectTripTaxRate
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.PdfTextValidation
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.Section
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.ui.component.ActionIcons

private const val TEXT_MUTED = "text-muted"
private const val CSS_CLASS_FS_08 = "fs-08"

/** [TripDialog]'s save-path callbacks — bundled into one constructor parameter purely to keep
 *  that constructor under detekt's `LongParameterList` threshold (see its own KDoc); the two are
 *  never meaningful independently of each other, unlike this dialog's other collaborators. */
internal data class TripSaveCallbacks(
    val onSaveNew: (TripCommand) -> Long?,
    val onUpdate: (TripCommand) -> Unit,
)

/**
 * The one trip dialog for a whole [de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP]
 * position — header, any number of expense lines, per-diem days (gated on `PER_DIEM_UI`),
 * receipts and the fixed-price/detailed switches — as one save. No business rule lives here
 * (DDD): amount derivation ([TripExpenseRow.netAmount], the same [InvoiceItem.calculateAmounts]
 * formula every other item dialog's preview uses) and the footer's calculated-net total mirror
 * exactly what [de.littletrotter.domain.invoice.InvoiceItem.calculatedTripNet] sums server-side —
 * expense net plus the live per-diem total ([PerDiemDaysEditor.currentTotal], itself routed
 * through [PerDiemCalculator]) — but every actual write (tax resolution, orphan removal,
 * validation) happens in [de.littletrotter.application.invoice.TripService] /
 * [de.littletrotter.application.invoice.TripCommandValidator] after this dialog submits its
 * [TripCommand]. Each collaborator is passed straight through as its own constructor parameter
 * instead of a bag type, and the three optional rate/day resolvers default to `null` so a
 * component test can construct this dialog without supplying them while production CDI always
 * resolves the real bean — keeping the constructor under detekt's `LongParameterList` threshold
 * without regrouping the collaborators. The one exception is [TripSaveCallbacks]: adding the
 * required [today] parameter (TimeSourceArchitectureTest — a plain UI class may not read the
 * clock itself) pushed the non-defaulted count one past that threshold, and the save/update
 * callbacks are the only pair here that were never independently meaningful on their own.
 *
 * The receipt-related collaborators — [invoiceReceiptQueryPort]/[attachReceiptsUseCase]/
 * [recipientQueryPort] — follow the same "straight through, never bundled" idiom.
 */
internal class TripDialog(
    private val tripEntryQueryPort: TripEntryQueryPort,
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    private val attachReceiptsUseCase: AttachReceiptsUseCase,
    /** Needed only for the recipient's real billing-address country (hotel-rate default),
     *  never held beyond the dialog's construction. */
    recipientQueryPort: RecipientQueryPort,
    hotelRateResolver: HotelRateResolver? = null,
    mileageRateResolver: MileageRateResolver? = null,
    private val perDiemCalculator: PerDiemCalculator? = null,
    private val perDiemUiEnabled: Boolean = true,
    private val invoice: InvoiceDetailDto,
    private val existingItem: ItemDto? = null,
    /** Backs the footer's download button ([EXPORT_BUTTON_ID]); `null` (component tests
     *  that do not consider export) or a not-yet-saved trip leaves the button disabled. */
    private val tripWorkbookExport: TripWorkbookExportPort? = null,
    private val saveCallbacks: TripSaveCallbacks,
    // A plain UI class may not read the clock itself (TimeSourceArchitectureTest); the real
    // caller (TripDialogLauncher) passes TimeSource.today() explicitly.
    private val today: LocalDate,
) : TranslationProvider {

    private val onSaveNew get() = saveCallbacks.onSaveNew
    private val onUpdate get() = saveCallbacks.onUpdate

    // Spec: a sent or cancelled trip stays readable — derived once, same idiom as ReceiptDialog's
    // own `readOnly`/`canWrite` split (ReceiptDialog.kt).
    private val readOnly = invoice.isReadOnly()

    // A bare width doesn't cap to the viewport; min(vw, px) is the idiom every other
    // item dialog in this package uses (ReceiptDialog).
    private val dlg = AppDialog("", AppDialog.Size.L).apply { isCloseOnEsc = false }
    override fun getComponent(): Component = dlg

    /** The "Posten" rows (both tabs); every row mutation marks the dialog dirty and
     *  refreshes the footer. */
    private val expenses = TripExpenseRowsEditor(
        invoiceReceiptQueryPort,
        recipientQueryPort,
        TripRateResolvers(hotelRateResolver, mileageRateResolver),
        invoice,
        existingItem,
        onChange = { dirty = true; refreshFooter() },
        today = today,
    )
    private lateinit var perDiemDaysEditor: PerDiemDaysEditor
    /** The read-only per-diem block of the Übersicht tab; built only while
     *  [perDiemUiEnabled] is true (never exists otherwise). */
    private lateinit var perDiemOverview: TripPerDiemOverview
    private lateinit var perDiemEditSection: Section
    private var dirty = false

    private val titleField = TextField(tr("invoiceDetail.trip.title")).apply {
        setWidthFull(); isRequired = true; placeholder = tr("invoiceDetail.trip.title.placeholder")
        value = existingItem?.description ?: ""
    }
    private val purposeField = TextArea(tr("invoiceDetail.trip.purpose")).apply {
        setWidthFull(); minRows = 2
        value = existingItem?.itemDescription ?: ""
    }
    private val periodFromField = DatePicker(tr("invoiceDetail.trip.periodFrom")).apply {
        setWidthFull(); value = existingItem?.servicePeriodFrom
    }
    private val periodToField = DatePicker(tr("invoiceDetail.trip.periodTo")).apply {
        setWidthFull(); value = existingItem?.servicePeriodTo
    }
    private val costCenterField = TextField(tr("invoiceDetail.trip.costCenter")).apply {
        setWidthFull(); maxLength = 100
    }
    private val detailedOnInvoiceSwitch = Checkbox(tr("invoiceDetail.trip.detailedOnInvoice")).apply {
        value = existingItem?.trip?.detailedOnInvoice ?: false
    }
    private val fixedPriceSwitch = Checkbox(tr("invoiceDetail.trip.fixedPrice.toggle")).apply {
        value = existingItem?.trip?.fixedNetPrice != null
    }
    private val fixedPriceField = NumberField(tr("invoiceDetail.trip.fixedPrice.amount")).apply {
        suffixComponent = Span("€"); setWidthFull()
        isVisible = fixedPriceSwitch.value
        value = existingItem?.trip?.fixedNetPrice?.amount?.toDouble() ?: 0.0
    }
    private val inheritedTaxNote = Paragraph().apply { addClassNames(CSS_CLASS_FS_08, TEXT_MUTED, "m-0") }

    private val calculatedLine = Span().apply { addClassName("lt-amount-preview") }
    private val onInvoiceLine = Span().apply { addClassName("lt-amount-preview") }
    private val receiptsCountLabel = Span()

    fun open() {
        dlg.headerTitle = dialogTitle()

        refreshInheritedTaxNote()
        wireDirtyTracking()
        perDiemDaysEditor = buildPerDiemDaysEditor()
        // A narrowed/widened trip period must re-flag out-of-range entries and
        // recompute which dates the "+" button/date pickers still offer immediately, not only on
        // the next unrelated per-diem edit.
        periodFromField.addValueChangeListener { perDiemDaysEditor.notifyTripPeriodChanged() }
        periodToField.addValueChangeListener { perDiemDaysEditor.notifyTripPeriodChanged() }
        if (readOnly) applyReadOnlyState()

        val headerForm = buildHeaderForm()
        val switchesRow = buildSwitchesRow()

        val expensesSection = buildExpensesSection()
        expenses.render()
        refreshPerDiemOverviewGrid()

        val receiptsSection = buildReceiptsSection()

        refreshFooter()
        val footer = VerticalLayout(calculatedLine, onInvoiceLine).apply { isPadding = false; isSpacing = false; setWidthFull() }

        val bodyComponents = buildList {
            if (readOnly) add(Paragraph(readOnlyStateHint()).apply { addClassNames(CSS_CLASS_FS_08, TEXT_MUTED, "m-0") })
            add(headerForm); add(switchesRow); add(fixedPriceField); add(inheritedTaxNote); add(Hr())
            add(expensesSection); add(Hr())
            add(receiptsSection); add(Hr()); add(footer)
        }
        dlg.add(*bodyComponents.toTypedArray())

        if (readOnly) {
            // Spec — a plain close, no save path; requestClose() is not used here since a
            // read-only dialog never has unsaved input to confirm discarding.
            dlg.footer(AppButtons.tertiary(tr("app.close")) { dlg.close() })
        } else {
            dlg.footer(
                AppButtons.cancel { requestClose() },
                AppButtons.primary(tr("app.save")) { save() },
            )
        }
        // The export control is context, not a decision: it leads the footer, ahead of cancel and save.
        dlg.footer.addComponentAsFirst(buildExportControl())

        // A11y — focus order header -> expenses -> per-diem -> footer follows the DOM add order
        // above; Escape asks first when the form has unsaved input (existing dialog idiom, see
        // requestClose's own KDoc) instead of Dialog's default silent-discard behaviour.
        Shortcuts.addShortcutListener(dlg, { _ -> requestClose() }, Key.ESCAPE)

        dlg.open()
        titleField.focus()
    }

    private fun dialogTitle(): String =
        if (existingItem != null) {
            "${tr("button.edit")} - ${tr("invoiceDetail.trip.dialog.title")}"
        } else {
            tr("invoiceDetail.trip.dialog.title")
        }

    /** Spec section 2 — every input becomes read-only/disabled; combos, checkboxes and date
     *  pickers have no `isReadOnly` state in this Vaadin version, so those are disabled instead
     *  (a read-only text field still shows and lets the user copy its value). */
    private fun applyReadOnlyState() {
        titleField.isReadOnly = true
        purposeField.isReadOnly = true
        periodFromField.isEnabled = false
        periodToField.isEnabled = false
        costCenterField.isReadOnly = true
        detailedOnInvoiceSwitch.isEnabled = false
        fixedPriceSwitch.isEnabled = false
        fixedPriceField.isReadOnly = true
    }

    /** Spec section 2 — names the invoice's own state (SENT/VOIDED/CANCELLED, or the
     *  Korrektur-Storno distinction) via [statusBadgeLabelKey], the same label the status badge
     *  elsewhere in this view already shows, rather than a second, duplicate wording. */
    private fun readOnlyStateHint(): String =
        tr("invoiceDetail.trip.readOnlyHint", tr(statusBadgeLabelKey(invoice.statusEnum(), invoice.typeCodeEnum())))

    private fun buildPerDiemDaysEditor(): PerDiemDaysEditor = PerDiemDaysEditor(
        tripEntryQueryPort = tripEntryQueryPort,
        perDiemCalculator = perDiemCalculator,
        existingDays = existingItem?.trip?.perDiemDays.orEmpty(),
        tripPeriod = { periodFromField.value?.let { from -> periodToField.value?.let { to -> if (!from.isAfter(to)) from..to else null } } },
        // Fires once synchronously from the constructor's own initial render, before this
        // field is assigned (see refreshFooter's own KDoc) — only mark the dialog dirty for a
        // callback that runs after construction actually finished, i.e. a real user edit.
        onChange = { if (::perDiemDaysEditor.isInitialized) dirty = true; refreshFooter(); refreshPerDiemOverviewGrid() },
        today = today,
    )

    private fun buildHeaderForm(): FormLayout =
        FormLayout(titleField, purposeField, periodFromField, periodToField, costCenterField).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("480px", 2))
            setColspan(titleField, 2); setColspan(purposeField, 2)
        }

    private fun buildSwitchesRow(): HorizontalLayout {
        fixedPriceSwitch.addValueChangeListener {
            fixedPriceField.isVisible = it.value
            refreshInheritedTaxNote()
            dirty = true
            refreshFooter()
        }
        fixedPriceField.addValueChangeListener { dirty = true; refreshFooter() }
        detailedOnInvoiceSwitch.addValueChangeListener { dirty = true }

        return HorizontalLayout(detailedOnInvoiceSwitch, fixedPriceSwitch).apply {
            isPadding = false; addClassNames("lt-gap-150", "toolbar-wrap")
        }
    }

    /** "Posten" split into an "Übersicht" tab (read-only, [TripExpenseRowsEditor.overviewGrid]) and a
     *  "Bearbeiten" tab (the row-list editor). Both read the same
     *  [TripExpenseRowsEditor] row list, so switching tabs neither reorders nor loses in-progress
     *  edits — only [TripExpenseRowsEditor.render] ever repopulates either one. Übersicht is added first so [TabSheet]
     *  selects it by default (spec: shown first).
     *
     *  Per-diem entries join both tabs of this SAME [TabSheet] rather than living in a
     *  separate section: a read-only [TripPerDiemOverview] block below the expense grid in
     *  Übersicht (grouped, not interleaved — a per-diem entry and a trip expense are different
     *  domain objects, forcing one [DataTable] row type over both would blend them), and
     *  [PerDiemDaysEditor]'s own entry-list block below [TripExpenseRowsEditor.container] in Bearbeiten. Both
     *  per-diem blocks are built only while [perDiemUiEnabled] is true (the switch keeps
     *  gating the whole section) — while off, neither [Section] is ever added, so the "hidden
     *  while off" tests find no trace of it.
     *
     *  While [perDiemUiEnabled] is on, both [Section]s are still built and
     *  added up front (so [perDiemOverview]/[perDiemEditSection] always exist once this
     *  method returns), but start and stay `isVisible = false` until the first per-diem entry is
     *  added — [refreshPerDiemSectionsVisibility] toggles them on every [PerDiemDaysEditor.onChange]
     *  firing (add/delete), so per-diem stays invisible entirely until the user opts in by clicking
     *  "+ Verpflegungspauschale", and hides again the moment the last entry is deleted. Toggling
     *  `isVisible` rather than adding/removing from the layout tree keeps both grids/rows fully
     *  built and ready underneath, and matches this file's own idiom elsewhere (`fixedPriceField`).
     *
     *  [PerDiemDaysEditor.addButton] joins addButtonsRow
     *  itself — one shared row of "+" buttons for every kind of Posten, trip expense or per-diem
     *  entry alike — instead of sitting inside its own section below. Only the button moves; the
     *  entry list and rate fields stay in their own [Section], since those show fields
     *  ([TripExpenseRow] has none of) rate/meal-checkboxes a trip-expense row never needs. Both
     *  per-diem blocks use [Section] (the project's own bordered/bar/title component,
     *  `ui/component/Section.kt`, already used by `RecipientView`/`ArticleView`) instead of a bare
     *  `Hr()` plus an unstyled `Span`, so the divider between Posten groups carries real visual
     *  weight instead of blending into plain body text — needed once the per-diem add button
     *  moved away from directly above it. */
    private fun buildExpensesSection(): Component {
        val overviewTabContent = VerticalLayout(expenses.overviewGrid).apply {
            isPadding = false; isSpacing = true; setWidthFull()
        }

        if (perDiemUiEnabled) {
            perDiemOverview = TripPerDiemOverview(perDiemDaysEditor) { formatCurrency(it) }
            overviewTabContent.add(perDiemOverview.section)
        }

        val tabs = TabSheet().apply { setWidthFull() }
        tabs.add(Tab(tr("invoiceDetail.trip.tab.overview")), overviewTabContent)

        // Spec section 2 — the "Bearbeiten" tab is hidden entirely while read-only, not merely
        // disabled: an editor that cannot edit is noise, and its "+"/delete buttons must be gone,
        // not disabled, so this whole branch (including perDiemDaysEditor.addButton and every
        // per-diem entry's own delete button, both built inside perDiemDaysEditor.getComponent())
        // is skipped rather than attached and hidden.
        if (!readOnly) {
            val addButtons = expenses.addButtons() +
                listOfNotNull(if (perDiemUiEnabled) perDiemDaysEditor.addButton else null)
            val addButtonsRow = HorizontalLayout(*addButtons.toTypedArray())
                .apply { isPadding = false; addClassNames("lt-gap-050", "toolbar-wrap") }
            val editTabContent = VerticalLayout(addButtonsRow, expenses.container).apply {
                isPadding = false; isSpacing = true; setWidthFull()
            }
            if (perDiemUiEnabled) {
                perDiemEditSection = Section(tr("invoiceDetail.trip.perDiem.title"), "sp-section-bar--blue", perDiemDaysEditor.getComponent()).apply {
                    setWidthFull()
                    addClassName("lt-border-box")
                }
                editTabContent.add(perDiemEditSection)
            }
            tabs.add(Tab(tr("invoiceDetail.trip.tab.edit")), editTabContent)
        }
        refreshPerDiemSectionsVisibility()

        return Section(tr("invoiceDetail.trip.expenses.title"), "", tabs)
    }

    /** Guarded like [refreshFooter]: [PerDiemDaysEditor]'s own `onChange` fires once synchronously
     *  from its constructor, before [perDiemOverview] exists (it is built later, inside
     *  [buildExpensesSection]) — and never exists at all while [perDiemUiEnabled] is false. */
    private fun refreshPerDiemOverviewGrid() {
        if (perDiemUiEnabled && ::perDiemOverview.isInitialized) perDiemOverview.refresh()
        refreshPerDiemSectionsVisibility()
    }

    /** Both per-diem [Section] blocks stay invisible until the
     *  first entry is added, re-evaluated on every [PerDiemDaysEditor.onChange] firing (add/delete;
     *  see [buildExpensesSection]'s own KDoc). Guarded the same way [refreshPerDiemOverviewGrid]
     *  is: not yet built on the constructor's own synchronous first call, and never built at all
     *  while [perDiemUiEnabled] is false. */
    private fun refreshPerDiemSectionsVisibility() {
        if (!perDiemUiEnabled || !::perDiemOverview.isInitialized) return
        val visible = perDiemDaysEditor.hasEntries()
        perDiemOverview.section.isVisible = visible
        // perDiemEditSection is never built while readOnly (buildExpensesSection skips the whole
        // "Bearbeiten" tab then) — guarded the same way the other lateinit sections here are.
        if (::perDiemEditSection.isInitialized) perDiemEditSection.isVisible = visible
    }

    private fun buildReceiptsSection(): Component {
        val receiptsButton = AppButtons.tertiary(tr("invoiceDetail.trip.receipts.title"), ActionIcons.ATTACHMENTS) {
            existingItem?.id?.let { openReceiptsDialog(it) }
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            isEnabled = existingItem?.id != null
            element.setProperty("title", if (existingItem?.id == null) tr("invoiceDetail.items.receipts.unsavedPosition") else "")
        }
        refreshReceiptsCount()
        val buttonRow = HorizontalLayout(receiptsButton, receiptsCountLabel).apply {
            isPadding = false; defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER; addClassName("lt-gap-075")
        }
        // The two assignment levels (whole trip vs. per Posten row) are not otherwise
        // obvious from the UI alone: "Belege" here attaches to the trip item, but the Reise-Excel's
        // per-line receipt column only ever reads a row's own receiptId (Bearbeiten tab).
        val assignmentHint = Paragraph(tr("invoiceDetail.trip.receipts.assignmentHint")).apply {
            addClassNames(CSS_CLASS_FS_08, TEXT_MUTED, "m-0")
        }
        return VerticalLayout(buttonRow, assignmentHint).apply { isPadding = false; isSpacing = false; setWidthFull() }
    }

    private fun buildExportControl(): Component {
        val exportButton = AppButtons.spreadsheetDownload().apply {
            setId(EXPORT_BUTTON_ID)
            isEnabled = existingItem != null && tripWorkbookExport != null
        }
        return if (existingItem != null && tripWorkbookExport != null) {
            Anchor(tripWorkbookDownloadHandler(invoice, existingItem.positionId, tripWorkbookExport, ::tr), "").apply {
                element.setAttribute("download", true)
                add(exportButton)
            }
        } else {
            exportButton
        }
    }

    private fun wireDirtyTracking() {
        titleField.addValueChangeListener { dirty = true }
        purposeField.addValueChangeListener { dirty = true }
        periodFromField.addValueChangeListener { dirty = true }
        periodToField.addValueChangeListener { dirty = true }
        costCenterField.addValueChangeListener { dirty = true }
    }

    /** Escape/Cancel: closes immediately when nothing changed, otherwise confirms discarding —
     *  the same [ConfirmDialog] idiom `InvoiceDetailViewOverview`'s reverse-charge toggle uses. */
    private fun requestClose() {
        if (!dirty) {
            dlg.close()
            return
        }
        ConfirmDialog(
            tr("invoiceDetail.trip.discardConfirm.title"),
            tr("invoiceDetail.trip.discardConfirm.text"),
            tr("invoiceDetail.trip.discardConfirm.confirm"),
        ) { dlg.close() }.apply {
            setCancelable(true)
            setCancelText(tr("app.cancel"))
            open()
        }
    }

    // ── Inherited tax ────────────────────────────────────────────────────────

    private fun refreshInheritedTaxNote() {
        inheritedTaxNote.text = try {
            val rate = selectTripTaxRate(
                TripTaxContext(
                    invoice.reverseCharge,
                    invoice.sellerVatRegistrationCountry?.let { Country.fromAlpha2(it) }, invoice.invoiceDate,
                    reverseChargeTaxRateFor(invoice.items, tripEntryQueryPort.findAllTaxRates()),
                ),
                tripEntryQueryPort::findReferenceTaxRate,
            )
            val key = if (!invoice.reverseCharge) "invoiceDetail.trip.referenceTax" else "invoiceDetail.trip.inheritedTax"
            tr(key, taxRuleLabel(rate))
        } catch (e: InvalidParameterException) {
            e.message ?: tr("invoiceDetail.trip.inheritedTax.missing")
        }
    }

    // ── Receipts ─────────────────────────────────────────────────────────────

    private fun refreshReceiptsCount() {
        val count = existingItem?.id?.let { invoiceReceiptQueryPort.countReceiptsForItem(it) } ?: 0
        receiptsCountLabel.text = if (count > 0) tr("invoiceDetail.items.receipts.count", count.toString()) else tr("invoiceDetail.items.receipts.none")
    }

    private fun openReceiptsDialog(itemId: Long) {
        ReceiptDialog(
            invoiceReceiptQueryPort = invoiceReceiptQueryPort,
            attachReceiptsUseCase = attachReceiptsUseCase,
            invoice = invoice,
            target = ReceiptTarget.Item(itemId),
            onChanged = { refreshReceiptsCount(); expenses.render() },
        ).open()
    }

    // ── Footer preview ───────────────────────────────────────────────────────

    /** The calculated net across expenses and per-diem days — the same two terms
     *  [de.littletrotter.domain.invoice.InvoiceItem.calculatedTripNet] sums server-side (see this
     *  class's own KDoc); [PerDiemDaysEditor.currentTotal] already routes through
     *  [PerDiemCalculator], so only the expense side is summed again here, via the identical
     *  [InvoiceItem.calculateAmounts] formula [TripExpenseRow.netAmount] uses. */
    private fun calculatedNet(): Money =
        expenses.currentTotal() + perDiemDaysEditor.currentTotal()

    /** Guarded against [PerDiemDaysEditor]'s own constructor: it renders its initial day rows
     *  synchronously and reports that first render through the very `onChange` callback that
     *  calls this method — at that point [perDiemDaysEditor] (assigned only once the constructor
     *  call above returns) is not there yet. [open] calls this again once construction is
     *  actually done, so the guarded call here is a harmless no-op, not a missed refresh. */
    private fun refreshFooter() {
        if (!::perDiemDaysEditor.isInitialized) return
        val calculated = calculatedNet()
        calculatedLine.text = tr("invoiceDetail.trip.footer.calculated", formatCurrency(calculated))
        if (fixedPriceSwitch.value) {
            val fixed = Money.of(BigDecimal.valueOf(fixedPriceField.value ?: 0.0))
            val delta = fixed - calculated
            onInvoiceLine.text = tr("invoiceDetail.trip.footer.onInvoice", formatCurrency(fixed), formatCurrency(delta))
            onInvoiceLine.isVisible = true
        } else {
            onInvoiceLine.isVisible = false
        }
    }

    private fun formatCurrency(money: Money): String = formatCurrency(money.amount, invoice.currencyEnum())

    // ── Save ─────────────────────────────────────────────────────────────────

    /** Spec section 3 — belt and braces: even though the read-only footer never renders a save
     *  button, this refuses to submit while read-only so a future refactor cannot open a write
     *  path by accident. `internal`, not `private`, so TripDialogKaribuTest can pin this
     *  directly (the same idiom as [EXPORT_BUTTON_ID]/[PER_DIEM_OVERVIEW_GRID_ID] exposing a test
     *  seam). */
    /** [save]'s validation, split into small chained checks to keep that method's own
     *  ReturnCount down — the first non-null message key wins, same order as before. */
    private fun firstValidationErrorKey(): String? =
        readOnlyErrorKey() ?: titleOrExpenseErrorKey() ?: fixedPriceErrorKey() ?: perDiemErrorKey()

    private fun readOnlyErrorKey(): String? = if (readOnly) "invoiceDetail.trip.save.readOnlyBlocked" else null

    private fun titleOrExpenseErrorKey(): String? {
        if (titleField.value.trim().isBlank()) return TITLE_REQUIRED_KEY
        if (expenses.hasMissingDescription()) return "invoiceDetail.trip.expense.description.required"
        return null
    }

    private fun fixedPriceErrorKey(): String? {
        val fixedPrice = currentFixedPrice()
        return if (fixedPrice != null && fixedPrice.isNegative()) FIXED_PRICE_NEGATIVE_KEY else null
    }

    private fun perDiemErrorKey(): String? =
        if (!perDiemDaysEditor.isValid()) "invoiceDetail.perDiem.error.rateRequired" else null

    private fun currentFixedPrice(): Money? =
        if (fixedPriceSwitch.value) Money.of(BigDecimal.valueOf(fixedPriceField.value ?: 0.0)) else null

    internal fun save() {
        val errorKey = firstValidationErrorKey()
        if (errorKey != null) {
            showValidationError(errorKey)
            return
        }

        val command = buildCommand()
        if (rejectUnprintableText(command)) return
        if (existingItem != null) onUpdate(command) else onSaveNew(command)
        dirty = false
        dlg.close()
    }

    /** Puts the error on the input that caused it where the dialog owns that input; errors of the
     *  expense and per-diem editors have no single field here and stay a toast. */
    private fun showValidationError(errorKey: String) {
        val message = tr(errorKey)
        when (errorKey) {
            TITLE_REQUIRED_KEY -> UiNotifications.errorOn(titleField, message)
            FIXED_PRICE_NEGATIVE_KEY -> UiNotifications.errorOn(fixedPriceField, message)
            else -> UiNotifications.error(message)
        }
    }

    /** The PDF engine rejects characters its fonts lack, so every text the trip prints is checked. */
    private fun rejectUnprintableText(command: TripCommand): Boolean {
        val pdfTr = { key: String -> tr(key) }
        val flagged = listOf(titleField, purposeField, costCenterField)
            .firstNotNullOfOrNull { field -> PdfTextValidation.message(pdfTr, field.value)?.let { field to it } }
        if (flagged != null) {
            UiNotifications.errorOn(flagged.first, flagged.second)
            return true
        }
        val rowTexts = command.expenses.map { it.description } +
            command.perDiemDays.flatMap { listOf(it.description, it.participant) }
        val rowProblem = rowTexts.firstNotNullOfOrNull { PdfTextValidation.message(pdfTr, it) } ?: return false
        UiNotifications.error(rowProblem)
        return true
    }

    private fun buildCommand(): TripCommand {
        val fixedPrice = currentFixedPrice()
        return TripCommand(
            title = titleField.value.trim(),
            purpose = purposeField.value.trim().takeIf { it.isNotBlank() },
            periodFrom = periodFromField.value,
            periodTo = periodToField.value,
            detailedOnInvoice = detailedOnInvoiceSwitch.value,
            fixedNetPrice = fixedPrice,
            expenses = expenses.commands(),
            perDiemDays = perDiemDaysEditor.currentCommands(),
            costCenter = costCenterField.value.trim().takeIf { it.isNotBlank() },
        )
    }

    companion object {
        private const val TITLE_REQUIRED_KEY = "invoiceDetail.trip.title.required"
        private const val FIXED_PRICE_NEGATIVE_KEY = "invoiceDetail.trip.fixedPrice.negative"

        /** The export button's own Karibu test looks the button up by this id. */
        const val EXPORT_BUTTON_ID = "trip-export-button"

        /** Lets Karibu tests find the per-diem overview grid unambiguously once the
         *  Übersicht tab holds two [DataTable]s (trip expenses, per-diem entries) at once. */
        const val PER_DIEM_OVERVIEW_GRID_ID = "trip-per-diem-overview-grid"
    }
}
