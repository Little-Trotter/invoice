package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.PdfTextValidation
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.view.invoice.selectableTaxRates
import de.littletrotter.ui.view.invoice.taxRuleLabel
import de.littletrotter.ui.view.referencedata.ArticleFormData
import java.math.BigDecimal
import java.time.LocalDate

private const val SHORT_HASH_MAX_LENGTH = 10

/**
 * [ArticleView]'s dialog field construction/binding, extracted out to keep the
 * view itself under detekt's `LargeClass` size threshold; the widgets are still owned/read/written
 * by [ArticleView] directly (`taxRateCombo.value`, `articleNumberField.value`, etc. in
 * `readFormData`/`applyFormData`), same shape as [SenderProfileDialogSections]/
 * [SenderProfileFormBinder] already split out of `SenderProfileView`.
 */
class ArticleDialogFields(
    tr: (String) -> String,
    taxRateQueries: TaxRateQueryPort,
    unitsByDomain: List<InvoiceItemUnit>,
    sectionLabel: (InvoiceItemCategory) -> String,
    categories: List<InvoiceItemCategory>,
    today: LocalDate,
) {
    val nameField = TextField()
    val descriptionArea = TextArea()
    val categoryBox = ComboBox<InvoiceItemCategory>()
    val unitBox = ComboBox<InvoiceItemUnit>()
    val quantityField = BigDecimalField()
    val priceField = BigDecimalField()
    val taxRateCombo = ComboBox<TaxRateDto>()

    /** Article number proposal from ARTICLE circle; editable like customer number. Empty field uses auto-assignment on save. */
    val articleNumberField = TextField()

    /** Short hash technical identifier; editable. Empty field uses auto-assignment on save. */
    val shortHashField = TextField()

    init {
        articleNumberField.label = tr("serviceCatalog.positionNumber")
        articleNumberField.helperText = tr("serviceCatalog.articleNumber.helper")
        articleNumberField.setWidthFull()

        shortHashField.label = tr("serviceCatalog.shortHash")
        shortHashField.helperText = tr("serviceCatalog.shortHash.helper")
        shortHashField.maxLength = SHORT_HASH_MAX_LENGTH
        shortHashField.setWidthFull()

        nameField.label = tr("serviceCatalog.name")
        nameField.isRequired = true
        nameField.setWidthFull()

        descriptionArea.label = tr("serviceCatalog.description")
        descriptionArea.setWidthFull()
        descriptionArea.minRows = 2

        categoryBox.label = tr("serviceCatalog.category")
        categoryBox.setItems(categories)
        categoryBox.setItemLabelGenerator { sectionLabel(it) }
        categoryBox.isRequired = true
        categoryBox.setWidthFull()

        unitBox.label = tr("serviceCatalog.unit")
        unitBox.setItems(unitsByDomain)
        unitBox.setItemLabelGenerator { unit -> "${tr(unit.labelKey)} (${unit.uneceCode})" }
        unitBox.isRequired = true
        unitBox.setWidthFull()
        // Rich dropdown renderer: label + symbol/UNECE code + domain chip, so the user can
        // scan the 18 units visually grouped by physical dimension (Zeit, Länge, …).
        unitBox.setRenderer(ComponentRenderer { unit: InvoiceItemUnit ->
            HorizontalLayout().apply {
                isSpacing = true
                isPadding = false
                addClassName("lt-unit-row")
                defaultVerticalComponentAlignment = Alignment.CENTER
                add(Span(tr(unit.labelKey)).apply { addClassName("lt-medium") })
                add(Span("${unit.symbol} · ${unit.uneceCode}").apply { addClassName("lt-unit-row__symbol") })
                add(Span(tr(unit.domain.labelKey)).apply { applyChipTone(ChipTone.NEUTRAL); addClassName("lt-unit-row__domain") })
            }
        })
        quantityField.label = tr("serviceCatalog.quantity")
        quantityField.setWidthFull()

        priceField.label = tr("serviceCatalog.unitPriceNet")
        priceField.suffixComponent = Span("€")
        priceField.setWidthFull()

        taxRateCombo.label = tr("serviceCatalog.taxRate")
        taxRateCombo.setWidthFull()
        taxRateCombo.setItemLabelGenerator(::taxRuleLabel)
        taxRateCombo.helperText = tr("serviceCatalog.taxRate.helper")
        // Only standard tax rates (19%, 7%): articles carry rate, not tax case. Reverse charge,
        // exemption, non-taxable depend on recipient and resolve per invoice; allowing those here
        // creates duplicates that would incorrectly apply to domestic customers.
        taxRateCombo.setItems(
            selectableTaxRates(taxRateQueries.findAllTaxRates(), today)
                .filter { it.categoryEnum == de.littletrotter.shared.tax.VatCategory.STANDARD && it.country == de.littletrotter.shared.country.Country.DE },
        )
    }

    fun bind(binder: Binder<ArticleFormData>, tr: (String) -> String) {
        binder.forField(nameField)
            .asRequired(tr("serviceCatalog.name.required"))
            .withValidator(PdfTextValidation.validator(tr))
            .bind(ArticleFormData::name, ArticleFormData::name::set)
        binder.forField(descriptionArea)
            .withValidator(PdfTextValidation.validator(tr))
            .bind({ it.description ?: "" }, { fd, v -> fd.description = v.ifBlank { null } })
        binder.forField(categoryBox)
            .asRequired()
            .bind(ArticleFormData::category, ArticleFormData::category::set)
        binder.forField(unitBox)
            .asRequired()
            .bind(ArticleFormData::defaultUnit, ArticleFormData::defaultUnit::set)
        binder.forField(quantityField)
            .withValidator({ v -> v == null || v >= BigDecimal.ZERO }, tr("serviceCatalog.quantity.invalid"))
            .bind(ArticleFormData::defaultQuantity, ArticleFormData::defaultQuantity::set)
        binder.forField(priceField)
            .withValidator({ v -> v == null || v >= BigDecimal.ZERO }, tr("serviceCatalog.unitPriceNet.invalid"))
            .withConverter(
                { amount -> amount?.let { Money.of(it) } ?: Money.ZERO },
                { money -> money?.amount ?: BigDecimal.ZERO }
            )
            .bind(ArticleFormData::defaultUnitPriceNet, ArticleFormData::defaultUnitPriceNet::set)
        // Tax rate selection is resolved during save (ArticleFormData.defaultTaxRateId)
    }
}
