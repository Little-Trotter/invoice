package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.ColumnTextAlign
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GlyphAppearance
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.clickableStatusGlyph
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import java.math.BigDecimal
import java.time.LocalDate

private const val ATTR_TITLE = "title"

/**
 * Column definitions, cell renderers and the column-key/sort-field mapping of [InvoiceListView]'s
 * grid. Stateless apart from its callbacks: the view owns the grid, the sort state and the
 * navigation; this class only knows how an [InvoiceOverviewDto] row is presented.
 */
internal class InvoiceListGridColumns(
    private val translations: TranslationProvider,
    private val onValidationTileClick: (Long) -> Unit,
) {

    private fun tr(key: String, vararg params: Any): String = translations.tr(key, *params)

    fun configure(grid: DataTable<InvoiceOverviewDto>) {
        grid.addClassName("invoice-table")
        grid.setSizeFull()
        grid.removeAllColumns()
        addInvoiceNumberColumn(grid)
        addInvoiceDateColumn(grid)
        addSellerColumn(grid)
        addRecipientColumn(grid)
        addStatusColumn(grid)
        addGrossAmountColumn(grid)
        addItemsCountColumn(grid)
        addValidationStatusColumn(grid)
        addReverseChargeColumn(grid)
        grid.element.setAttribute("aria-label", tr("invoice.table.ariaLabel"))
    }

    private fun addInvoiceNumberColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn({ inv -> inv.invoiceNumber.ifBlank { "-" } })
            .setHeader(tr("invoice.table.invoiceNumber"))
            .setSortable(true)
            .setSortProperty(KEY_INVOICE_NUMBER)
            .setKey(KEY_INVOICE_NUMBER)
            .asCompactColumn()
    }

    // Every Grid column starts with a no-op comparator unless setComparator(...) is
    // called; grid.sort(...) then builds an in-memory comparator that reduces to "always
    // equal" and the client may show same-date rows in a different order than the server's
    // `invoiceDate desc, id desc`. This comparator mirrors that backend tiebreaker (written
    // ascending; Grid reverses it for DESCENDING, NULLS LAST on ASC like Postgres).
    private fun addInvoiceDateColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn({ inv -> DateFormatter.format(inv.invoiceDate) })
            .setHeader(tr("invoice.table.date"))
            .setSortable(true)
            .setSortProperty(KEY_INVOICE_DATE)
            .setComparator(
                compareBy<InvoiceOverviewDto, LocalDate?>(nullsLast()) { it.invoiceDate }
                    .thenBy(nullsLast()) { it.id },
            )
            .setKey(KEY_INVOICE_DATE)
            .asCompactColumn()
    }

    private fun addSellerColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn({ inv -> inv.sellerName ?: "" })
            .setHeader(tr("invoice.table.seller"))
            .setSortable(true)
            .setSortProperty(KEY_SELLER)
            .setKey(KEY_SELLER)
            .asTextColumn(TextColumnWidth.MEDIUM)
    }

    private fun addRecipientColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn({ inv -> inv.recipientName ?: "-" })
            .setHeader(tr("invoice.table.recipient"))
            .setSortable(true)
            .setSortProperty(KEY_RECIPIENT)
            .setKey(KEY_RECIPIENT)
            .asTextColumn(TextColumnWidth.MEDIUM)
    }

    // Status chip; the header icon opens the legend dialog.
    private fun addStatusColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn(ComponentRenderer { inv: InvoiceOverviewDto ->
            buildStatusCell(inv)
        }).setHeader(buildStatusColumnHeader({ key -> tr(key) }) { openStatusLegend { key -> tr(key) } })
            .asCompactColumn().setTextAlign(ColumnTextAlign.CENTER).setSortable(true).setSortProperty(KEY_STATUS).apply { key = KEY_STATUS }
    }

    private fun addGrossAmountColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn({ inv -> formatCurrency(inv.grossAmount?.amount ?: BigDecimal.ZERO, inv.currency) })
            .setHeader(tr("invoice.table.amount"))
            .setSortable(true)
            .setSortProperty(KEY_GROSS_AMOUNT)
            .setKey(KEY_GROSS_AMOUNT)
            .asNumericColumn()
    }

    private fun addItemsCountColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn({ inv -> inv.itemsCount.toString() })
            .setHeader(tr("invoice.table.items"))
            .setSortable(true)
            .setSortProperty(KEY_ITEMS_COUNT)
            .setKey(KEY_ITEMS_COUNT)
            .asNumericColumn()
    }

    private fun addValidationStatusColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn(ComponentRenderer { inv: InvoiceOverviewDto ->
            buildValidationCell(inv.validationStatus, inv.id)
        }).setHeader(buildValidationColumnHeader({ key -> tr(key) }) { openValidationLegend { key -> tr(key) } })
            .asCompactColumn().setTextAlign(ColumnTextAlign.CENTER).setSortable(true).setSortProperty(KEY_VALIDATION_STATUS).apply { key = KEY_VALIDATION_STATUS }
    }

    private fun addReverseChargeColumn(grid: DataTable<InvoiceOverviewDto>) {
        grid.addColumn(ComponentRenderer { inv: InvoiceOverviewDto ->
            buildReverseChargeCell(inv.reverseCharge)
        }).setHeader(buildReverseChargeHeader())
            .asCompactColumn()
    }

    /** The status chip, plus a failure chip while the invoice's document generation failed (list-level cue for the detail's regenerate action). */
    fun buildStatusCell(inv: InvoiceOverviewDto): Component {
        val chip = buildStatusChip(inv.status, inv.typeCode)
        if (!inv.documentGenerationFailed) return chip
        val warning = StatusGlyph(VaadinIcon.WARNING, ChipTone.ERROR, tr("documentGeneration.failed.list")).apply {
            addClassName("document-generation-failed-marker")
        }
        return HorizontalLayout(chip, warning).apply {
            isSpacing = true
            isPadding = false
            justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        }
    }

    /** The Status cell: a symbol in status colour; label key shared with the detail cockpit. */
    fun buildStatusChip(invStatus: InvoiceStatus, typeCode: InvoiceTypeCode?): StatusGlyph =
        StatusGlyph(invoiceStatusGlyphIcon(invStatus), invoiceStatusTone(invStatus), tr(statusBadgeLabelKey(invStatus, typeCode)))

    /**
     * The Validierung cell: a [StatusGlyph], clickable whenever [invoiceId] is known
     * (opens the invoice straight on its validation area; `stopPropagation()` in
     * [clickableStatusGlyph] keeps the row click from firing too). `null` renders a plain,
     * non-interactive chip. UNAVAILABLE/NOT_VALIDATED/PENDING keep their own faces and tooltips.
     */
    fun buildValidationCell(vs: ValidationStatus, invoiceId: Long?): Component {
        val tone = validationStatusTone(vs)
        val icon = validationStatusGlyphIcon(vs)
        val pulsing = vs == ValidationStatus.PENDING
        val label = validationStatusLabel(vs) { key -> tr(key) }
        val tooltip = validationStatusTooltip(vs) { key -> tr(key) }
        if (invoiceId == null) {
            return StatusGlyph(icon, tone, label, pulsing).apply { element.setAttribute(ATTR_TITLE, tooltip) }
        }
        return clickableStatusGlyph(
            appearance = GlyphAppearance(icon, tone, label, pulsing),
            ariaLabel = "$label — ${tr("invoice.validation.openDetail")}",
            tooltip = tooltip,
        ) { onValidationTileClick(invoiceId) }
    }

    fun buildReverseChargeCell(reverseCharge: Boolean): Span {
        return if (reverseCharge) {
            StatusChip(tr("invoice.table.reverseCharge"), ChipTone.NEUTRAL).apply {
                element.setAttribute(ATTR_TITLE, tr("invoice.table.reverseCharge.tooltip"))
            }
        } else {
            Span()
        }
    }

    fun buildReverseChargeHeader(): Span {
        val headerLabel = Span(tr("invoice.table.reverseCharge"))
        headerLabel.element.setAttribute(ATTR_TITLE, tr("invoice.table.reverseCharge.tooltip"))
        return headerLabel
    }

    /** Every call site passes the invoice's own currency. */
    fun formatCurrency(amount: BigDecimal, currency: Currency): String = MoneyFormatter.format(amount, currency)

    companion object {
        private const val KEY_INVOICE_NUMBER = "invoiceNumber"
        private const val KEY_INVOICE_DATE = "invoiceDate"
        private const val KEY_SELLER = "seller"
        private const val KEY_RECIPIENT = "recipient"
        private const val KEY_ITEMS_COUNT = "itemsCount"
        private const val KEY_GROSS_AMOUNT = "grossAmount"
        private const val KEY_STATUS = "status"
        private const val KEY_VALIDATION_STATUS = "validationStatus"

        /** Grid column key → backend sort field; an unknown or absent key sorts by invoice date. */
        fun sortFieldForColumnKey(key: String?): InvoiceOverviewSortField = when (key) {
            KEY_INVOICE_NUMBER -> InvoiceOverviewSortField.INVOICE_NUMBER
            KEY_SELLER -> InvoiceOverviewSortField.SELLER_NAME
            KEY_RECIPIENT -> InvoiceOverviewSortField.RECIPIENT_NAME
            KEY_ITEMS_COUNT -> InvoiceOverviewSortField.ITEMS_COUNT
            KEY_GROSS_AMOUNT -> InvoiceOverviewSortField.GROSS_AMOUNT
            KEY_STATUS -> InvoiceOverviewSortField.STATUS
            KEY_VALIDATION_STATUS -> InvoiceOverviewSortField.VALIDATION_STATUS
            else -> InvoiceOverviewSortField.INVOICE_DATE
        }

        /** Backend sort field → grid column key, the inverse of [sortFieldForColumnKey]. */
        fun columnKeyForSortField(field: InvoiceOverviewSortField): String = when (field) {
            InvoiceOverviewSortField.INVOICE_NUMBER -> KEY_INVOICE_NUMBER
            InvoiceOverviewSortField.SELLER_NAME -> KEY_SELLER
            InvoiceOverviewSortField.RECIPIENT_NAME -> KEY_RECIPIENT
            InvoiceOverviewSortField.ITEMS_COUNT -> KEY_ITEMS_COUNT
            InvoiceOverviewSortField.GROSS_AMOUNT -> KEY_GROSS_AMOUNT
            InvoiceOverviewSortField.STATUS -> KEY_STATUS
            InvoiceOverviewSortField.VALIDATION_STATUS -> KEY_VALIDATION_STATUS
            InvoiceOverviewSortField.INVOICE_DATE -> KEY_INVOICE_DATE
        }
    }
}
