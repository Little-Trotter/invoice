package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.component.AmountPreview
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.PdfTextValidation
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal
import java.math.RoundingMode

private const val ARTICLE_LOOKUP_LIMIT = 50
private const val DESCRIPTION_MIN_ROWS = 3
private const val PERCENT = 100
private const val MONEY_SCALE = 2
private const val DEFAULT_VAT_RATE = 19.0

/** Net, VAT and gross of one line as the dialog previews them; the offer component computes the authoritative amounts. */
internal fun previewAmounts(quantity: BigDecimal, unitPrice: BigDecimal, ratePercent: BigDecimal): Triple<BigDecimal, BigDecimal, BigDecimal> {
    val net = quantity.multiply(unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
    val vat = net.multiply(ratePercent).divide(BigDecimal(PERCENT), MONEY_SCALE, RoundingMode.HALF_UP)
    return Triple(net, vat, net.add(vat))
}

/**
 * Shared scaffold of the offer's item dialogs: the same field order, CSS classes and footer as the
 * invoice's item dialogs, without what an offer has no use for (B2B terms, the customer's own
 * article number, delivery-note reference). A subclass adds the fields of its position kind; the
 * result is a [QuoteItemInput] handed to [onSave]. The article picker only offers articles of the
 * dialog's own kind, so a pick can never put a unit on the line that the kind forbids.
 */
internal abstract class QuoteBaseItemDialog(
    protected val host: TranslationProvider,
    private val articles: QuoteArticleLookupQueryPort,
    private val quoteId: String,
    private val existing: QuoteItem?,
    private val onSave: (QuoteItemInput) -> Unit,
) {

    internal val dialog = AppDialog("", AppDialog.Size.M)
    internal val articleBox = ComboBox<QuoteArticleOption>()
    internal val articleNumber = TextField(host.tr("article.articleNumber")).apply {
        setWidthFull()
        isReadOnly = true
    }
    internal val description = TextArea().apply {
        setWidthFull()
        isRequired = true
        minRows = DESCRIPTION_MIN_ROWS
    }
    internal val quantity = NumberField(host.tr("invoiceDetail.itemDialog.quantity")).apply {
        value = 1.0
        min = 0.0
        setWidthFull()
    }
    internal val price = NumberField().apply {
        suffixComponent = Span("€")
        setWidthFull()
    }
    internal val vatCategory = ComboBox<VatCategory>(host.tr("quote.items.vatCategory")).apply {
        setItems(VatCategory.entries)
        setItemLabelGenerator { it.code }
        isAllowCustomValue = false
        value = VatCategory.STANDARD
        setWidthFull()
    }
    internal val vatRate = NumberField(host.tr("invoiceDetail.itemDialog.taxRate")).apply {
        value = DEFAULT_VAT_RATE
        min = 0.0
        suffixComponent = Span("%")
        setWidthFull()
    }
    internal val preview = AmountPreview(Currency.EUR)
    internal val cancelButton = AppButtons.tertiary(host.tr("app.cancel")) { dialog.close() }
    internal val saveButton = AppButtons.primary(host.tr("app.save")) { save() }

    /** The article last picked; its link and the customer's own reference travel with the saved line. */
    private var picked: QuoteArticleOption? = null

    protected abstract val category: InvoiceItemCategory

    protected abstract fun titleKey(): String

    protected abstract fun articleLabelKey(): String

    protected abstract fun descriptionLabelKey(): String

    protected abstract fun priceLabelKey(): String

    /** The kind's own inputs (unit and quantity) in the order they appear between the description and the price. */
    protected abstract fun unitFields(): List<Component>

    protected abstract fun selectedUnit(): InvoiceItemUnit

    protected abstract fun selectUnit(unit: InvoiceItemUnit)

    protected abstract fun wireUnitListeners(onChange: () -> Unit)

    /** The quantity that counts for the line; a kind without a quantity input answers 1. */
    protected open fun effectiveQuantity(): Double? = quantity.value

    fun open() {
        dialog.headerTitle = host.tr(titleKey())
        configureArticleBox()
        description.label = host.tr(descriptionLabelKey())
        price.label = host.tr(priceLabelKey())
        existing?.let { load(it) }
        listOf(quantity, price, vatRate).forEach { field -> field.addValueChangeListener { refresh() } }
        wireUnitListeners { refresh() }
        refresh()
        dialog.add(form(), preview)
        dialog.footer(cancelButton, saveButton)
        dialog.open()
    }

    private fun form(): FormLayout {
        val fields = listOf(articleBox, articleNumber, description) + unitFields() + listOf(price, vatCategory, vatRate)
        return FormLayout(*fields.toTypedArray()).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("500px", 2))
            setColspan(articleBox, 2)
            setColspan(description, 2)
        }
    }

    private fun configureArticleBox() {
        articleBox.label = host.tr(articleLabelKey())
        articleBox.placeholder = host.tr("invoiceDetail.goods.selectArticle.placeholder")
        articleBox.addClassName("article-select-combo")
        articleBox.setWidthFull()
        articleBox.setItemLabelGenerator { "${it.articleNumber} · ${it.description}" }
        articleBox.setItems { query ->
            val text = query.filter.orElse("")
            articles.articles(quoteId, category, text, query.offset + minOf(query.limit, ARTICLE_LOOKUP_LIMIT)).drop(query.offset).stream()
        }
        articleBox.addValueChangeListener { event -> event.value?.let { prefill(it) } }
    }

    private fun prefill(article: QuoteArticleOption) {
        picked = article
        articleNumber.value = article.articleNumber
        description.value = article.description
        selectUnit(article.unit)
        price.value = article.unitPrice.amount.toDouble()
        vatCategory.value = article.vatCategory
        vatRate.value = article.vatRate.toDouble()
        refresh()
    }

    private fun load(item: QuoteItem) {
        articleNumber.value = item.articleNumber.orEmpty()
        description.value = item.description
        quantity.value = item.quantity.toDouble()
        selectUnit(item.unit)
        price.value = item.unitPrice.amount.toDouble()
        vatCategory.value = item.vatCategory
        vatRate.value = item.vatRate.toDouble()
    }

    private fun refresh() {
        val rate = BigDecimal.valueOf(vatRate.value ?: 0.0)
        val (net, vat, gross) = previewAmounts(BigDecimal.valueOf(effectiveQuantity() ?: 0.0), BigDecimal.valueOf(price.value ?: 0.0), rate)
        preview.update(net, vat, gross, rate)
    }

    /** Validates the form; on success hands the line to [onSave] and closes. Answers whether the line was accepted. */
    internal fun save(): Boolean {
        val text = description.value.trim()
        val qty = effectiveQuantity()
        val unitPrice = price.value
        val rate = vatRate.value
        if (rejectInput(text, qty, unitPrice, rate)) return false
        onSave(toInput(text, BigDecimal.valueOf(requireNotNull(qty)), BigDecimal.valueOf(requireNotNull(unitPrice)), BigDecimal.valueOf(requireNotNull(rate))))
        dialog.close()
        return true
    }

    /** Flags the first unusable input on its own field; true when the line must not be saved. */
    private fun rejectInput(text: String, qty: Double?, unitPrice: Double?, rate: Double?): Boolean {
        val descriptionProblem = if (text.isBlank()) host.tr("invoiceDetail.itemDialog.error.descriptionRequired") else null
        when {
            descriptionProblem != null -> UiNotifications.errorOn(description, descriptionProblem)
            qty == null || qty <= 0.0 -> UiNotifications.errorOn(quantity, host.tr("quote.items.dialog.error.quantity"))
            unitPrice == null || unitPrice < 0.0 -> UiNotifications.errorOn(price, host.tr("quote.items.dialog.error.price"))
            rate == null || rate < 0.0 -> UiNotifications.errorOn(vatRate, host.tr("quote.items.dialog.error.rate"))
            else -> {
                val pdfProblem = PdfTextValidation.message({ host.tr(it) }, text) ?: return false
                UiNotifications.errorOn(description, pdfProblem)
            }
        }
        return true
    }

    private fun toInput(text: String, qty: BigDecimal, unitPrice: BigDecimal, rate: BigDecimal): QuoteItemInput {
        val link = picked
        return QuoteItemInput(
            description = text,
            quantity = qty,
            category = category,
            unit = selectedUnit(),
            unitPrice = Money.of(unitPrice),
            vatCategory = requireNotNull(vatCategory.value),
            vatRate = rate,
            id = existing?.id,
            articleId = link?.id ?: existing?.articleId,
            articleNumber = link?.articleNumber ?: existing?.articleNumber,
            customerReference = if (link != null) link.customerReference else existing?.customerReference,
            customerArticleName = if (link != null) link.customerArticleName else existing?.customerArticleName,
        )
    }
}
