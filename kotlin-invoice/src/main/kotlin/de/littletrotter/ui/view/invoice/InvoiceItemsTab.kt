package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.KeyModifier
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridScrollBox
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.masterdata.ServiceCatalogSection
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * Positionen: one filterable table for all three item categories, instead of a two-level tab
 * nesting (Waren&Leistungen / Reisen{Reisekosten, Verpflegungspauschale}) that forced a second tab
 * open just to see a Reise's own sub-kind. TRAVEL_EXPENSE/EXPENSE/PER_DIEM are merged into one
 * TRIP category since a trip freely mixes expense lines and per-diem days; hotel/mileage prefill
 * and the day-rate editor share the single [TripDialog] covering both.
 */
internal data class ItemFilterOption(val category: InvoiceItemCategory?, val labelKey: String)

// Filter-chip labels reuse the `item.category.*` keys (the same text the description cell
// and every other category-labelled place in this module already use) instead of introducing a
// second, parallel set of category-labelled keys just for the filter row (DRY).
internal val ITEM_FILTER_OPTIONS = listOf(
    ItemFilterOption(null, "invoiceDetail.filter.all"),
    ItemFilterOption(InvoiceItemCategory.PRODUCT, "item.category.product"),
    ItemFilterOption(InvoiceItemCategory.UNIT_PRICE, "item.category.unitPrice"),
    ItemFilterOption(InvoiceItemCategory.SERVICE, "item.category.service"),
    ItemFilterOption(InvoiceItemCategory.TRIP, "item.category.trip"),
)

/**
 * A TRIP ([InvoiceItemCategory.TRIP]) also covers a plain hotel/taxi/mileage trip with no
 * per-diem days at all, so the toolbar's category chips ([ITEM_FILTER_OPTIONS]) never hide
 * anything based on the per-diem feature switch — that switch only gates the
 * "Verpflegungspauschalen" section inside [TripDialog] itself.
 *
 * The effective category filter for a re-render: keeps [filter] unless no [allItems] match it
 * anymore.
 */
internal fun effectiveItemCategoryFilter(
    filter: InvoiceItemCategory?,
    allItems: List<ItemDto>,
): InvoiceItemCategory? = when {
    filter == null -> null
    allItems.none { it.categoryEnum() == filter } -> null
    else -> filter
}

private const val ITEM_FILTER_CHIP_CLASS = "item-filter-chip"
private const val ITEM_FILTER_CHIP_ACTIVE_CLASS = "item-filter-chip--active"
private const val TOOLBAR_WRAP = "toolbar-wrap"
private const val ITEM_TOOLBAR_ROW = "item-toolbar-row"
private const val TEXT_MUTED = "text-muted"

/**
 * The Positionen tab as a `@Dependent` CDI bean of its own, the same shape as
 * [InvoiceReceiptsTab]: [render]/[refresh] rebuild/incrementally-update this component's content,
 * and it is injected once for the whole view lifetime instead of a `VerticalLayout` rebuilt per
 * invoice.
 *
 * [invoiceItemDialogs] is exposed `internal` (not `private`) so [InvoiceDetailTabs] can [bind] it
 * to [InvoiceDetailState] once, the same "reach a nested component's own dialog launcher" idiom
 * [InvoiceReceiptsTab.receiptDialogLauncher] already uses. [onReceiptsClick] is likewise wired
 * once by [InvoiceDetailTabs.bind] after construction (the same "wired once after CDI
 * construction" idiom [InvoiceDetailTabs] uses for its own `exportPanel.onReload`) — this tab has
 * no [ReceiptDialogLauncher] of its own to reach the Belege dialog through.
 */
