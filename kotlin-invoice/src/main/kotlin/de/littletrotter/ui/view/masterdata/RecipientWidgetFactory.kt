package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.masterdata.port.inbound.RecipientB2bFieldsQueryPort
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.ui.component.B2bProfileFieldsSection

/**
 * [RecipientView]'s more elaborately configured dialog widgets, extracted so the view's
 * own field section stays a plain list of `private val`s. [RecipientView] still declares every
 * field directly (so the existing field-name reflection in the Karibu tests keeps working); this
 * file only owns how each widget's non-trivial construction is put together.
 */
internal fun buildRecipientCustomerTypeField(): ComboBox<CustomerType> = ComboBox<CustomerType>().apply {
    setItems(CustomerType.BUSINESS, CustomerType.CONSUMER)
    value = CustomerType.BUSINESS
}

/** PO order 04.09.2026 — "deutsche oder internationale Rechnung" for this customer; the
 *  preselection-from-country logic lives on [RecipientFormPopulator]. */
internal fun buildRecipientInvoiceFormatField(): ComboBox<InvoiceFormat> = ComboBox<InvoiceFormat>().apply {
    setItems(InvoiceFormat.GERMAN, InvoiceFormat.INTERNATIONAL)
    isAllowCustomValue = false
    value = InvoiceFormat.GERMAN
}

/** The recipient's single document language flag ("Dokumentsprache": German or international);
 *  preselected from the billing country by [RecipientFormPopulator]. */
internal fun buildRecipientDocumentLanguageField(): ComboBox<DocumentLanguage> = ComboBox<DocumentLanguage>().apply {
    setItems(DocumentLanguage.DE, DocumentLanguage.EN)
    isAllowCustomValue = false
    value = DocumentLanguage.DE
}

/** Horizontal segmented control for the recipient's standing Reverse Charge default (the
 *  same control family as `InvoiceCreateDialog.buildReverseChargeDecision`'s
 *  `RadioButtonGroup<Boolean>` one tab away, for the same underlying decision); one icon per
 *  option so the three read apart at a glance, selection state alone carrying the distinction. */
internal fun buildRecipientReverseChargePreferenceField(): RadioButtonGroup<ReverseChargePreference> =
    RadioButtonGroup<ReverseChargePreference>().apply {
        setItems(*ReverseChargePreference.entries.toTypedArray())
        isRequiredIndicatorVisible = false // always has a value, never blank
        setRenderer(
            ComponentRenderer { option ->
                val icon = when (option) {
                    ReverseChargePreference.NEVER -> VaadinIcon.CLOSE_SMALL
                    ReverseChargePreference.ALWAYS -> VaadinIcon.CHECK
                    ReverseChargePreference.ASK -> VaadinIcon.QUESTION_CIRCLE_O
                }.create().apply { addClassName("fs-08") }
                HorizontalLayout(icon, Span(itemLabelGenerator.apply(option))).apply {
                    isSpacing = true
                    alignItems = FlexComponent.Alignment.CENTER
                }
            },
        )
        element.setAttribute("data-testid", "recipient-reverse-charge-preference")
    }

/** Red badge for [de.littletrotter.shared.tax.VatIdVerificationStatus.FAILED] — the manual
 *  attestation checkbox alone cannot express it (renders unchecked the same as UNVERIFIED), so it
 *  needs a visible marker of its own next to the checkbox. */
internal fun buildVatIdFailedBadge(): Span = Span().apply {
    addClassNames("fs-08", "font-weight-semibold", "lt-text-error-base")
    isVisible = false
}

internal fun buildVatIdValidatedRow(checkbox: Checkbox, badge: Span): HorizontalLayout =
    HorizontalLayout(checkbox, badge).apply {
        isSpacing = true
        alignItems = FlexComponent.Alignment.CENTER
    }

/** The standing B2B profile default for invoices billed to this customer; `null` = no
 *  profile (Community behavior). Populated eagerly, like the sender form's former combo: the
 *  option list is small, reference-data-sized, and rarely changes mid-session. */
internal fun buildRecipientB2bProfileCombo(b2bFields: RecipientB2bFieldsQueryPort): ComboBox<B2bProfileDto> =
    ComboBox<B2bProfileDto>().apply {
        setItems(b2bFields.findAllActiveB2bProfiles())
        setItemLabelGenerator { it.name }
        isClearButtonVisible = true
        setWidthFull()
        addClassName("recipient-b2b-field")
    }

/** The customer's "B2B-Felder" section container, hidden ([Div.isVisible]) until
 *  [fieldsSection] actually carries a `RECIPIENT`-placed field for the assigned profile. */
internal fun buildRecipientB2bFieldsContainer(title: Span, fieldsSection: B2bProfileFieldsSection): Div = Div().apply {
    addClassName("entry-section")
    add(title.apply { addClassName("bau-eyebrow") })
    add(fieldsSection)
    isVisible = false
}
