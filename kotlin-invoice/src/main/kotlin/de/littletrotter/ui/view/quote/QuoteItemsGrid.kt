package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.Money
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.RowEditButtons
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.wireBufferedEditorKeys
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.InvoiceUnitLabels
import java.math.BigDecimal

private const val RATE_FIELD_WIDTH = "5rem"
private const val CATEGORY_FIELD_WIDTH = "5.5rem"

/** The editable fields of one line while its row editor is open; null means the user cleared the field. */
private class PendingEdit(
    var quantity: BigDecimal? = null,
    var unitPrice: Money? = null,
    var vatRate: BigDecimal? = null,
    var vatCategory: VatCategory? = null,
)

/** The line as the edit command takes it, so a single changed field can be sent together with all other lines. */
internal fun QuoteItem.toInput(): QuoteItemInput = QuoteItemInput(
    description = description,
    quantity = quantity,
    category = category,
    unit = unit,
    unitPrice = unitPrice,
    vatCategory = vatCategory,
    vatRate = vatRate,
    id = id,
    articleId = articleId,
    articleNumber = articleNumber,
    customerReference = customerReference,
    customerArticleName = customerArticleName,
)

/**
 * The lines of an offer, in the column set of the invoice's item grid: position, description (title
 * plus a small grey line with article number and the customer's own references), the paperclip
 * with the number of attachments, quantity, unit, net unit price, VAT, net and, last, the row
 * actions. Read-only (detail view) it shows no actions and plain text; editable (editor) the rows
 * stay plain text until a double-click opens the grid's buffered row editor for quantity, unit
 * price and VAT (rate and category): Enter saves, Escape cancels, and a save reports the whole
 * changed line once through [onInlineEdit]. Edit/delete go through [onEdit]/[onDelete]. The grid
 * itself never writes anything.
 */
internal class QuoteItemsGrid(private val host: TranslationProvider, editable: Boolean = false) {

    internal val grid = DataTable(QuoteItem::class.java)

    internal var onEdit: (QuoteItem) -> Unit = {}
    internal var onDelete: (QuoteItem) -> Unit = {}
    internal var onInlineEdit: (QuoteItem, QuoteItemInput) -> Unit = { _, _ -> }
    internal var onAttachments: (QuoteItem) -> Unit = {}

    private val labels = QuoteLabels(host)
    private var attachmentCounts: Map<String?, Int> = emptyMap()
    private val pending = PendingEdit()