@Dependent
class InvoiceItemsTab
@Inject
constructor(
    private val itemGrid: InvoiceItemGrid,
    internal val invoiceItemDialogs: InvoiceItemDialogs,
) : Div(), TranslationProvider {

    override fun getComponent(): Component = this

    internal var onReceiptsClick: (ItemDto) -> Unit
        get() = itemGrid.onReceiptsClick
        set(value) { itemGrid.onReceiptsClick = value }

    private var currentInvoice: InvoiceDetailDto? = null
    /** `internal` (not `private`) purely as a test seam, same idiom as [ReceiptDialogLauncher]'s
     *  own two receipt ports. */
    internal var activeItemCategoryFilter: InvoiceItemCategory? = null
    internal var itemsDataProvider: ListDataProvider<ItemDto>? = null
    internal var itemsGrid: DataTable<ItemDto>? = null
    internal var itemsHeaderSlot: Div? = null
    internal var itemsGridSlot: Div? = null

    init {
        setWidthFull()
        itemGrid.onEdit = { item -> currentInvoice?.let { invoiceItemDialogs.openEditInvoiceItemDialog(this, it, item) } }
        itemGrid.onDelete = { item -> currentInvoice?.let { invoiceItemDialogs.deleteItem(this, it, item) } }
        // The grid survives item saves (refresh only swaps its rows), so an inline save must use the
        // latest invoice snapshot — the one the grid was built from carries an outdated version.
        itemGrid.onInlineSave = { command -> currentInvoice?.let { invoiceItemDialogs.applyInlineUpdate(this, it, command) } }
    }

    fun render(invoice: InvoiceDetailDto) {
        currentInvoice = invoice
        val allItems = invoice.items.sortedBy { it.sortOrder }
        // One reset rule ("no items left" in the active filter falls back to "all"), pure and shared with refresh.
        activeItemCategoryFilter = effectiveItemCategoryFilter(activeItemCategoryFilter, allItems)

        fun itemsFor(category: InvoiceItemCategory?): List<ItemDto> =
            if (category == null) allItems else allItems.filter { it.categoryEnum() == category }

        val headerSlot = Div().apply { setWidthFull() }
        val gridSlot = Div().apply { setWidthFull() }

        // ── Create a ListDataProvider and hold it for incremental updates ──
        val filteredItems = itemsFor(activeItemCategoryFilter)
        val dataProvider = ListDataProvider(filteredItems.toMutableList())
        itemsDataProvider = dataProvider
        itemsHeaderSlot = headerSlot
        itemsGridSlot = gridSlot

        lateinit var renderHeader: () -> Unit

        val renderGrid: () -> Unit = {
            gridSlot.removeAll()
            val items = itemsFor(activeItemCategoryFilter)
            // The grid renders whatever [dataProvider] holds, so the filter only reaches the screen if
            // the provider's own contents follow it — binding the provider to a freshly built grid does
            // not, by itself, change a single row.
            dataProvider.items.clear()
            dataProvider.items.addAll(items)
            dataProvider.refreshAll()
            if (items.isEmpty()) {
                gridSlot.add(Paragraph(emptyMessageForItemFilter(activeItemCategoryFilter)).apply { addClassNames(TEXT_MUTED, "py-1") })
            } else {
                // Grid is built once with the data provider; subsequent data changes go through
                // refresh() which swaps items in the provider without rebuilding the grid.
                val grid = itemGrid.build(this, invoice, items, enableInlineEdit = true).apply {
                    isAllRowsVisible = true
                    setItems(dataProvider)
                }
                itemsGrid = grid
                gridSlot.add(GridScrollBox(grid))
                if (!invoice.isReadOnly()) {
                    gridSlot.add(Span(tr("invoiceDetail.items.inlineEdit.hint")).apply { addClassName("hint-text") })
                }
            }
        }

        renderHeader = {
            headerSlot.removeAll()
            val filterChips = buildItemFilterChips(
                activeFilter = activeItemCategoryFilter,
                countFor = { category -> itemsFor(category).size },
                onSelect = { category -> activeItemCategoryFilter = category; renderHeader(); renderGrid() },
            )
            headerSlot.add(buildItemToolbarRow(invoice, allItems.size, filterChips))
        }

        renderHeader()
        renderGrid()

        // Global Alt+N shortcut opens the "add item" dialog (only when editable)
        if (!invoice.isReadOnly()) {
            Shortcuts.addShortcutListener(gridSlot, { _ ->
                currentInvoice?.let { invoiceItemDialogs.openGoodsDialog(this, it) }
            }, Key.KEY_N, KeyModifier.ALT)
        }

        removeAll()
        add(VerticalLayout(headerSlot, gridSlot).apply { isPadding = false; isSpacing = true; setSizeFull() })
    }

    /**
     * Incrementally refreshes the item grid's data without rebuilding the Grid
     * component. Called from [InvoiceDetailTabs.refresh]/[InvoiceDetailTabs.refreshReceipts] after
     * item saves, deletions, or recalculations.
     */
    fun refresh(invoice: InvoiceDetailDto) {
        currentInvoice = invoice
        val provider = itemsDataProvider ?: return
        val slot = itemsGridSlot ?: return
        val headerSlot = itemsHeaderSlot ?: return

        val allItems = invoice.items.sortedBy { it.sortOrder }
        // Same effective-filter rule as render (see there); covers the
        // "filtered category has no items left" reset.
        activeItemCategoryFilter = effectiveItemCategoryFilter(activeItemCategoryFilter, allItems)
        val filteredItems = if (activeItemCategoryFilter == null) allItems else allItems.filter { it.categoryEnum() == activeItemCategoryFilter }

        // Swap data in the provider
        provider.items.clear()
        provider.items.addAll(filteredItems)
        provider.refreshAll()

        // Refresh the header (counters, chips, add buttons) — rebuild in-place
        headerSlot.removeAll()
        val filterChips = buildItemFilterChips(
            activeFilter = activeItemCategoryFilter,
            countFor = { category -> if (category == null) allItems.size else allItems.count { it.categoryEnum() == category } },
            onSelect = { category ->
                activeItemCategoryFilter = category
                // Re-filter the provider
                val refiltered = if (category == null) allItems else allItems.filter { it.categoryEnum() == category }
                provider.items.clear()
                provider.items.addAll(refiltered)
                provider.refreshAll()
                refresh(invoice)
            },
        )
        // Same toolbar row as render's above (this is the incremental-refresh copy of
        // the same header row); buildItemToolbarRow is the single place that shape is assembled.
        headerSlot.add(buildItemToolbarRow(invoice, allItems.size, filterChips))

        // Handle empty state: if no items in the current filter, show the empty message instead of the grid
        if (filteredItems.isEmpty()) {
            slot.removeAll()
            slot.add(Paragraph(emptyMessageForItemFilter(activeItemCategoryFilter)).apply { addClassNames(TEXT_MUTED, "py-1") })
        } else if (itemsGrid?.parent?.isEmpty != false) {
            // Grid was removed by a previous empty-state (or never attached) — rebuild it
            slot.removeAll()
            val grid = itemGrid.build(this, invoice, filteredItems, enableInlineEdit = !invoice.isReadOnly()).apply {
                isAllRowsVisible = true
                setItems(provider)
            }
            itemsGrid = grid
            slot.add(GridScrollBox(grid))
            if (!invoice.isReadOnly()) {
                slot.add(Span(tr("invoiceDetail.items.inlineEdit.hint")).apply { addClassName("hint-text") })
            }
        }
    }

    private fun emptyMessageForItemFilter(category: InvoiceItemCategory?): String = when (category) {
        InvoiceItemCategory.PRODUCT -> tr("invoiceDetail.items.empty.goods")
        InvoiceItemCategory.SERVICE -> tr("invoiceDetail.items.empty.services")
        InvoiceItemCategory.TRIP -> tr("invoiceDetail.items.empty.travel")
        InvoiceItemCategory.UNIT_PRICE -> tr("invoiceDetail.items.empty.goods")
        null -> tr("invoiceDetail.items.empty.all")
    }

    private fun appendAddItemButtons(row: HorizontalLayout, invoice: InvoiceDetailDto) {
        if (!invoice.isReadOnly()) {
            row.add(HorizontalLayout().apply {
                ServiceCatalogSection.entries.forEach { section ->
                    add(AppButtons.secondary(tr("invoiceDetail.add.category", tr(section.labelKey))) {
                        when (section.category) {
                            InvoiceItemCategory.PRODUCT, InvoiceItemCategory.UNIT_PRICE ->
                                invoiceItemDialogs.openGoodsDialog(this@InvoiceItemsTab, invoice, category = section.category)
                            InvoiceItemCategory.SERVICE -> invoiceItemDialogs.openServiceDialog(this@InvoiceItemsTab, invoice)
                            InvoiceItemCategory.TRIP -> invoiceItemDialogs.openTripDialog(this@InvoiceItemsTab, invoice)
                        }
                    }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                        element.setAttribute("data-add-category", section.category.name)
                    })
                }
                isPadding = false
                addClassNames("lt-gap-050", "toolbar-wrap")
            })
        }
    }

    /**
     * The one builder for the category filter row's chips, used by both [render]'s
     * `renderHeader` (full render, counts via `itemsFor`) and [refresh]'s incremental-refresh
     * header rebuild (counts via `allItems.count{}`), so the two call sites cannot drift into
     * different counting code. Only [onSelect] differs between the two callers
     * (their click listeners re-render through different paths) — everything else about a chip's
     * look and a11y wiring is decided here, once:
     * - each chip is a toggle (`aria-pressed`) with a `title`/`aria-label` spelling out what it does.
     * - a zero-count category is disabled (not hidden) so its "(0)" still tells the reader there is
     *   nothing there, but is not clickable into an empty grid; "Alle" is never disabled.
     * - the active chip gets [ITEM_FILTER_CHIP_ACTIVE_CLASS] (styles.css), the inactive ones the
     *   plain outlined [ITEM_FILTER_CHIP_CLASS] — both read as clickable at a glance.
     */
    private fun buildItemFilterChips(
        activeFilter: InvoiceItemCategory?,
        countFor: (InvoiceItemCategory?) -> Int,
        onSelect: (InvoiceItemCategory?) -> Unit,
    ): List<Component> {
        val chips = ITEM_FILTER_OPTIONS.map { option ->
            val count = countFor(option.category)
            val active = option.category == activeFilter
            val disabled = option.category != null && count == 0
            val describe = if (option.category == null) {
                tr("invoiceDetail.items.filter.showAll")
            } else {
                tr("invoiceDetail.items.filter.byCategory", tr(option.labelKey))
            }
            AppButtons.secondary("${tr(option.labelKey)} ($count)") { onSelect(option.category) }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
                addClassName(ITEM_FILTER_CHIP_CLASS)
                if (active) addClassName(ITEM_FILTER_CHIP_ACTIVE_CLASS)
                isEnabled = !disabled
                element.setAttribute("aria-pressed", active.toString())
                element.setAttribute("title", if (disabled) tr("invoiceDetail.items.filter.emptyCategory") else describe)
                element.setAttribute("aria-label", describe)
            }
        }
        return chips
    }

    /**
     * The item toolbar row (item count, filter chips, "Neu"-buttons), built
     * identically by [render]'s `renderHeader` (full render) and [refresh]'s incremental-refresh
     * header rebuild. Only [filterChips] itself differs between the two callers (their click
     * listeners re-render through different paths); the row shape around it does not.
     */
    private fun buildItemToolbarRow(
        invoice: InvoiceDetailDto,
        allItemsCount: Int,
        filterChips: List<Component>,
    ): HorizontalLayout {
        val leftSide = HorizontalLayout().apply {
            isPadding = false; defaultVerticalComponentAlignment = Alignment.CENTER
            addClassNames("lt-gap-100", TOOLBAR_WRAP)
            add(Span("$allItemsCount ${tr("invoiceDetail.items.count")}").apply { addClassName(TEXT_MUTED) })
            add(HorizontalLayout(*filterChips.toTypedArray()).apply {
                isPadding = false; isSpacing = false; addClassNames("lt-gap-025", TOOLBAR_WRAP)
                element.setAttribute("role", "group")
                element.setAttribute("aria-label", tr("invoiceDetail.items.filter.groupLabel"))
            })
        }
        return HorizontalLayout(leftSide).apply {
            setWidthFull(); defaultVerticalComponentAlignment = Alignment.CENTER; justifyContentMode = JustifyContentMode.BETWEEN
            // `justify-content: space-between` on an unpadded full-width row pushed the
            // "Neu"-buttons flush against the tab content's right edge; item-toolbar-row (styles.css)
            // gives them breathing room without touching DataTable/grid layout.
            addClassName(TOOLBAR_WRAP); addClassName(ITEM_TOOLBAR_ROW)
        }.also { appendAddItemButtons(it, invoice) }
    }
}
