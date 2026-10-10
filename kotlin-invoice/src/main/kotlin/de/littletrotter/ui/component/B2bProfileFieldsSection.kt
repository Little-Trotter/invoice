package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasHelper
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.domain.reference.B2bFieldValueOrigin
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.shared.b2b.B2bTermBinding

/**
 * Dynamic section rendering a set of [B2bProfileFieldDto]s as inputs for one owner (invoice, invoice
 * item, customer). Not built on [ModuleFieldSection]/`Binder` reflection like the invoice-form
 * modules: a B2B field set is chosen at runtime (per profile), so there is no fixed JavaBean
 * property to bind against. The section only renders: callers load the field list and the stored
 * values through their own ports and hand them in ([setGroupedFields], [readFrom],
 * [readResolved]), and persist [currentValues] themselves.
 *
 * `BOUND` terms (e.g. BT-84/IBAN) contribute no widget here — their value already lives on the
 * existing property they are bound to; only `FREE` terms get an input.
 */
class B2bProfileFieldsSection(
    private val tr: (String) -> String,
) : VerticalLayout() {

    // officialCode is resolved once, in setFields, from the same non-null `term` that already
    // gated whether this field got a widget at all — every other use site (readFrom, prefillIfBlank,
    // currentValues) no longer needs to re-derive it through field.term?.officialCode.
    // fix-einvoice-guided-profile-party-values — baseHelperText is the term's own definitional
    // helper text (as created by B2bFieldWidgetFactory), captured once so readResolved can restore
    // it verbatim when a field's origin is no longer PARTY; inheritedValue is the write-back rule's
    // comparison target, null for a widget never loaded through readResolved so every other
    // caller (invoice items, RecipientView) keeps currentValues()'s pre-existing behavior exactly.
    // Plain class, not a data class: inheritedValue is mutated in place after
    // construction (readResolved below); equals/hashCode/copy are never used on a widget.
    private class FieldWidget(
        val field: B2bProfileFieldDto,
        val officialCode: String,
        val input: B2bFieldWidget,
        val baseHelperText: String?,
        var inheritedValue: String? = null,
    ) {
        val component: Component get() = input.component
    }

    private val widgets = mutableListOf<FieldWidget>()

    // egro-erechnung-tab — callers outside this section (the E-Rechnung tab's caption/progress
    // bar/step squares) need to know "did any widget's value just change", live, without this
    // section having to know what a caller does with that. A plain listener list, not a Vaadin
    // event bus: there is exactly one kind of event here.
    private val changeListeners = mutableListOf<() -> Unit>()

    val hasContent: Boolean get() = widgets.isNotEmpty()

    init {
        isPadding = false
        isSpacing = true
        setWidthFull()
    }

    /** Rebuilds the widgets for an explicit field list (e.g. an invoice item's
     *  profile-gated LINE fields): a single, headingless group. Shorthand for [setGroupedFields] with one `null`-headed group. */
    fun setFields(fields: List<B2bProfileFieldDto>) = setGroupedFields(listOf(null to fields))

    /**
     * fix-einvoice-guided-profile-party-values, Required change 2 — rebuilds the widgets from
     * possibly several named [groups] (heading text to that group's fields), each rendered under
     * its own eyebrow heading in its own `FormLayout`, mirroring the group-heading pattern
     * [ScopedB2bProfileFieldsSection] already uses for its country groups (an eyebrow [Span] + a
     * `sp-bt-pair-group` container). A `null` heading renders no eyebrow and no wrapping container
     * — just the bare `FormLayout` directly, the same DOM shape [setFields] always had, for the
     * single-group callers that never had (and don't need) a heading. A group with no `FREE` field
     * renders nothing at all, not an empty heading.
     */
    fun setGroupedFields(groups: List<Pair<String?, List<B2bProfileFieldDto>>>) {
        removeAll()
        widgets.clear()
        groups.forEach { (heading, fields) ->
            // A field with no term at all (never catalog-bound) is dropped right here via
            // mapNotNull's own null-filter, so `term` is a genuinely non-null local for the rest of
            // this block — no redundant, permanently-dead `field.term ?: return` needed downstream.
            val freeFields = fields.mapNotNull { field -> field.term?.let { term -> term to field } }
                .filter { (term, _) -> term.binding == B2bTermBinding.FREE }
            if (freeFields.isEmpty()) return@forEach
            val formLayout = FormLayout().apply {
                setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("480px", 2))
            }
            freeFields.forEach { (term, field) ->
                val widget = B2bFieldWidgetFactory.createWidget(term, field, tr)
                // Every widget type B2bFieldWidgetFactory.createWidget can return (TextField,
                // DatePicker, a searchable ComboBox) is a com.vaadin.flow.component.HasValue —
                // an unconditional cast replaces the former one-arm-per-concrete-type `when` (every
                // arm did the exact same thing anyway); a factory that ever broke this invariant
                // would fail loudly here instead of silently dropping the value-change wiring.
                (widget.component as com.vaadin.flow.component.HasValue<*, *>).addValueChangeListener { notifyValueChange() }
                val baseHelper = (widget.component as HasHelper).helperText
                widgets.add(FieldWidget(field, term.officialCode, widget, baseHelper))
                formLayout.add(widget.component)
            }
            if (heading == null) {
                add(formLayout)
            } else {
                add(
                    Div().apply {
                        addClassName("sp-bt-pair-group")
                        add(Span(heading).apply { addClassNames("fs-08", "font-weight-semibold", "text-muted") })
                        add(formLayout)
                    },
                )
            }
        }
    }

    /** Loads the stored [values] (keyed by `bt_code`) into the widgets; a code missing from
     *  [values] clears its widget, so an empty map resets a section for a not-yet-persisted owner. */
    fun readFrom(values: Map<String, String?>) {
        widgets.forEach { B2bFieldWidgetFactory.setWidgetValue(it.input, values[it.officialCode]) }
    }

    /**
     * fix-einvoice-guided-profile-party-values, Required change 1/2/3 — loads [resolved] (the
     * invoice > party > default chain, [B2bFieldValueQueryPort.findB2bGuidedFieldValues]) directly
     * onto the widgets, replacing the separate [readFrom]/[applyDefaults] two-step for callers that
     * need the party tier: each widget starts at its resolved value, remembers the tier-2/3
     * ("inherited") value [currentValues] compares against for the write-back rule (an
     * untouched inherited value must never materialize as an invoice row), and — for a value whose
     * [B2bFieldValueOrigin] is [B2bFieldValueOrigin.PARTY] — appends [inheritedHint] to the
     * widget's own helper text so the user can tell "this came from master data, not yet an
     * invoice-level choice" (a [B2bFieldValueOrigin.DEFAULT] value gets no hint: a profile default
     * is not master data about this specific party). A field this section does not render (not in
     * [resolved] at all) is left untouched.
     */
    fun readResolved(resolved: Map<String, ResolvedB2bFieldValueDto>, inheritedHint: String) {
        widgets.forEach { widget ->
            val entry = resolved[widget.officialCode]
            B2bFieldWidgetFactory.setWidgetValue(widget.input, entry?.value)
            widget.inheritedValue = entry?.inheritedValue
            // Every rendered widget type (TextField, DatePicker, ComboBox) implements HasHelper —
            // same "unconditional cast, not a redundant type check" reasoning as setGroupedFields'
            // HasValue wiring above.
            (widget.component as HasHelper).helperText = if (entry?.origin == B2bFieldValueOrigin.PARTY) {
                listOfNotNull(widget.baseHelperText?.ifBlank { null }, inheritedHint).joinToString(" · ")
            } else {
                widget.baseHelperText.orEmpty()
            }
        }
    }

    /** Prefills [btCode]'s widget with [value] only when it's
     *  currently blank (never overwrites a value the user already typed or that was loaded from
     *  storage), and only if this section actually renders that term; a no-op otherwise. Used to
     *  default BT-155 (seller's item number) to the selected article's master-data number while
     *  still letting the user override it. */
    fun prefillIfBlank(btCode: String, value: String?) {
        if (value.isNullOrBlank()) return
        val widget = widgets.find { it.officialCode == btCode } ?: return
        if (B2bFieldWidgetFactory.widgetValue(widget.input).isNullOrBlank()) {
            B2bFieldWidgetFactory.setWidgetValue(widget.input, value)
        }
    }

    /** Prefills every rendered widget still blank with its own field's
     *  [B2bProfileFieldDto.defaultValue] (`null`/blank default = nothing to do for that field). Same
     *  "blank widget only" contract as [prefillIfBlank], just sourced from each field's own
     *  default instead of one caller-supplied value for one term — call after [readFrom] so a
     *  record with an existing stored value (including one deliberately left blank/cleared) is
     *  read first and never gets overwritten; only a widget still blank afterwards (typically: no
     *  stored `b2b_field_value` row for this term at all) gets the default. */
    fun applyDefaults() {
        widgets.forEach { widget ->
            val default = widget.field.defaultValue
            if (default.isNullOrBlank()) return@forEach
            if (B2bFieldWidgetFactory.widgetValue(widget.input).isNullOrBlank()) {
                B2bFieldWidgetFactory.setWidgetValue(widget.input, default)
            }
        }
    }

    /**
     * The in-flight widget state, keyed by `bt_code` — the caller persists it via
     * [de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase.saveB2bFieldValues]; finalization
     * checks the mandatory seller fields
     * ([de.littletrotter.application.reference.B2bProfileFieldDefinitionService.missingMandatorySellerFields]).
     *
     * A widget loaded through [readResolved] whose
     * live value still equals its own [FieldWidget.inheritedValue] (tier 2/3, never touched by the
     * user since) reports `null` here instead of that value:
     * [de.littletrotter.application.reference.B2bFieldValueService.saveFieldValues]
     * already deletes the row on `null`, so an untouched inherited value never materializes as a
     * spurious invoice-level row — a later master-data correction keeps reaching this invoice. A
     * widget never loaded through [readResolved] has `inheritedValue == null`, so this reduces to
     * the pre-existing behavior exactly (a blank widget already reports `null` on its own).
     */
    fun currentValues(): Map<String, String?> = widgets.associate { widget ->
        val current = B2bFieldWidgetFactory.widgetValue(widget.input)
        widget.officialCode to (current.takeUnless { it == widget.inheritedValue })
    }

    /** Effective values currently visible to the user, before [currentValues]' inherited-value
     * suppression. INV-J needs these for its one-time free-text copy while persistence must keep
     * using [currentValues] so inherited values do not become invoice overrides. */
    fun displayedValues(): Map<String, String?> = widgets.associate { widget ->
        widget.officialCode to B2bFieldWidgetFactory.widgetValue(widget.input)
    }

    /** Field-navigation hook for the aggregate invoice-header validation summary. */
    fun componentFor(btCode: String): Component? = widgets.firstOrNull { it.officialCode == btCode }?.component

    /** egro-erechnung-tab — fires [listener] on every rendered widget's value change (including
     *  ones added by a later [setFields]/[setProfile] call). Registration order doesn't matter:
     *  widgets always read the live [changeListeners] list when they fire, not a snapshot taken
     *  at widget-creation time. */
    fun addValueChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    /** The rendered widgets an "Enter advances to the next step" shortcut makes sense on
     *  (egro-erechnung-tab section 2.3) — `TextField`/`DatePicker`, not `Checkbox`, which has no
     *  notion of a text-entry Enter. */
    fun enterAdvanceCandidates(): List<Component> = widgets.map { it.component }.filter { it is com.vaadin.flow.component.textfield.TextField || it is com.vaadin.flow.component.datepicker.DatePicker }

    private fun notifyValueChange() = changeListeners.forEach { it() }
}
