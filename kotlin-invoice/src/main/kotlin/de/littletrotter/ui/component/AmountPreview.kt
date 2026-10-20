package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Hr
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.shared.Currency
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.formatCurrency
import java.math.BigDecimal

/**
 * Right-aligned three-line block for amount previews in item dialogs: net / VAT (with percentage) /
 * gross as separate lines with their own amount column (CSS: .lt-amount-preview). Shared by the
 * invoice and the offer item dialogs; it only needs the [currency] and translates through itself.
 */
internal class AmountPreview(private val currency: Currency) : VerticalLayout(), TranslationProvider {
    override fun getComponent(): Component = this

    private val netLine = amountLine()
    private val vatLine = amountLine()
    private val grossLine = amountLine(emphasized = true)

    init {
        isPadding = false; isSpacing = false
        addClassName("lt-amount-preview")
        add(netLine.row, vatLine.row, Hr(), grossLine.row)
    }

    fun update(net: BigDecimal, vat: BigDecimal, gross: BigDecimal, taxRatePercent: BigDecimal) {
        netLine.set(tr("invoiceDetail.netAmount"), net)
        vatLine.set("${tr("invoiceDetail.vatAmount")} (${taxRatePercent.stripTrailingZeros().toPlainString()} %)", vat)
        grossLine.set(tr("invoiceDetail.grossAmount"), gross)
    }

    private fun amountLine(emphasized: Boolean = false): AmountPreviewLine {
        val label = Span().apply { addClassName("label") }
        val amount = Span().apply { addClassName("amount") }
        val row = HorizontalLayout(label, amount).apply {
            isPadding = false; isSpacing = false
            setWidthFull()
            addClassName("row")
            if (emphasized) addClassName("emphasized")
        }
        return AmountPreviewLine(row, label, amount, currency)
    }
}

private class AmountPreviewLine(
    val row: HorizontalLayout,
    val label: Span,
    val amount: Span,
    val currency: Currency,
) {
    fun set(labelText: String, value: BigDecimal) {
        label.text = labelText
        amount.text = formatCurrency(value, currency)
    }
}
