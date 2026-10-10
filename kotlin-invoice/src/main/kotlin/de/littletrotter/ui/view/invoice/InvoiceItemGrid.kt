package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.shared.Money
import de.littletrotter.shared.PositionNumberFormatter
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.RowEditButtons
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.component.orPlaceholder
import de.littletrotter.ui.component.wireBufferedEditorKeys
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import java.math.BigDecimal

private const val PROP_TITLE = "title"
private const val TEXT_MUTED = "text-muted"
private const val INVOICE_ITEM_SUBLINE = "invoice-item-subline"

/** CSS part name for the category color bar (`vaadin-grid::part(item-cat-*)` in styles.css). */
private fun categoryPartName(category: InvoiceItemCategory): String = when (category) {
    InvoiceItemCategory.PRODUCT -> "item-cat-product"
    InvoiceItemCategory.SERVICE -> "item-cat-service"
    InvoiceItemCategory.TRIP -> "item-cat-travel"
    InvoiceItemCategory.UNIT_PRICE -> "item-cat-unit-price"
}

/**
 * PO 2026-08-25 — a PRODUCT position must always show BT-156 (buyer's article number) and BT-154
 * (item description) when the recipient's B2B profile defines the term, or when a value is
 * already present even without one. BT-154 lives directly on the item (no query needed); BT-156's
 * profile-defined-ness and its per-item values are resolved once here for the whole grid — never
 * per row — mirroring the item dialogs' own findB2bItemFieldsFor/batched idiom.
 */
private data class Bt156Context(val defined: Boolean, val valuesByItem: Map<Long, String?>)

/**
 * Groups the inline-edit [Binder] with the editor fields it binds, so the column-builder helpers
 * below (each touching only some of the row's fields) take one holder instead of a growing
 * binder/editorFields parameter pair. `binder == null` means [InvoiceItemGrid.build] was called
 * with `enableInlineEdit = false`; every helper below treats that as "no editor wiring for this
 * column" and returns early.
 */
private class ItemGridEditingContext(val binder: Binder<ItemDto>?) {
    val editorFields: MutableList<Component> = mutableListOf()

    /** The actions column [InvoiceItemGrid.build] already built, stashed here so
     *  [InvoiceItemGrid.wireInlineEditing] can reach it without a parameter of its own — the same
     *  "existing context instead of a growing parameter list" idiom this holder already follows
     *  for the bound fields below. */
    var actionsColumn: Grid.Column<ItemDto>? = null

    /** [ItemDto]'s quantity/unitPriceNet/taxRate/taxRateRefId are `val` (immutable read
     *  model); the buffered [Binder] can no longer write them back onto the bean, so each bound
     *  setter below stashes its edited value here instead, and [wireInlineEditing]'s save
     *  listener folds them into a fresh item via `copy()`. Reset on every editor open. */
    var pendingQuantity: BigDecimal? = null
    var pendingUnitPriceNet: Money? = null
    var pendingTaxRate: BigDecimal? = null
    var pendingTaxRateRefId: Long? = null

    fun resetPendingEdits() {
        pendingQuantity = null
        pendingUnitPriceNet = null
        pendingTaxRate = null
        pendingTaxRateRefId = null
    }
}

/**
 * the Positionen tab's grid, extracted out of `InvoiceDetailViewItemGrid.kt`'s extension
 * functions into a `@Dependent` CDI bean of its own, the same shape as [InvoiceReceiptsTab]/
 * [InvoiceStatusActions]: injected once into [InvoiceItemsTab]. Not itself a [Component] (same
 * "host purely as translation/dialog-attach context" idiom [InvoiceStatusActions] already uses) —
 * [InvoiceItemsTab] decides where the returned [DataTable] lives, and wires the four callbacks
 * below once after construction (the same "wired once after CDI construction" idiom
 * [InvoiceDetailView]'s own `exportPanel.onReload` already uses), since a CDI-injected bean's
 * constructor can carry only its own ports, never another component's callbacks.
 *
 * The former `updateItem` write path lives one level up now, in [InvoiceItemDialogs] — this class
 * only validates an inline edit's exemption-detail completeness (it alone knows [taxRateQueries]
 * and the edited [ItemDto]) and then reports through [onInlineSave].
 */
