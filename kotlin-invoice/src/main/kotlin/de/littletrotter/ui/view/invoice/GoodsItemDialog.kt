package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ArticleDto
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextFieldBase
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.applyArticleSelectPresentation
import de.littletrotter.ui.component.lazyItems
import java.math.BigDecimal

/**
 * The "Ware" item dialog. Goods-specific field building, form layout, and article selection.
 */
internal class GoodsItemDialog(
    recipientQueryPort: RecipientQueryPort,
    itemEntryQueryPort: ItemEntryQueryPort,
    invoice: InvoiceDetailDto,
    existingItem: ItemDto? = null,
    private val category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT,
    defaultTaxRate: () -> TaxRateDto?,
    onSaveNew: (InvoiceItemCommand) -> Long?,
    onUpdate: (InvoiceItemCommand) -> Unit,
    today: java.time.LocalDate,
) : BaseItemDialog(
    recipientQueryPort, itemEntryQueryPort, invoice, existingItem, defaultTaxRate, ItemSaveCallbacks(onSaveNew, onUpdate), today,
) {

    private val articleQueries get() = itemEntryQueryPort

    private lateinit var descField: TextArea
    private lateinit var itemDescriptionField: TextArea
    private lateinit var refField: TextField
    private lateinit var qtyField: NumberField
    private lateinit var unitBox: ComboBox<InvoiceItemUnit>
    private lateinit var priceField: NumberField
    private lateinit var articleSelect: ComboBox<ArticleDto>
    private lateinit var articleNumberField: TextField

    override fun getItemCategory(): InvoiceItemCategory = category

    override fun getDialogTitleKey(): String = "invoiceDetail.goods.dialog.title"

    override fun buildCategoryFields() {
        descField = TextArea(tr("invoiceDetail.goods.itemName")).apply { setWidthFull(); isRequired = true; minRows = 2; placeholder = "z. B. Hydraulikpumpe Typ X" }
        itemDescriptionField = TextArea(tr("invoiceDetail.itemDialog.itemDescription")).apply {
            setWidthFull(); minRows = 2
            helperText = tr("invoiceDetail.itemDialog.itemDescription.helper")
        }
        refField = TextField(tr("invoiceDetail.goods.deliveryRef")).apply { setWidthFull(); maxLength = 100; placeholder = "LS-2025-0042" }
        qtyField = NumberField(tr("invoiceDetail.itemDialog.quantity")).apply { value = 1.0; min = 0.0; setWidthFull() }
        unitBox = ComboBox<InvoiceItemUnit>(tr("invoiceDetail.itemDialog.unit")).apply {
            val allowedUnits = LeistungsverzeichnisRules.allowedUnits(category).sortedBy { it.ordinal }
            setItems(allowedUnits)
            setItemLabelGenerator { InvoiceUnitLabels.label(dlg, it) }
            value = allowedUnits.firstOrNull() ?: InvoiceItemUnit.PIECE; setWidthFull()
        }
        priceField = NumberField(tr("invoiceDetail.itemDialog.unitPriceNet")).apply {
            suffixComponent = Span("€"); setWidthFull()
        }
        articleSelect = ComboBox<ArticleDto>(tr("invoiceDetail.goods.selectArticle")).apply {
            setWidthFull(); placeholder = tr("invoiceDetail.goods.selectArticle.placeholder")
            addClassName("article-select-combo")
            // Was findAllArticles() + client-side category filter, capped at
            // page-size.reference-data.lookup (1000). The category now constrains the query itself.
            lazyItems(
                count = { filter -> articleQueries.countArticlesForSelect(category, filter) },
                fetch = { filter, offset, limit -> articleQueries.findArticlesForSelect(category, filter, offset, limit) },
            )
            // Number/name/description search: shared two-line rows + "number · name" label.
            applyArticleSelectPresentation { key -> tr(key) }
        }
        articleNumberField = buildArticleNumberField { key -> tr(key) }

        // Locks quantity to 1 while the unit is EUR, so an EUR-priced position can't drift
        // into a meaningless multi-unit amount.
        applyEuroQuantityGuardState(qtyField, unitBox) { key -> tr(key) }
        wireEuroQuantityGuard(qtyField, unitBox) { key -> tr(key) }
    }

    override fun loadExistingItemIntoFields(item: ItemDto) {
        descField.value = item.description
        itemDescriptionField.value = item.itemDescription.orEmpty()
        refField.value = item.deliveryNoteReference.orEmpty()
        qtyField.value = item.quantity.toDouble()
        unitBox.value = item.unitEnum()
        priceField.value = (item.unitPriceNet ?: Money.ZERO).amount.toDouble()
        loadArticleIntoFields(articleQueries, item.articleRefId, articleSelect, articleNumberField)
    }

    override fun buildFormLayout(buyerItemNumberField: TextField, buyerItemDescriptionField: TextField, taxGroup: ComboBox<TaxRateDto>): FormLayout {
        // taxGroup sits directly after priceField: it is what turns the net price into the amount
        // the preview below shows, so it belongs next to the price, not at the end of the form.
        return FormLayout(
            articleSelect, articleNumberField, descField, itemDescriptionField, refField, qtyField, unitBox, priceField,
            taxGroup, buyerItemNumberField, buyerItemDescriptionField,
        ).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("500px", 2))
            applyColspan(
                this, 2,
                articleSelect, descField, itemDescriptionField, refField, taxGroup, buyerItemNumberField, buyerItemDescriptionField,
            )
        }
    }

    override fun wireRefreshListeners(
        taxGroup: ComboBox<TaxRateDto>,
        taxRulePanel: TaxRuleProposalPanel?,
        refresh: () -> Unit,
        refreshAdvice: () -> Unit,
    ) {
        // qtyField feeds both callbacks — one registration, refresh before refreshAdvice
        // (same order the two separate listeners used to fire in).
        qtyField.addValueChangeListener { refresh.invoke(); refreshAdvice.invoke() }
        priceField.addValueChangeListener { refresh.invoke() }
        taxGroup.addValueChangeListener { taxRulePanel?.onSelectedRateChanged(it.value); refresh.invoke() }

        descField.addValueChangeListener { refreshAdvice.invoke() }
        unitBox.addValueChangeListener { refreshAdvice.invoke() }
    }

    override fun applyArticleSelection(
        article: ArticleDto,
        taxGroup: ComboBox<TaxRateDto>,
        taxRulePanel: TaxRuleProposalPanel?,
        refresh: () -> Unit,
    ) {
        val recipientRef = resolveArticleRecipientRef(articleQueries, invoice, article.id)
        articleNumberField.value = article.articleNumber.orEmpty()
        descField.value = if (existingItem == null) newPositionDescriptionFor(recipientRef, article.name) else article.name
        refField.value = article.description ?: ""
        qtyField.value = article.defaultQuantity.toDouble().takeIf { it > 0 } ?: 1.0
        unitBox.value = article.defaultUnit
        priceField.value = article.defaultUnitPriceNet.amount.toDouble()
        taxGroup.value = resolveArticleTaxRate(
            article, category, taxRulePanel,
            ArticleTaxContext(reverseChargeLocked, lockedRate, sellerCountry, fallbackTaxRate, articleQueries.findAllTaxRates()),
        )
        refresh.invoke()
    }

    override fun collectItemCommand(): InvoiceItemCommand {
        val desc = descField.value.trim()
        val ref = refField.value.trim()
        val qty = BigDecimal.valueOf(qtyField.value ?: 1.0)
        val price = BigDecimal.valueOf(priceField.value ?: 0.0)
        val deliveryRef = ref.ifBlank { null }

        return InvoiceItemCommand(
            itemId = existingItem?.id,
            category = category,
            description = desc,
            itemDescription = itemDescriptionField.value.trim().ifBlank { null },
            deliveryNoteReference = deliveryRef,
            quantity = qty,
            unit = unitBox.value ?: InvoiceItemUnit.PIECE,
            unitPriceNet = Money(price),
            articleId = articleSelect.value?.id,
            taxRateId = null, // Will be set by base class
        )
    }

    override fun collectQuantityAndPrice(): Pair<BigDecimal, BigDecimal> {
        return Pair(
            BigDecimal.valueOf(qtyField.value ?: 1.0),
            BigDecimal.valueOf(priceField.value ?: 0.0),
        )
    }

    override fun getDescriptionValue(): String = descField.value

    override fun descriptionField(): TextFieldBase<*, String> = descField

    override fun pdfPrintedFields(): List<TextFieldBase<*, String>> = super.pdfPrintedFields() + listOf(itemDescriptionField, refField)

    override fun getUnitValue(): InvoiceItemUnit = unitBox.value ?: InvoiceItemUnit.PIECE

    override fun getArticleComboBox(): ComboBox<ArticleDto> = articleSelect
}