    init {
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, host.tr(if (editable) "quote.items.editor.ariaLabel" else "quote.items.ariaLabel"))
        grid.addColumn { it.position }.setHeader(host.tr("quote.items.position")).setKey("position").asCompactColumn()
        grid.addComponentColumn { describe(it) }.setHeader(host.tr("quote.items.description")).setKey("description").asTextColumn(TextColumnWidth.LARGE)
        grid.addComponentColumn { paperclip(it) }.setHeader(host.tr("quote.items.column.attachments")).setKey("attachments").asCompactColumn()
        val quantityColumn = numeric("quantity", "quote.items.quantity") { Span(labels.number(it.quantity)) }
        grid.addColumn { InvoiceUnitLabels.label(grid, it.unit) }.setHeader(host.tr("quote.items.unit")).setKey("unit").asCompactColumn()
        val priceColumn = numeric("unitPrice", "quote.items.unitPrice") { Span(labels.money(it.unitPrice)) }
        val vatColumn = numeric("vat", "quote.items.vat") { Span("${labels.number(it.vatRate)} %") }
        numeric("net", "quote.items.net") { Span(labels.money(it.net)) }
        if (editable) {
            val actionsColumn = grid.addComponentColumn { actions(it) }.setKey("actions").asActionColumn()
            wireEditor(quantityColumn, priceColumn, vatColumn, actionsColumn)
        }
    }

    /**
     * The row editor is buffered: the fields write into [pending] only when the row is saved, so a
     * cancelled edit never leaves a half-changed line behind; a save then reports all four values
     * at once.
     */
    private fun wireEditor(
        quantityColumn: Grid.Column<QuoteItem>,
        priceColumn: Grid.Column<QuoteItem>,
        vatColumn: Grid.Column<QuoteItem>,
        actionsColumn: Grid.Column<QuoteItem>,
    ) {
        val binder = Binder<QuoteItem>()
        grid.editor.binder = binder
        grid.editor.isBuffered = true

        val quantityField = NumberField().apply { min = 0.0; setWidthFull() }
        binder.forField(quantityField).bind(
            { it.quantity.toDouble() },
            { _, v -> pending.quantity = v?.let(BigDecimal::valueOf) },
        )
        quantityColumn.editorComponent = quantityField

        val priceField = NumberField().apply { min = 0.0; setWidthFull() }
        binder.forField(priceField).bind(
            { it.unitPrice.amount.toDouble() },
            { _, v -> pending.unitPrice = v?.let { Money.of(BigDecimal.valueOf(it)) } },
        )
        priceColumn.editorComponent = priceField

        val rateField = NumberField().apply { min = 0.0; width = RATE_FIELD_WIDTH }
        binder.forField(rateField).bind(
            { it.vatRate.toDouble() },
            { _, v -> pending.vatRate = v?.let(BigDecimal::valueOf) },
        )
        val categoryBox = ComboBox<VatCategory>().apply {
            setItems(VatCategory.entries)
            setItemLabelGenerator { it.code }
            width = CATEGORY_FIELD_WIDTH
            isAllowCustomValue = false
            element.setAttribute(ARIA_LABEL, host.tr("quote.items.vatCategory"))
        }
        binder.forField(categoryBox).bind({ it.vatCategory }, { _, v -> pending.vatCategory = v })
        vatColumn.editorComponent = HorizontalLayout(rateField, categoryBox).apply {
            isPadding = false
            isSpacing = true
        }
        actionsColumn.editorComponent = editorActions()

        grid.wireBufferedEditorKeys(listOf(quantityField, priceField, rateField, categoryBox))
        grid.editor.addSaveListener { event -> save(event.item) }
        grid.addItemDoubleClickListener { event ->
            event.item?.takeUnless { grid.editor.isOpen }?.let { grid.editor.editItem(it) }
        }
    }

    private fun editorActions(): Component =
        HorizontalLayout(
            RowEditButtons.confirm { grid.editor.save() },
            RowEditButtons.cancel { grid.editor.cancel() },
        ).apply {
            isPadding = false
            isSpacing = true
        }

    /** A cleared or impossible value is never sent; the row is redrawn with the saved value. */
    private fun save(item: QuoteItem) {
        val edit = pending
        val problem = when {
            edit.quantity == null || edit.quantity!!.signum() != 1 -> "QUANTITY"
            edit.unitPrice == null -> "PRICE"
            edit.vatRate == null -> "RATE"
            else -> null
        }
        if (problem != null) {
            reject(item, problem)
            return
        }
        onInlineEdit(
            item,
            item.toInput().copy(
                quantity = edit.quantity!!,
                unitPrice = edit.unitPrice!!,
                vatRate = edit.vatRate!!,
                vatCategory = edit.vatCategory ?: item.vatCategory,
            ),
        )
    }

    /** Shows [items]; [attachments] only feed the paperclip counts of the lines they belong to. */
    fun render(items: List<QuoteItem>, attachments: List<QuoteAttachment> = emptyList()) {
        attachmentCounts = attachments.groupingBy { it.itemId }.eachCount()
        grid.setItems(items)
    }

    private fun numeric(key: String, headerKey: String, cell: (QuoteItem) -> Component): Grid.Column<QuoteItem> =
        grid.addComponentColumn { cell(it) }.setHeader(host.tr(headerKey)).setKey(key).asNumericColumn()

    private fun describe(item: QuoteItem): Div {
        val cell = Div(Span(item.description).apply { addClassName("quote-item__title") }).apply { addClassName("quote-item") }
        detailLine(item)?.let { cell.add(Span(it).apply { addClassName("quote-item__detail") }) }
        return cell
    }

    /** The grey second line; absent when the line carries no article number or customer reference. */
    internal fun detailLine(item: QuoteItem): String? =
        listOfNotNull(
            item.articleNumber,
            item.customerReference?.let { host.tr("quote.items.customerReference", it) },
            item.customerArticleName?.let { host.tr("quote.items.customerArticleName", it) },
        ).joinToString(" · ").ifEmpty { null }

    private fun paperclip(item: QuoteItem): Component {
        val count = attachmentCounts[item.id] ?: 0
        val label = if (count > 0) host.tr("quote.items.attachments.count", count.toString()) else host.tr("quote.items.attachments.none")
        return (if (count > 0) AppButtons.primary(label, ActionIcons.ATTACHMENTS) { onAttachments(item) } else AppButtons.tertiary(label, ActionIcons.ATTACHMENTS) { onAttachments(item) }).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
            addClassName("invoice-item-receipts")
        }
    }

    /** A cleared or impossible value is never sent; the row is redrawn with the saved value. */
    private fun reject(item: QuoteItem, problem: String) {
        UiNotifications.warning(host.tr("quote.items.problem.$problem", item.position))
        grid.dataProvider.refreshItem(item)
    }

    private fun actions(item: QuoteItem): Component =
        HorizontalLayout(
            AppButtons.icon(ActionIcons.EDIT, host.tr("button.edit")) { onEdit(item) }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
            },
            AppButtons.icon(ActionIcons.DELETE, host.tr("app.delete")) { onDelete(item) }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
            },
        ).apply {
            isPadding = false
            isSpacing = true
        }
}
