package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ArticleDto
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.TextFieldBase
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.AmountPreview
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.component.PdfTextValidation
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import java.math.BigDecimal

/** [BaseItemDialog]'s save-path callbacks — bundled into one constructor parameter purely to keep
 *  that constructor under detekt's `LongParameterList` threshold; the two are never meaningful
 *  independently of each other. [GoodsItemDialog]/[ServiceItemDialog] keep their own `onSaveNew`/
 *  `onUpdate` constructor parameters unbundled (each is well under the threshold on its own) and
 *  only wrap them into this at the `BaseItemDialog(...)` call. */
internal data class ItemSaveCallbacks(
    val onSaveNew: (InvoiceItemCommand) -> Long?,
    val onUpdate: (InvoiceItemCommand) -> Unit,
)

/**
 * Shared scaffold for Goods-/ServiceItemDialog. Extracts:
 * - Constructor wiring and dialog initialization
 * - Tax rate selection (reverse charge, seller country, fallback)
 * - Refresh and preview update
 * - B2B field handling
 * - Save validation and button footer
 *
 * Subclasses implement category-specific field building, loading, and form layout.
 */
internal abstract class BaseItemDialog(
    protected val recipientQueryPort: RecipientQueryPort,
    // The item-entry use case's own port: tax rates, B2B line fields and values, articles.
    protected val itemEntryQueryPort: ItemEntryQueryPort,
    protected val invoice: InvoiceDetailDto,
    protected val existingItem: ItemDto?,
    protected val defaultTaxRate: () -> TaxRateDto?,
    protected val saveCallbacks: ItemSaveCallbacks,
    // A plain UI class may not read the clock itself (TimeSourceArchitectureTest); the
    // constructing CDI bean (InvoiceItemDialogs) threads its own TimeSource.today() through.
    protected val today: java.time.LocalDate,
) {
    protected val onSaveNew get() = saveCallbacks.onSaveNew
    protected val onUpdate get() = saveCallbacks.onUpdate

    protected val dlg = AppDialog("", AppDialog.Size.M)
    protected fun tr(key: String, vararg params: Any): String = UiTranslations.tr(dlg, key, *params)

    protected val sellerCountry = invoice.sellerVatRegistrationCountry?.let { Country.fromAlpha2(it) }
    // Lazy because subclasses provide the category through constructor state. Calling the
    // abstract getter while the base constructor is still running observes uninitialised state.
    protected val availableTaxRates: List<TaxRateDto> by lazy {
        if (sellerCountry != null) taxRatesForSellerCountry(itemEntryQueryPort, sellerCountry)
        else selectableTaxRatesFor(getItemCategory(), itemEntryQueryPort.findAllTaxRates(), today)
    }
    protected val reverseChargeLocked = sellerCountry == null && invoice.reverseCharge
    protected val lockedRate = if (reverseChargeLocked) reverseChargeTaxRate(itemEntryQueryPort.findAllTaxRates()) else null
    protected val fallbackTaxRate: TaxRateDto? by lazy {
        if (sellerCountry == null) {
            defaultTaxRate() ?: availableTaxRates.find { it.rate.value == BigDecimal("19") } ?: availableTaxRates.firstOrNull()
        } else {
            itemEntryQueryPort.findReferenceTaxRate(sellerCountry, invoice.invoiceDate ?: today)
        }
    }

    /**
     * Returns the invoice item category (PRODUCT or SERVICE) this dialog handles.
     */
    protected abstract fun getItemCategory(): InvoiceItemCategory

    /**
     * Returns the translation key for the dialog title.
     */
    protected abstract fun getDialogTitleKey(): String

    /**
     * Builds and returns category-specific fields. Called before existing item load.
     * Subclass should populate and return a populated field holder.
     */
    protected abstract fun buildCategoryFields()

    /**
     * Loads existing item data into fields. Called only if [existingItem] is non-null.
     */
    protected abstract fun loadExistingItemIntoFields(item: ItemDto)

    /**
     * Builds the form layout from populated fields. Called after field initialization
     * and item load. Must include both the buyerItemNumberField and the taxGroup.
     *
     * [taxGroup] is passed in and has to be placed by every implementation: it is the tax-rate
     * selector the save path validates (`taxRatePanel.validate(taxGroup.value)`, and for a foreign
     * seller country a plain `taxGroup.value == null` rejection) and reads back into the saved
     * item. A layout that leaves it out compiles and runs, but the field never reaches the
     * component tree — the user then gets "a tax rate is required" for a control that is not on
     * screen, with no way to satisfy it.
     */
    protected abstract fun buildFormLayout(
        buyerItemNumberField: TextField,
        buyerItemDescriptionField: TextField,
        taxGroup: ComboBox<TaxRateDto>,
    ): FormLayout

    /**
     * Wires up refresh listeners to call the provided refresh functions on field changes.
     */
    protected abstract fun wireRefreshListeners(
        taxGroup: ComboBox<TaxRateDto>,
        taxRulePanel: TaxRuleProposalPanel?,
        refresh: () -> Unit,
        refreshAdvice: () -> Unit,
    )

    /**
     * Applies article selection logic for this item type (category-specific field updates).
     * Called when article ComboBox value changes.
     */
    protected abstract fun applyArticleSelection(
        article: ArticleDto,
        taxGroup: ComboBox<TaxRateDto>,
        taxRulePanel: TaxRuleProposalPanel?,
        refresh: () -> Unit,
    )

    /**
     * Collects current field values into an InvoiceItemCommand, without tax/exemption fields.
     * Base class adds those.
     */
    protected abstract fun collectItemCommand(): InvoiceItemCommand

    /**
     * Extracts quantity and unit price for preview calculations.
     */
    protected abstract fun collectQuantityAndPrice(): Pair<BigDecimal, BigDecimal>

    /**
     * Gets the description field value for advisors.
     */
    protected abstract fun getDescriptionValue(): String

    /** The input that holds [getDescriptionValue], so a missing description is flagged on it. */
    protected abstract fun descriptionField(): TextFieldBase<*, String>

    /** Every free-text input of this dialog whose value ends up on the invoice PDF. */
    protected open fun pdfPrintedFields(): List<TextFieldBase<*, String>> = listOf(descriptionField(), buyerItemDescriptionField)

    /**
     * Gets the unit value for advisors.
     */
    protected abstract fun getUnitValue(): InvoiceItemUnit

    /**
     * Gets the article ComboBox.
     */
    protected abstract fun getArticleComboBox(): ComboBox<ArticleDto>

    private lateinit var buyerItemDescriptionField: TextField

    private data class HeaderFields(val b2bItemSection: B2bProfileFieldsSection?, val buyerItemNumberField: TextField)

    private fun setupHeaderFields(): HeaderFields {
        dlg.headerTitle = tr(getDialogTitleKey())

        val b2bFields = findB2bItemFieldsFor(itemEntryQueryPort, invoice.id)
        val b2bItemSection = itemEntryQueryPort.buildB2bItemSection({ key -> tr(key) }, b2bFields, existingItem?.id)
        val buyerItemNumberField = buildBuyerItemNumberField(itemEntryQueryPort, { key -> tr(key) }, b2bFields, existingItem?.id)
        buyerItemDescriptionField = buildBuyerItemDescriptionField { key -> tr(key) }
        if (existingItem != null) {
            buyerItemDescriptionField.value = existingItem.buyerItemDescription.orEmpty()
        }

        buildCategoryFields()
        return HeaderFields(b2bItemSection, buyerItemNumberField)
    }

    /** The item dialog's own form widgets that [handleArticleSelectionChanged]/[saveCurrentItem]/
     *  [buildFooter] each need a different subset of — bundled into one type so all three stay
     *  under detekt's `LongParameterList` threshold instead of three different, narrower
     *  bags. */
    private data class ItemFormWidgets(
        val taxGroup: ComboBox<TaxRateDto>,
        val taxRulePanel: TaxRuleProposalPanel?,
        val articleCombo: ComboBox<ArticleDto>,
        val b2bItemSection: B2bProfileFieldsSection?,
        val buyerItemNumberField: TextField,
    )

    private fun handleArticleSelectionChanged(
        article: ArticleDto,
        widgets: ItemFormWidgets,
        refresh: () -> Unit,
    ) {
        val taxGroup = widgets.taxGroup
        val taxRulePanel = widgets.taxRulePanel
        val b2bItemSection = widgets.b2bItemSection
        val buyerItemNumberField = widgets.buyerItemNumberField
        // Combo rows carry no tax variants, so the selection applies the article loaded by id;
        // the combo's own row is only the fallback for an article deleted meanwhile.
        val loaded = article.id?.let { itemEntryQueryPort.findArticleById(it) }
        val recipientRef = resolveArticleRecipientRef(itemEntryQueryPort, invoice, article.id)
        applyArticleSelection(loaded ?: article, taxGroup, taxRulePanel, refresh)
        // BT-155 comes from the stored article only, never from a possibly stale combo row.
        b2bItemSection?.prefillIfBlank(B2bTerm.BT_155.officialCode, loaded?.articleNumber)
        prefillIfBlank(buyerItemNumberField, recipientRef?.buyerArticleNumber)
        prefillIfBlank(buyerItemDescriptionField, recipientRef?.buyerArticleName)
        refresh()
    }

    /** [saveCurrentItem]'s field validation, extracted to keep that method's own complexity/
     *  return count down. */
    private fun validItemInput(taxGroup: ComboBox<TaxRateDto>, taxRulePanel: TaxRuleProposalPanel?): Boolean {
        val desc = getDescriptionValue().trim()
        if (desc.isBlank()) {
            UiNotifications.errorOn(descriptionField(), tr("invoiceDetail.itemDialog.error.descriptionRequired"))
            return false
        }
        // The PDF engine rejects characters its fonts lack; refusing them here keeps PDF and XML
        // identical and the issued invoice renderable.
        pdfPrintedFields().firstNotNullOfOrNull { field ->
            PdfTextValidation.message({ key -> tr(key) }, field.value)?.let { field to it }
        }?.let { (field, message) ->
            UiNotifications.errorOn(field, message)
            return false
        }
        return validTaxSelection(taxGroup, taxRulePanel)
    }

    private fun validTaxSelection(taxGroup: ComboBox<TaxRateDto>, taxRulePanel: TaxRuleProposalPanel?): Boolean {
        if (sellerCountry == null) return taxRulePanel!!.validate(taxGroup.value)
        if (taxGroup.value == null) {
            UiNotifications.errorOn(taxGroup, tr("invoiceDetail.itemDialog.error.taxRateRequired"))
            return false
        }
        return true
    }

    // Shared save logic
    private fun saveCurrentItem(widgets: ItemFormWidgets): Boolean {
        val taxGroup = widgets.taxGroup
        val taxRulePanel = widgets.taxRulePanel
        val articleCombo = widgets.articleCombo
        val b2bItemSection = widgets.b2bItemSection
        val buyerItemNumberField = widgets.buyerItemNumberField
        if (!validItemInput(taxGroup, taxRulePanel)) return false

        val command = collectItemCommand().copy(
            buyerItemDescription = buyerItemDescriptionField.value.trim().ifBlank { null },
            taxRateId = taxGroup.value?.id,
            exemptionReasonTextOverride = if (existingItem == null && requiresExplicitExemptionDetails(taxGroup.value))
                taxRulePanel!!.exemptionReasonField.value.trim() else null,
            taxLegalBasisOverride = if (existingItem == null && requiresExplicitExemptionDetails(taxGroup.value))
                taxRulePanel!!.exemptionLegalBasisField.value.trim() else null,
        )

        val article = articleCombo.value

        // A 0-€ goods/service position is almost always a forgotten price, not intent;
        // warn but never block (PO default 2026-09-02: free-of-charge lines stay possible).
        if (command.unitPriceNet.isZero() &&
            (command.category == InvoiceItemCategory.PRODUCT || command.category == InvoiceItemCategory.SERVICE)
        ) {
            UiNotifications.warning(tr("invoiceDetail.itemDialog.warning.zeroPrice"))
        }

        if (existingItem != null) {
            onUpdate(command)
            copyB2bTemplateFor(itemEntryQueryPort, invoice.id, article, existingItem.id)
            persistB2bItemValues(itemEntryQueryPort, existingItem.id, b2bItemSection, onlyNonBlank = false)
            persistBuyerItemNumber(itemEntryQueryPort, existingItem.id, buyerItemNumberField)
        } else {
            val savedItemId = onSaveNew(command)
            copyB2bTemplateFor(itemEntryQueryPort, invoice.id, article, savedItemId)
            persistB2bItemValues(itemEntryQueryPort, savedItemId, b2bItemSection, onlyNonBlank = true)
            persistBuyerItemNumber(itemEntryQueryPort, savedItemId, buyerItemNumberField)
        }
        return true
    }

    private fun buildFooter(widgets: ItemFormWidgets) {
        dlg.footer(
            AppButtons.cancel { dlg.close() },
            AppButtons.primary(tr("app.save")) {
                if (saveCurrentItem(widgets)) dlg.close()
            },
        )
    }

    fun open() {
        val (b2bItemSection, buyerItemNumberField) = setupHeaderFields()

        val preview = AmountPreview(invoice.currencyEnum())
        val advicePanel = LineAdvicePanel { key -> tr(key) }

        // Tax rule panel (only on the German-seller tax rule resolution path)
        val taxRulePanel = if (sellerCountry == null) TaxRuleProposalPanel(recipientQueryPort, itemEntryQueryPort.findAllTaxRates(), invoice, getItemCategory(), today) else null
        val taxGroup = createTaxRateComboBox(taxRulePanel)

        fun refresh() {
            val rate = taxGroup.value?.rate?.value ?: (if (sellerCountry == null) BigDecimal("19") else BigDecimal.ZERO)
            val (qty, price) = collectQuantityAndPrice()
            val (net, vat, gross) = InvoiceDetailView.calculateAmounts(qty, price, rate)
            preview.update(net, vat, gross, rate)
        }

        fun refreshAdvice() {
            val (qty, _) = collectQuantityAndPrice()
            val desc = getDescriptionValue()
            val unit = getUnitValue()
            advicePanel.update(LineAdvisor.advise(desc, unit, qty))
        }

        // Load existing item before setting up listeners
        if (existingItem != null) {
            loadExistingItemIntoFields(existingItem)
            loadExistingTaxRate(taxGroup, taxRulePanel)
            refresh()
            refreshAdvice()
        }

        // Wire common refresh listeners
        wireRefreshListeners(taxGroup, taxRulePanel, { refresh() }, { refreshAdvice() })

        // Article selection listener
        val articleCombo = getArticleComboBox()
        val widgets = ItemFormWidgets(taxGroup, taxRulePanel, articleCombo, b2bItemSection, buyerItemNumberField)
        articleCombo.addValueChangeListener { ev ->
            val article = ev.value
            if (article != null) {
                handleArticleSelectionChanged(article, widgets) { refresh() }
            }
        }

        // Build layout and add to dialog
        val formLayout = buildFormLayout(buyerItemNumberField, buyerItemDescriptionField, taxGroup)
        val additionalPanels = listOfNotNull(taxRulePanel, b2bItemSection)
        dlg.add(formLayout, *additionalPanels.toTypedArray(), preview, advicePanel.component)

        buildFooter(widgets)
        dlg.open()
    }

    protected open fun createTaxRateComboBox(taxRulePanel: TaxRuleProposalPanel?): ComboBox<TaxRateDto> =
        ComboBox<TaxRateDto>(tr("invoiceDetail.itemDialog.taxRate")).apply {
            setItems(if (reverseChargeLocked) listOfNotNull(lockedRate) else availableTaxRates)
            setItemLabelGenerator(::taxRuleLabel)
            value = when {
                reverseChargeLocked -> lockedRate
                sellerCountry == null -> effectiveTaxRuleFor(
                    getItemCategory(),
                    taxRulePanel?.resolution,
                    TaxRateCandidates(proposed = taxRulePanel?.proposedRate, articleDefault = null, fallback = fallbackTaxRate),
                )
                else -> null
            }
            isEnabled = !reverseChargeLocked
            if (reverseChargeLocked) helperText = tr("invoiceDetail.itemDialog.taxRate.lockedByReverseCharge")
            setWidthFull()
        }

    protected open fun loadExistingTaxRate(taxGroup: ComboBox<TaxRateDto>, taxRulePanel: TaxRuleProposalPanel?) {
        val existingTaxRate = existingItem?.taxRateRefId?.let { id -> itemEntryQueryPort.findAllTaxRates().find { it.id == id } }
        taxGroup.value = if (reverseChargeLocked) lockedRate
            else existingTaxRate ?: (if (sellerCountry == null) defaultTaxRate() else null) ?: availableTaxRates.firstOrNull()
        taxRulePanel?.onSelectedRateChanged(taxGroup.value)
    }
}
