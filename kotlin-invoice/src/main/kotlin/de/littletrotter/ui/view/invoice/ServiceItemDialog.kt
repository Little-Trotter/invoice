package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ArticleDto
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
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
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.applyArticleSelectPresentation
import de.littletrotter.ui.component.lazyItems
import java.math.BigDecimal

/** Visible row count of the activity description [TextArea]. */
private const val ACTIVITY_DESCRIPTION_MIN_ROWS = 3

/**
 * The "Dienstleistung" item dialog. Service-specific field building, form layout, and article selection.
 */
internal class ServiceItemDialog(
    recipientQueryPort: RecipientQueryPort,
    itemEntryQueryPort: ItemEntryQueryPort,
    invoice: InvoiceDetailDto,
    existingItem: ItemDto? = null,
    defaultTaxRate: () -> TaxRateDto?,
    onSaveNew: (InvoiceItemCommand) -> Long?,
    onUpdate: (InvoiceItemCommand) -> Unit,
    today: java.time.LocalDate,
) : BaseItemDialog(
    recipientQueryPort, itemEntryQueryPort, invoice, existingItem, defaultTaxRate, ItemSaveCallbacks(onSaveNew, onUpdate), today,
) {

    private val articleQueries get() = itemEntryQueryPort

    private lateinit var descArea: TextArea
    private lateinit var itemDescriptionField: TextArea
    private lateinit var unitHour: String
    private lateinit var unitDay: String
    private lateinit var unitFlat: String
    private lateinit var unitToInvoiceUnit: Map<String, InvoiceItemUnit>
    private lateinit var unitToLabel: Map<String, String>
    private lateinit var unitGroup: RadioButtonGroup<String>
    private lateinit var qtyField: NumberField
    private lateinit var priceField: NumberField
    private lateinit var articleSelect: ComboBox<ArticleDto>
    private lateinit var articleNumberField: TextField

    override fun getItemCategory(): InvoiceItemCategory = InvoiceItemCategory.SERVICE

    override fun getDialogTitleKey(): String = "invoiceDetail.service.dialog.title"

    override fun buildCategoryFields() {
        unitHour = InvoiceUnitLabels.label(dlg, InvoiceItemUnit.HOUR)
        unitDay = InvoiceUnitLabels.label(dlg, InvoiceItemUnit.DAY)
        unitFlat = InvoiceUnitLabels.label(dlg, InvoiceItemUnit.FLAT_RATE)
        unitToInvoiceUnit = mapOf(unitHour to InvoiceItemUnit.HOUR, unitDay to InvoiceItemUnit.DAY, unitFlat to InvoiceItemUnit.FLAT_RATE)
        unitToLabel = mapOf(
            unitHour to tr("invoiceDetail.service.rateLabel.hour"),
            unitDay to tr("invoiceDetail.service.rateLabel.day"),
            unitFlat to tr("invoiceDetail.service.rateLabel.flat"),
        )

        descArea = TextArea(tr("invoiceDetail.service.activity")).apply { setWidthFull(); isRequired = true; minRows = ACTIVITY_DESCRIPTION_MIN_ROWS; placeholder = "z. B. Softwareentwicklung Feature X, Zeitraum 03.–07.03.2025" }
        itemDescriptionField = TextArea(tr("invoiceDetail.itemDialog.itemDescription")).apply {
            setWidthFull(); minRows = 2
            helperText = tr("invoiceDetail.itemDialog.itemDescription.helper")
        }
        articleSelect = ComboBox<ArticleDto>(tr("invoiceDetail.service.selectArticle")).apply {
            setWidthFull(); placeholder = tr("invoiceDetail.service.selectArticle.placeholder")
            addClassName("article-select-combo")
            // Was findAllArticles() + client-side category filter, capped at
            // page-size.reference-data.lookup (1000). The category now constrains the query itself.
            lazyItems(
                count = { filter -> articleQueries.countArticlesForSelect(InvoiceItemCategory.SERVICE, filter) },
                fetch = { filter, offset, limit -> articleQueries.findArticlesForSelect(InvoiceItemCategory.SERVICE, filter, offset, limit) },
            )
            // Number/name/description search: shared two-line rows + "number · name" label.
            applyArticleSelectPresentation { key -> tr(key) }
        }
        articleNumberField = buildArticleNumberField { key -> tr(key) }
        unitGroup = RadioButtonGroup<String>(tr("invoiceDetail.itemDialog.unit")).apply { setItems(unitHour, unitDay, unitFlat); value = unitHour }
        qtyField = NumberField(tr("invoiceDetail.itemDialog.quantity")).apply { value = 1.0; min = 0.0; setWidthFull() }
        priceField = NumberField(tr("invoiceDetail.service.rateLabel.hour")).apply { suffixComponent = Span("€"); setWidthFull() }
    }

    /** Price label and quantity visibility follow the unit; also needed for a loaded value, which is set before the listener is wired. */
    private fun syncUnitDependentFields(unit: String?) {
        priceField.label = unitToLabel[unit] ?: ""
        qtyField.isVisible = unit != unitFlat
    }

    override fun loadExistingItemIntoFields(item: ItemDto) {
        descArea.value = item.description
        itemDescriptionField.value = item.itemDescription.orEmpty()
        unitGroup.value = when (item.unitEnum()) {
            InvoiceItemUnit.HOUR -> unitHour
            InvoiceItemUnit.DAY -> unitDay
            InvoiceItemUnit.FLAT_RATE -> unitFlat
            else -> unitHour
        }
        syncUnitDependentFields(unitGroup.value)
        qtyField.value = item.quantity.toDouble().takeIf { value -> value > 0 } ?: 1.0
        priceField.value = (item.unitPriceNet ?: Money.ZERO).amount.toDouble()
        loadArticleIntoFields(articleQueries, item.articleRefId, articleSelect, articleNumberField)
    }

    override fun buildFormLayout(buyerItemNumberField: TextField, buyerItemDescriptionField: TextField, taxGroup: ComboBox<TaxRateDto>): FormLayout {
        // taxGroup sits directly after priceField: it is what turns the net price into the amount
        // the preview below shows, so it belongs next to the price, not at the end of the form.
        return FormLayout(
            articleSelect, articleNumberField, descArea, itemDescriptionField, unitGroup, qtyField, priceField,
            taxGroup, buyerItemNumberField, buyerItemDescriptionField,
        ).apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1))
            applyColspan(this, 1, articleSelect, descArea, itemDescriptionField, taxGroup, buyerItemNumberField, buyerItemDescriptionField)
        }
    }

    override fun wireRefreshListeners(
        taxGroup: ComboBox<TaxRateDto>,
        taxRulePanel: TaxRuleProposalPanel?,
        refresh: () -> Unit,
        refreshAdvice: () -> Unit,
    ) {
        unitGroup.addValueChangeListener { e ->
            syncUnitDependentFields(e.value)
            refresh.invoke()
        }
        qtyField.addValueChangeListener { refresh.invoke() }
        priceField.addValueChangeListener { refresh.invoke() }
        taxGroup.addValueChangeListener { taxRulePanel?.onSelectedRateChanged(it.value); refresh.invoke() }

        descArea.addValueChangeListener { refreshAdvice.invoke() }
        unitGroup.addValueChangeListener { refreshAdvice.invoke() }
        qtyField.addValueChangeListener { refreshAdvice.invoke() }
    }

    override fun applyArticleSelection(
        article: ArticleDto,
        taxGroup: ComboBox<TaxRateDto>,
        taxRulePanel: TaxRuleProposalPanel?,
        refresh: () -> Unit,
    ) {
        val recipientRef = resolveArticleRecipientRef(articleQueries, invoice, article.id)
        articleNumberField.value = article.articleNumber.orEmpty()
        descArea.value = if (existingItem == null) {
            newPositionDescriptionFor(recipientRef, article.description ?: article.name)
        } else {
            article.description ?: article.name
        }
        qtyField.value = article.defaultQuantity.toDouble().takeIf { it > 0 } ?: 1.0
        priceField.value = article.defaultUnitPriceNet.amount.toDouble()
        taxGroup.value = resolveArticleTaxRate(
            article, InvoiceItemCategory.SERVICE, taxRulePanel,
            ArticleTaxContext(reverseChargeLocked, lockedRate, sellerCountry, fallbackTaxRate, articleQueries.findAllTaxRates()),
        )
        refresh.invoke()
    }

    override fun collectItemCommand(): InvoiceItemCommand {
        val desc = descArea.value.trim()
        val isFlat = unitGroup.value == unitFlat
        val qty = if (isFlat) BigDecimal.ONE else BigDecimal.valueOf(qtyField.value ?: 1.0)
        val price = BigDecimal.valueOf(priceField.value ?: 0.0)

        return InvoiceItemCommand(
            itemId = existingItem?.id,
            category = InvoiceItemCategory.SERVICE,
            description = desc,
            itemDescription = itemDescriptionField.value.trim().ifBlank { null },
            quantity = qty,
            unit = unitToInvoiceUnit[unitGroup.value] ?: InvoiceItemUnit.HOUR,
            unitPriceNet = Money(price),
            articleId = articleSelect.value?.id,
            taxRateId = null, // Will be set by base class
        )
    }

    override fun collectQuantityAndPrice(): Pair<BigDecimal, BigDecimal> {
        val isFlat = unitGroup.value == unitFlat
        return Pair(
            if (isFlat) BigDecimal.ONE else BigDecimal.valueOf(qtyField.value ?: 1.0),
            BigDecimal.valueOf(priceField.value ?: 0.0),
        )
    }

    override fun getDescriptionValue(): String = descArea.value

    override fun descriptionField(): TextFieldBase<*, String> = descArea

    override fun pdfPrintedFields(): List<TextFieldBase<*, String>> = super.pdfPrintedFields() + listOf(itemDescriptionField)

    override fun getUnitValue(): InvoiceItemUnit {
        return unitToInvoiceUnit[unitGroup.value] ?: InvoiceItemUnit.HOUR
    }

    override fun getArticleComboBox(): ComboBox<ArticleDto> = articleSelect
}
