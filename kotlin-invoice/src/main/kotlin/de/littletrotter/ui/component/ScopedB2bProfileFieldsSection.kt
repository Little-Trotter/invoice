package de.littletrotter.ui.component

import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.domain.reference.isB2bSellerFieldGroupVisible
import java.text.MessageFormat

private const val CSS_FS_08 = "fs-08"
private const val CSS_TEXT_MUTED = "text-muted"

/**
 * The seller (Anbieter) form's B2B profile fields, split into an
 * always-visible "PROFILFELDER" group (unscoped profiles) and one "PROFILFELDER · <Land>" group
 * per country at least one active scoped profile names. Every group is built exactly once (from
 * [de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort.findB2bSellerFieldsUnion], which is independent of any particular seller —
 * see that method's KDoc) and [applySellerCountries] only ever toggles a country group's
 * *visibility* afterwards — the same "hiding never deletes" pattern [PartyExtensionsSection]
 * uses: a value typed into a currently-hidden group must survive a VAT registration being removed
 * and re-added before the dialog is saved, so the widget tree is never rebuilt on such a change.
 *
 * `BOUND` terms (e.g. BT-84/IBAN) contribute no widget here — their value already lives on the
 * existing property they are bound to; only `FREE` terms get an input (same rule as
 * [B2bProfileFieldsSection]).
 */