@Dependent
class InvoiceItemGrid
@Inject
constructor(
    private val taxRateQueries: TaxRateQueryPort,
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val b2bFieldValueQueries: B2bFieldValueQueryPort,
    private val time: TimeSource,
) {
    /** Opens the edit dialog — non-inline column click, or any TRIP row (double-click/view icon). */
    internal var onEdit: (ItemDto) -> Unit = {}

    /** Deletes an item. */
    internal var onDelete: (ItemDto) -> Unit = {}

    /** Opens the item-level receipts dialog. */
    internal var onReceiptsClick: (ItemDto) -> Unit = {}

    /**
     * An inline edit passed validation; reports the update command to [InvoiceItemDialogs].
     * Deliberately carries no invoice: the grid outlives the snapshot it was built from (item
     * saves only swap its rows), so the owner must resolve the invoice's current version itself.
     */
    internal var onInlineSave: (InvoiceItemCommand) -> Unit = {}

    fun build(
        host: Component,
        invoice: InvoiceDetailDto,
        items: List<ItemDto>,
        enableInlineEdit: Boolean = false,
    ): DataTable<ItemDto> {
        val bt156Context = resolveBt156Context(invoice, items)
        val editing = ItemGridEditingContext(if (enableInlineEdit) Binder(ItemDto::class.java) else null)

        return DataTable(ItemDto::class.java, false).apply {
            setWidthFull()
            if (editing.binder != null) {
                editor.binder = editing.binder
                editor.isBuffered = true
            }
            addPositionColumn(host, this)
            addDescriptionColumn(host, this, bt156Context)
            addReceiptsColumn(host, this)
            addQtyUnitPriceColumns(host, this, editing, invoice.currencyEnum())
            addTaxRateColumn(host, this, editing)
            addColumn { formatCurrency((it.netAmount ?: Money.ZERO).amount, invoice.currencyEnum()) }
                .setHeader(tr(host, "invoiceDetail.netAmount")).setSortable(true).asNumericColumn()
            // Spec (trip-details-read-only) — the actions column is now built for a read-only invoice
            // too, but carries only a view action, and only for a TRIP row: every other read-only row
            // already shows everything it has. The write-path wiring below (inline-edit save listener,
            // editor component, double-click) still only ever applies to the writable column.
            editing.actionsColumn = addActionsColumn(host, this, invoice)
            if (!invoice.isReadOnly() && editing.binder != null) {
                wireInlineEditing(host, this, editing)
            }
            // The caller (InvoiceItemsTab) calls setItems with a ListDataProvider so the grid can
            // be updated without rebuilding.
        }
    }

    private fun tr(host: Component, key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    private fun resolveBt156Context(invoice: InvoiceDetailDto, items: List<ItemDto>): Bt156Context {
        val productItemIds = items.filter { it.categoryEnum() == InvoiceItemCategory.PRODUCT }.map { it.id }
        val defined = b2bFieldDefinitionQueries.findB2bInvoiceItemFields(b2bFieldValueQueries.findB2bProfileIdForInvoice(invoice.id))
            .any { it.term == B2bTerm.BT_156 }
        val valuesByItem: Map<Long, String?> = if (productItemIds.isEmpty()) emptyMap()
            else b2bFieldValueQueries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, productItemIds)
                .mapValues { (_, values) -> values[B2bTerm.BT_156.officialCode] }
        return Bt156Context(defined, valuesByItem)
    }

    private fun addPositionColumn(host: Component, grid: DataTable<ItemDto>) {
        grid.addComponentColumn { item -> Span(PositionNumberFormatter.format(item.positionId)) }
            .setHeader(tr(host, "invoiceDetail.items.column.position"))
            .asCompactColumn()
            .setSortable(true)
            .setComparator(positionIdComparator())
            .setPartNameGenerator { categoryPartName(it.categoryEnum()) }
    }

    private fun addDescriptionColumn(host: Component, grid: DataTable<ItemDto>, bt156: Bt156Context) {
        grid.addComponentColumn { item -> buildItemDescriptionCell(host, item, bt156.defined, bt156.valuesByItem) }
            .setHeader(tr(host, "invoiceDetail.items.column.description"))
            .asTextColumn(TextColumnWidth.LARGE)
            .isSortable = false
    }

    private fun addReceiptsColumn(host: Component, grid: DataTable<ItemDto>) {
        grid.addComponentColumn { item ->
            val count = item.receiptIds.size
            val label = if (count > 0) tr(host, "invoiceDetail.items.receipts.count", count.toString()) else tr(host, "invoiceDetail.items.receipts.none")
            val onClick = { onReceiptsClick(item) }
            val button = if (count > 0) {
                AppButtons.primary(label, ActionIcons.ATTACHMENTS, onClick)
            } else {
                AppButtons.tertiary(label, ActionIcons.ATTACHMENTS, onClick)
            }
            button.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
                addClassName("invoice-item-receipts")
            }
        }.setHeader(tr(host, "invoiceDetail.items.column.receipts")).asCompactColumn().isSortable = false
    }

    private fun addQtyUnitPriceColumns(
        host: Component,
        grid: DataTable<ItemDto>,
        editing: ItemGridEditingContext,
        currency: de.littletrotter.shared.Currency,
    ) {
        val qtyColumn = grid.addColumn { formatDecimal(it.quantity) }.setHeader(tr(host, "invoiceDetail.items.column.quantity")).setSortable(true).asNumericColumn()
        grid.addColumn { unitLabel(host, it.unit.orEmpty()) }.setHeader(tr(host, "invoiceDetail.items.column.unit")).asCompactColumn().isSortable = true
        // A TRIP row's `unitPriceNet` is domain-forced equal to its net total
        // (InvoiceItem.calculateAmounts); showing that same number twice (once here, once in
        // the net column) reads as a mistake, so this column shows a placeholder for trips
        // instead — the net column right of it is still the one real number.
        val priceColumn = grid.addColumn { item ->
            if (item.categoryEnum() == InvoiceItemCategory.TRIP) "-" else formatCurrency((item.unitPriceNet ?: Money.ZERO).amount, currency)
        }.setHeader(tr(host, "invoiceDetail.items.column.unitPriceNet")).setSortable(true).asNumericColumn()
        val binder = editing.binder ?: return

        val qtyField = NumberField().apply { min = 0.0; setWidthFull() }
        binder.forField(qtyField).bind(
            { item: ItemDto -> item.quantity.toDouble() },
            { _: ItemDto, v: Double? -> editing.pendingQuantity = BigDecimal.valueOf(v ?: 0.0) },
        )
        qtyColumn.editorComponent = qtyField
        editing.editorFields.add(qtyField)

        val priceField = NumberField().apply { min = 0.0; suffixComponent = Span("€"); setWidthFull() }
        binder.forField(priceField).bind(
            { item: ItemDto -> (item.unitPriceNet ?: Money.ZERO).amount.toDouble() },
            { _: ItemDto, v: Double? -> editing.pendingUnitPriceNet = Money.of(BigDecimal.valueOf(v ?: 0.0)) },
        )
        priceColumn.editorComponent = priceField
        editing.editorFields.add(priceField)
    }

    private fun addTaxRateColumn(host: Component, grid: DataTable<ItemDto>, editing: ItemGridEditingContext) {
        val taxColumn = grid.addColumn { item -> item.taxRate?.let { "${formatDecimal(it)} %" }.orPlaceholder() }.setHeader(tr(host, "invoiceDetail.items.column.taxRate")).setSortable(true).asNumericColumn()
        val binder = editing.binder ?: return

        val taxBox = ComboBox<TaxRateDto>().apply {
            setItemLabelGenerator(::taxRuleLabel)
            setWidthFull()
            // Populated with the full catalogue up front: Vaadin's EditorImpl runs
            // binder.readBean — which sets this box's value — BEFORE firing the open event,
            // so an items-less box crashed with "Cannot set a value for a ComboBox without
            // items" the first time the editor opened on an item that already carried a tax
            // rate (verified against vaadin-grid-flow 25.2.6 sources). The category-specific
            // narrowing still happens on open, below.
            setItems(taxRateQueries.findAllTaxRates())
        }
        // Selection depends on the line item type, determined only when editing — narrowed
        // here per item. The item's own current rate is kept in the list even when it is no
        // longer selectable (an in-use rate can be deactivated), otherwise opening the
        // editor would silently blank a historical rate.
        grid.editor.addOpenListener { event -> editing.resetPendingEdits(); applyTaxBoxSelection(host, taxBox, event.item) }
        binder.forField(taxBox).bind(
            { item -> item.taxRateRefId?.let { id -> taxRateQueries.findAllTaxRates().find { it.id == id } } },
            { _, v -> editing.pendingTaxRateRefId = v?.id; editing.pendingTaxRate = v?.rate?.value },
        )
        taxColumn.editorComponent = taxBox
        editing.editorFields.add(taxBox)
    }

    private fun applyTaxBoxSelection(host: Component, taxBox: ComboBox<TaxRateDto>, item: ItemDto) {
        val selectable = selectableTaxRatesFor(item.categoryEnum(), taxRateQueries.findAllTaxRates(), time.today())
        val current = item.taxRateRefId?.let { id -> taxRateQueries.findAllTaxRates().find { it.id == id } }
        // setItems replaces the data provider and drops the selection readBean just made —
        // restore it explicitly rather than relying on entity equality across lists.
        taxBox.setItems(if (current != null && selectable.none { it.id == current.id }) selectable + current else selectable)
        if (current != null) taxBox.value = current
        // A trip never picks its own rate, it always inherits the invoice's
        // main-service rate; the inline editor never actually opens on a TRIP
        // row (the grid's double-click listener routes it to TripDialog instead), but this
        // keeps the box itself honest if it is ever reached (e.g. programmatically).
        taxBox.isEnabled = item.categoryEnum() != InvoiceItemCategory.TRIP
        taxBox.element.setProperty(
            PROP_TITLE,
            if (taxBox.isEnabled) "" else tr(host, "invoiceDetail.items.column.taxRate.tripLocked"),
        )
    }

    private fun addActionsColumn(host: Component, grid: DataTable<ItemDto>, invoice: InvoiceDetailDto): Grid.Column<ItemDto> =
        if (invoice.isReadOnly()) {
            grid.addComponentColumn { item -> readOnlyItemActionsCell(host, item) }.asActionColumn()
        } else {
            grid.addComponentColumn { item -> writableItemActionsCell(host, item) }.asActionColumn()
        }

    private fun writableItemActionsCell(host: Component, item: ItemDto): Component =
        HorizontalLayout().apply {
            isPadding = false; isSpacing = true
            add(AppButtons.icon(ActionIcons.EDIT, tr(host, "button.edit")) { onEdit(item) }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
            })
            add(AppButtons.icon(ActionIcons.DELETE, tr(host, "app.delete")) { onDelete(item) }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
            })
        }

    private fun wireInlineEditing(
        host: Component,
        grid: DataTable<ItemDto>,
        editing: ItemGridEditingContext,
    ) {
        val actionsColumn = requireNotNull(editing.actionsColumn) { "actionsColumn must be built before wireInlineEditing" }
        grid.editor.addSaveListener { event ->
            // event.item is the still-immutable original row; the bound fields above only
            // ever wrote into editing.pending*, never onto the bean (see ItemGridEditingContext's
            // own KDoc). The tax-rate column is always wired (addTaxRateColumn runs unconditionally),
            // so its pending fields are the authoritative post-edit value, including an explicit
            // clear-to-null; quantity/unitPriceNet fall back to the original when the qty/price
            // columns were not built at all.
            val edited = event.item.copy(
                quantity = editing.pendingQuantity ?: event.item.quantity,
                unitPriceNet = editing.pendingUnitPriceNet ?: event.item.unitPriceNet,
                taxRateRefId = editing.pendingTaxRateRefId,
                taxRate = editing.pendingTaxRate,
            )
            applyInlineItemEdit(host, edited)
        }
        actionsColumn.setEditorComponent { _ -> buildInlineEditorActions(grid) }
        grid.wireBufferedEditorKeys(editing.editorFields)
        grid.addItemDoubleClickListener { event -> event.item?.let { handleItemDoubleClick(grid, it) } }
    }

    /**
     * The inline editor has no room for the full reason/legal-basis capture the item
     * dialogs offer (TaxRuleProposalPanel); a rate that still needs one (E/O) and doesn't
     * already carry it from a prior dialog save must be finished there, not silently saved
     * with the TaxRateDto seed row's generic placeholder text.
     */
    private fun applyInlineItemEdit(host: Component, item: ItemDto) {
        val rate = item.taxRateRefId?.let { id -> taxRateQueries.findAllTaxRates().find { it.id == id } }
        if (requiresExplicitExemptionDetails(rate) &&
            exemptionDetailsIncomplete(item.exemptionReasonText.orEmpty(), item.taxLegalBasis.orEmpty())
        ) {
            UiNotifications.error(tr(host, "invoiceDetail.taxRule.exemption.requiredInline"))
            return
        }
        // The Binder already wrote quantity/unitPriceNet/taxRateRefId onto `item`; toUpdateCommand()
        // reflects exactly those, and the server re-derives amounts from them.
        onInlineSave(item.toUpdateCommand())
    }

    private fun buildInlineEditorActions(grid: DataTable<ItemDto>): Component =
        HorizontalLayout().apply {
            isPadding = false; isSpacing = true
            add(RowEditButtons.confirm { grid.editor.save() })
            add(RowEditButtons.cancel { grid.editor.cancel() })
        }

    private fun handleItemDoubleClick(grid: DataTable<ItemDto>, item: ItemDto) {
        // Trip items have hotel-night or daily-rate logic that doesn't fit inline
        // number fields (egro-invoice.md section 5) — the dedicated dialog stays for
        // these (TRAVEL_EXPENSE/PER_DIEM merged into a single TRIP category).
        if (item.categoryEnum() == InvoiceItemCategory.TRIP) {
            onEdit(item)
        } else if (!grid.editor.isOpen) {
            grid.editor.editItem(item)
        }
    }

    /**
     * Spec (trip-details-read-only) — the read-only actions column's single possible cell content: a
     * view action ([ActionIcons.VIEW]) for a TRIP row only, reusing [onEdit] (routes a TRIP item to
     * the trip dialog, which itself derives its read-only mode from the invoice). Every other
     * category's row keeps no action while read-only — its grid row already shows everything it has.
     */
    private fun readOnlyItemActionsCell(host: Component, item: ItemDto): Component =
        HorizontalLayout().apply {
            isPadding = false; isSpacing = true
            if (item.categoryEnum() == InvoiceItemCategory.TRIP) {
                add(AppButtons.icon(ActionIcons.VIEW, tr(host, "button.view")) { onEdit(item) }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                })
            }
        }

    /**
     * PO 2026-08-25 — the positions grid's description cell for [item]: the plain description (BT-153)
     * plus, for a PRODUCT row only, muted sublines for BT-154 (item description, shown whenever a
     * value is present — it is a plain, never profile-gated field) and BT-156 (buyer's article number,
     * shown when the recipient's B2B profile defines the term — [bt156Defined], resolved once for the
     * whole grid — or a value is already present even without one). A defined-but-empty BT-156 renders
     * a visible "—" placeholder so the user sees the field is expected but missing; an undefined,
     * empty BT-156 renders nothing at all.
     */
    private fun buildItemDescriptionCell(
        host: Component,
        item: ItemDto,
        bt156Defined: Boolean,
        bt156ValuesByItem: Map<Long, String?>,
    ): Component {
        val lines = mutableListOf<Component>(Span(item.description))
        if (item.categoryEnum() == InvoiceItemCategory.PRODUCT) {
            item.itemDescription.blankToNull()?.let { description ->
                lines += Span(description).apply { addClassNames(TEXT_MUTED, INVOICE_ITEM_SUBLINE) }
            }
            val bt156Value = bt156ValuesByItem[item.id]
            if (bt156Defined || !bt156Value.isNullOrBlank()) {
                val display = bt156Value.orPlaceholder("—")
                lines += Span("${tr(host, "invoiceDetail.items.column.buyerItemNumberLabel")} $display").apply {
                    addClassNames(TEXT_MUTED, INVOICE_ITEM_SUBLINE)
                }
            }
        }
        if (item.categoryEnum() == InvoiceItemCategory.TRIP) {
            lines += Span(tripSummaryLine(host, item)).apply { addClassNames(TEXT_MUTED, INVOICE_ITEM_SUBLINE) }
        }
        return Div(*lines.toTypedArray()).apply { addClassName("invoice-item-description-cell") }
    }

    /**
     * The trip row's secondary line: "n Posten · m Verpflegungstage · Festpreis"/"detailliert".
     * Segments only appear when they apply (README border case 1/2 — a fixed-price-only or
     * per-diem-only trip is valid and must not show "0 Posten").
     */
    private fun tripSummaryLine(host: Component, item: ItemDto): String {
        val trip = item.trip
        val segments = mutableListOf<String>()
        val expenseCount = trip?.expenses?.size ?: 0
        if (expenseCount > 0) segments += tr(host, "invoiceDetail.trip.summary.expenses", expenseCount.toString())
        val perDiemCount = trip?.perDiemDays?.size ?: 0
        if (perDiemCount > 0) segments += tr(host, "invoiceDetail.trip.summary.perDiemDaysSymbol", perDiemCount.toString())
        segments += if (trip?.fixedNetPrice != null) tr(host, "invoiceDetail.trip.summary.fixedPrice") else tr(host, "invoiceDetail.trip.summary.calculated")
        if (trip?.detailedOnInvoice == true) segments += tr(host, "invoiceDetail.trip.summary.detailed")
        return segments.joinToString(" · ")
    }
}

