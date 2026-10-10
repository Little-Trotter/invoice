package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.HasHelper
import com.vaadin.flow.component.customfield.CustomField
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.shared.country.Country

/** The theme variant `styles.css` paints as a two-segment switch — same idiom as
 *  `InvoiceListExportDialog`'s `SEGMENTED_THEME_NAME`, not shared across files because each one's
 *  segmented control is a private implementation detail of its own component. */
private const val SEGMENTED_THEME_NAME = "lt-segmented"

/** Playwright/Karibu hook for the field's own container — same `data-testid` idiom
 *  `InvoiceCreateDialog.buildReverseChargeDecision` already uses for its (unrelated, out-of-scope)
 *  control. */
internal const val REVERSE_CHARGE_FIELD_ID = "invoice-detail-reverse-charge"

/** Playwright/Karibu hook for the segmented control itself, nested inside [REVERSE_CHARGE_FIELD_ID]. */
internal const val REVERSE_CHARGE_SEGMENTS_ID = "invoice-detail-reverse-charge-segments"

/**
 * B4 — binary reverse-charge decision, default No: replaces B1's tri-state [CustomField]
 * (`ReverseChargeToggle`, deleted). There is no "not yet decided" state any more; `value` is
 * never `null`, and a hidden field always carries `false` (§ 13b domestic reverse charge is out
 * of scope, so an equal-country pair is never a candidate either).
 *
 * One row, no wrapping: the label on the left, the two segments directly after it. The field's
 * own [label][CustomField.setLabel] is deliberately left unset (that would render a second,
 * stacked line above the control) — [labelText] instead lives in an inline [Span] next to the
 * segments, with the accessible name carried by the segmented control's own `aria-label`.
 */
class ReverseChargeField(
    labelText: String,
    yesLabel: String,
    noLabel: String,
    helper: String,
) : CustomField<Boolean>(false, true), HasHelper {

    private val segments = RadioButtonGroup<Boolean>().apply {
        setItems(false, true)
        setItemLabelGenerator { if (it) yesLabel else noLabel }
        value = false
        addThemeName(SEGMENTED_THEME_NAME)
        element.setAttribute("aria-label", labelText)
        setId(REVERSE_CHARGE_SEGMENTS_ID)
        addValueChangeListener { if (it.isFromClient) updateValue() }
    }

    init {
        addClassName("rc-field")
        setId(REVERSE_CHARGE_FIELD_ID)
        helperText = helper
        add(
            HorizontalLayout(Span(labelText).apply { addClassName("rc-field-label") }, segments).apply {
                addClassName("rc-field-row")
                isSpacing = true
                alignItems = FlexComponent.Alignment.CENTER
            },
        )
    }

    override fun generateModelValue(): Boolean = segments.value ?: false

    override fun setPresentationValue(newPresentationValue: Boolean) {
        segments.value = newPresentationValue
    }

    /**
     * The B4 visibility rule: shown only for a writable invoice whose seller and recipient VAT
     * countries are both known and differ (cross-border EU B2B) — never for an equal pair, § 13b
     * domestic reverse charge is not part of the MVP. A field that becomes hidden resets to `false`
     * so a hidden field never carries a stale Yes into the save command.
     */
    fun applyVisibility(sellerCountry: Country, recipientCountry: Country?, readOnly: Boolean) {
        isVisible = !readOnly && recipientCountry != null && recipientCountry != sellerCountry
        if (!isVisible) value = false
    }
}