class ScopedB2bProfileFieldsSection(
    private val b2bFieldValueQueries: B2bFieldValueQueryPort,
    private val ownerType: B2bFieldOwnerType,
    private val tr: (String) -> String,
) : VerticalLayout() {

    private data class FieldWidget(val input: B2bFieldWidget, val btCode: String, val defaultValue: String?)

    private class CountryGroupUi(val container: Div, val formLayout: FormLayout) {
        val widgets = mutableListOf<FieldWidget>()
        var triggerCountries: Set<Country> = emptySet()
    }

    private val unscopedForm = FormLayout().apply {
        setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("480px", 2))
    }
    private val unscopedWidgets = mutableListOf<FieldWidget>()
    private val unscopedContainer = Div().apply {
        addClassName("sp-bt-pair-group")
    }

    // Kept in insertion order (alpha2, set once in setFields), never rebuilt afterwards.
    private val countryGroups = linkedMapOf<Country, CountryGroupUi>()

    private val hintLabel = Span().apply {
        addClassNames(CSS_FS_08, CSS_TEXT_MUTED)
        isVisible = false
    }

    /** True once at least one FREE field exists, unscoped or scoped — used by the owning view to
     *  decide whether to add this section to the tab at all. */
    val hasContent: Boolean get() = unscopedWidgets.isNotEmpty() || countryGroups.isNotEmpty()

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
        unscopedContainer.add(
            Span(tr("senderProfile.einvoice.profileFields")).apply { addClassNames(CSS_FS_08, "font-weight-semibold", CSS_TEXT_MUTED) },
            unscopedForm,
        )
        add(unscopedContainer, hintLabel)
    }

    /** Builds every widget/group from [groups] (typically
     *  [de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort.findB2bSellerFieldsUnion]'s already-deduped result) — called exactly
     *  once, at construction time; see the class KDoc for why the tree is never rebuilt later. */
    fun setFields(groups: List<B2bSellerFieldGroupDto>) {
        groups.forEach { g ->
            val term = g.field.term ?: return@forEach
            if (term.binding != B2bTermBinding.FREE) return@forEach
            val widget = B2bFieldWidgetFactory.createWidget(term, g.field, tr)
            val fieldWidget = FieldWidget(widget, term.officialCode, g.field.defaultValue)
            val group = g.group
            if (group == null) {
                unscopedWidgets.add(fieldWidget)
                unscopedForm.add(widget.component)
            } else {
                val ui = countryGroups.getOrPut(group) { buildCountryGroupUi(group) }
                ui.widgets.add(fieldWidget)
                ui.triggerCountries += g.triggerCountries
                ui.formLayout.add(widget.component)
            }
        }
        unscopedContainer.isVisible = unscopedWidgets.isNotEmpty()
        countryGroups.toList().sortedBy { (country, _) -> country.alpha2 }.forEach { (_, ui) -> add(ui.container) }
    }

    private fun buildCountryGroupUi(country: Country): CountryGroupUi {
        val heading = Span(MessageFormat.format(tr("senderProfile.einvoice.profileFields.country"), countryDisplayName(country))).apply {
            addClassNames(CSS_FS_08, "font-weight-semibold", CSS_TEXT_MUTED)
        }
        val formLayout = FormLayout().apply {
            setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("480px", 2))
        }
        val container = Div().apply {
            addClassName("sp-bt-pair-group")
            add(heading, formLayout)
            isVisible = false
        }
        return CountryGroupUi(container, formLayout)
    }

    /** Loads the current values of an existing owner ([ownerId]); `null` (not yet persisted)
     *  clears every widget instead — same contract as [B2bProfileFieldsSection.readFrom]. */
    fun readFrom(ownerId: Long?) {
        val values = if (ownerId == null) emptyMap() else b2bFieldValueQueries.findB2bFieldValues(ownerType, ownerId)
        allWidgets().forEach { B2bFieldWidgetFactory.setWidgetValue(it.input, values[it.btCode]) }
    }

    /** Prefills every rendered widget still blank after [readFrom] with its own field's
     *  default value, across *every* group regardless of current visibility — same "blank widget
     *  only" contract as [B2bProfileFieldsSection.applyDefaults]; a hidden group's default still
     *  gets written so it is there once the group becomes visible, exactly like a manually typed
     *  value already survives a visibility toggle (class KDoc). */
    fun applyDefaults() {
        allWidgets().forEach { widget ->
            val default = widget.defaultValue
            if (default.isNullOrBlank()) return@forEach
            if (B2bFieldWidgetFactory.widgetValue(widget.input).isNullOrBlank()) {
                B2bFieldWidgetFactory.setWidgetValue(widget.input, default)
            }
        }
    }

    /** The in-flight widget state, keyed by `bt_code`, across *every* group regardless of current
     *  visibility — a hidden group's typed value is written back exactly like today
     *  ([de.littletrotter.ui.view.masterdata.SenderProfileView.persistEntity]'s `saveB2bFieldValues` semantics unchanged). */
    fun currentValues(): Map<String, String?> = allWidgets().associate { it.btCode to B2bFieldWidgetFactory.widgetValue(it.input) }

    private fun allWidgets(): List<FieldWidget> = unscopedWidgets + countryGroups.values.flatMap { it.widgets }

    /**
     * Recomputes which country groups are visible for [sellerCountries] (see
     * [de.littletrotter.domain.masterdata.sellerRegistrationCountries]) and the empty-state hint.
     * Called by the owning view on every relevant change (vatIdField, EU VAT registrations) — same
     * reactive wiring [de.littletrotter.ui.view.masterdata.SenderProfileView.updatePartySectionVisibility] already uses — and once more
     * after [readFrom], so a freshly opened record starts with the right groups shown.
     */
    fun applySellerCountries(sellerCountries: Set<Country>) {
        var anyScopedVisible = false
        countryGroups.forEach { (group, ui) ->
            val visible = isB2bSellerFieldGroupVisible(group, ui.triggerCountries, sellerCountries)
            ui.container.isVisible = visible
            if (visible) anyScopedVisible = true
        }
        val showHint = countryGroups.isNotEmpty() && !anyScopedVisible
        hintLabel.isVisible = showHint
        if (showHint) {
            val countryNames = countryGroups.keys.sortedBy { it.alpha2 }.joinToString(", ") { countryDisplayName(it) }
            hintLabel.text = MessageFormat.format(tr("senderProfile.einvoice.profileFields.hint"), countryNames)
        }
    }
}