internal fun sortedItemsForCategory(invoice: InvoiceDetailDto, category: InvoiceItemCategory): List<ItemDto> =
    invoice.items.filter { it.categoryEnum() == category }.sortedBy { it.sortOrder }

internal fun countItemsByCategory(invoice: InvoiceDetailDto): java.util.SortedMap<InvoiceItemCategory, Int> =
    InvoiceItemCategory.entries
        .associateWith { 0 }
        .toMutableMap()
        .apply {
            invoice.items.groupingBy { it.categoryEnum() }
                .eachCount()
                .forEach { (category, count) -> this[category] = count }
        }
        .toSortedMap(compareBy { it.ordinal })

/** positionId is a String ("1".."120"); the default sort is lexicographic and puts "120" before "5". */
internal fun positionIdComparator(): Comparator<ItemDto> = compareBy { it.positionId.toIntOrNull() ?: Int.MAX_VALUE }

private fun formatDecimal(value: BigDecimal?): String = (value ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString()

private fun unitLabel(host: Component, unitName: String): String = InvoiceUnitLabels.label(host, unitName)

/**
 * Builds the [InvoiceItemCommand] that reproduces an already-loaded [ItemDto]'s current field
 * values for [InvoiceItemDialogs.updateItem], used by write paths (the inline grid editor above)
 * that hold a loaded item rather than fresh dialog input. Mirrors exactly the field set
 * [de.littletrotter.application.invoice.InvoiceLifecycleService.updateItem] applies —
 * [InvoiceItemCommand.articleId] and the exemption-text overrides are creation-only (see
 * [InvoiceItemCommand]'s own KDoc) and therefore never included here.
 */
internal fun ItemDto.toUpdateCommand(): InvoiceItemCommand = InvoiceItemCommand(
    itemId = id,
    category = categoryEnum(),
    description = description,
    itemDescription = itemDescription,
    deliveryNoteReference = deliveryNoteReference,
    quantity = quantity,
    unit = unitEnum(),
    unitPriceNet = unitPriceNet ?: Money.ZERO,
    taxRateId = taxRateRefId,
)
