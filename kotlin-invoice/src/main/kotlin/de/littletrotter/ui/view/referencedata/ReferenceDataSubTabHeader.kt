package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout

/**
 * The uniform header frame shown above every reference-data sub-tab: a title, a
 * one-sentence factual description of what the tab manages, and a right-aligned slot.
 * [de.littletrotter.ui.view.referencedata.ReferenceDataHeaderActions] fills the slot —
 * a search field and an entry counter (a per-tab "Neu" button stays in the tab body next to its
 * own grid, not here). It stays an empty [Div] for tabs that opt out (e.g.
 * [ValidationSettingsTab]) so the frame's layout doesn't shift either way.
 */
class ReferenceDataSubTabHeader(title: String, description: String) : VerticalLayout() {

    /** Right-aligned slot — populated by [de.littletrotter.ui.view.referencedata
     *  .ReferenceDataHeaderActions.installHeaderActions] (search field, entry counter), or left
     *  empty for tabs that opt out. */
    val actionsSlot: Div = Div()

    init {
        isPadding = false
        isSpacing = false
        addClassName("lt-gap-025")

        val titleRow = HorizontalLayout(H3(title), actionsSlot).apply {
            setWidthFull()
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
        }
        add(titleRow, Paragraph(description))
    }
}
