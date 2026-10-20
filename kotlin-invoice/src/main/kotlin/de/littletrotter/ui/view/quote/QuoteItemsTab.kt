package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.quote.port.inbound.EditQuoteItemsUseCase
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ChipSpec
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.GridScrollBox
import de.littletrotter.ui.component.StatusQuickFilterRow
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.masterdata.ServiceCatalogSection

private const val TOOLBAR_WRAP = "toolbar-wrap"

/** What the items tab needs from the offer component; bundled to keep the tab's constructor short, each member stays one narrow port. */
internal class QuoteItemsTabPorts(
    val detail: QuoteDetailQueryPort,
    val articles: QuoteArticleLookupQueryPort,
    val editItems: EditQuoteItemsUseCase,
    val attachments: QuoteItemAttachmentPorts,
)

/**
 * The "Positionen" tab, laid out like the invoice's: a toolbar with the kind filter chips and a
 * small summary on the left and the three add buttons (Material, Einheitspreis, Dienstleistung;
 * never a business trip) on the right, above the grid and the totals. Everything done here is
 * stored at once: a dialog's OK, a deletion and an inline edit send the complete list of lines
 * through the edit use case and reload the offer, so there is no separate save step and the
 * editor's sticky bar only covers header, texts and references. [onSaved] lets the editor refresh
 * what depends on the lines (title row, attachment choices).
 */
internal class QuoteItemsTab(
    private val host: TranslationProvider,
    private val ports: QuoteItemsTabPorts,
    private val onSaved: (QuoteDetail) -> Unit,
) : VerticalLayout() {

    internal val itemsGrid = QuoteItemsGrid(host, editable = true)
    internal val grid get() = itemsGrid.grid
    internal val totals = QuoteTotalsPanel(host)
    internal val addButtons: List<Button> = ServiceCatalogSection.catalogueEntries.map { section -> addButton(section) }
    internal val summary = Span().apply { addClassName("text-muted") }
    internal val chips = StatusQuickFilterRow(FILTER_CATEGORIES, host.tr("invoiceDetail.items.filter.groupLabel"), this::chipSpec, this::toggleFilter)
    internal val toolbar = HorizontalLayout()

    private val labels = QuoteLabels(host)
    private var quoteId = ""
    private var items: List<QuoteItem> = emptyList()
    private var attachments: List<QuoteAttachment> = emptyList()
    internal var filter: InvoiceItemCategory? = null
        private set

    init {
        isPadding = true
        setWidthFull()
        grid.addClassName("quote-item-grid")
        buildToolbar()
        itemsGrid.onEdit = { openDialog(it) }
        itemsGrid.onDelete = { item -> commit(items.filter { it.id != item.id }.map { it.toInput() }) }
        itemsGrid.onInlineEdit = { item, input -> commit(items.map { if (it.id == item.id) input else it.toInput() }) }
        itemsGrid.onAttachments = { item ->
            QuoteItemAttachmentsDialog(host, quoteId, item, ports.attachments) { refreshAttachments() }.open()
        }
        // The same hint the invoice shows: the row editor is only discoverable by double-click.
        add(toolbar, GridScrollBox(grid), Span(host.tr("quote.items.inlineEdit.hint")).apply { addClassName("hint-text") }, totals)
        setHorizontalComponentAlignment(Alignment.END, totals)
    }

    /** The lines as last loaded, for the attachment choices of the editor's other tab. */
    fun currentItems(): List<QuoteItem> = items

    fun bind(detail: QuoteDetail) {
        quoteId = detail.id
        items = detail.items.sortedBy { it.position }
        totals.render(detail)
        attachments = guarded { ports.attachments.query.attachments(quoteId) } ?: emptyList()
        render()
    }

    /** Reloads the attachment counts, e.g. after files were added or moved elsewhere. */
    fun refreshAttachments() {
        attachments = guarded { ports.attachments.query.attachments(quoteId) } ?: emptyList()
        render()
    }

    private fun buildToolbar() {
        val left = HorizontalLayout(summary, chips).apply {
            isPadding = false
            defaultVerticalComponentAlignment = Alignment.CENTER
            addClassName(TOOLBAR_WRAP)
        }
        val right = HorizontalLayout(*addButtons.toTypedArray()).apply {
            isPadding = false
            addClassName(TOOLBAR_WRAP)
        }
        toolbar.add(left, right)
        toolbar.setWidthFull()
        toolbar.defaultVerticalComponentAlignment = Alignment.CENTER
        toolbar.justifyContentMode = JustifyContentMode.BETWEEN
        toolbar.addClassNames(TOOLBAR_WRAP, "item-toolbar-row")
    }

    private fun addButton(section: ServiceCatalogSection): Button =
        AppButtons.secondary(host.tr("invoiceDetail.add.category", host.tr(section.labelKey))) { openDialog(null, section.category) }.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            element.setAttribute("data-add-category", section.category.name)
        }

    private fun render() {
        if (filter != null && items.none { it.category == filter }) filter = null
        itemsGrid.render(items.filter { filter == null || it.category == filter }, attachments)
        chips.render(FILTER_CATEGORIES.associateWith { category -> items.count { it.category == category }.toLong() }, filter)
        summary.text = host.tr("quote.items.toolbar.summary", items.size, labels.money(totalNet()))
    }

    private fun totalNet() = items.fold(Money.ZERO) { sum, item -> sum + item.net }

    private fun toggleFilter(category: InvoiceItemCategory) {
        filter = if (filter == category) null else category
        render()
    }

    private fun chipSpec(category: InvoiceItemCategory): ChipSpec {
        val label = host.tr(ServiceCatalogSection.forCategory(category).labelKey)
        val glyph = when (category) {
            InvoiceItemCategory.PRODUCT -> VaadinIcon.CUBE
            InvoiceItemCategory.UNIT_PRICE -> VaadinIcon.TAG
            else -> VaadinIcon.TOOLS
        }
        return ChipSpec(label, ChipTone.NEUTRAL, glyph) { count -> host.tr("quote.items.filter.countAria", label, count.toString()) }
    }

    /** Opens the dialog of [category] for a new line, or for [existing] in the dialog of its own kind. */
    internal fun openDialog(existing: QuoteItem?, category: InvoiceItemCategory = requireNotNull(existing).category) {
        val save: (QuoteItemInput) -> Unit = { input ->
            commit(if (existing == null) items.map { it.toInput() } + input else items.map { if (it.id == existing.id) input else it.toInput() })
        }
        when (category) {
            InvoiceItemCategory.SERVICE -> QuoteServiceItemDialog(host, ports.articles, quoteId, existing, save).open()
            else -> QuoteGoodsItemDialog(host, ports.articles, quoteId, category, existing, save).open()
        }
    }

    /** Stores [lines] as the offer's complete list of lines and reloads; a refusal leaves the grid as it was. */
    private fun commit(lines: List<QuoteItemInput>) {
        if (guarded { ports.editItems.editItems(quoteId, lines) } == null) {
            render()
            return
        }
        UiNotifications.success(host.tr("quote.items.saved"))
        val detail = guarded { ports.detail.find(quoteId) } ?: return
        bind(detail)
        onSaved(detail)
    }
}

/** The kinds an offer line can have; business trips are not offered. */
private val FILTER_CATEGORIES = ServiceCatalogSection.catalogueEntries.map { it.category }
