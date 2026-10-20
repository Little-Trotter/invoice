package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasValue
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.spi.form.FieldKind
import de.littletrotter.spi.form.FieldSpec
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import de.littletrotter.spi.form.InvoiceFormModule
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate

/**
 * Renders the field-modules registered for one [FormSlot] into a [FormLayout] fragment.
 *
 * Every injected [InvoiceFormModule]'s [FieldSpec]s are turned into a Vaadin widget by `kind`,
 * labelled from its i18n key and bound to the shared view [Binder] by property name (spec.id ==
 * JavaBean property). The result is appended to `slotLayout`, so the surrounding view needs no
 * per-field code. With no modules registered nothing is added: no empty paragraphs, no layout
 * offset.
 *
 * Read-only handling mirrors the neighbouring hand-built fields: when the invoice is finalized
 * or cancelled ([FormContext.readOnly]) every module widget is read-only too. Independently, a
 * single field can be contextually disabled via [FieldSpec.enabled] — e.g. the invoice
 * format `ComboBox` while neither of its enabling conditions holds — which still renders the
 * widget, just non-interactive; [FormContext.readOnly] takes precedence when both apply.
 */
class ModuleFieldSection(
    private val slot: FormSlot,
    private val modules: Collection<InvoiceFormModule>,
    private val ctx: FormContext,
    private val binder: Binder<*>,
) {
    // B3 — the widgets [attachTo] just built, keyed by [FieldSpec.id] (== bound bean property
    // name); lets a caller elsewhere in the pane (the free-text chips) read a module field's live
    // value without re-deriving/duplicating the module's own rendering.
    private val builtFields = mutableMapOf<String, Component>()

    /** The already-built widget for [propertyId] (a [FieldSpec.id]), or `null` if no attached
     *  module rendered that field for this section's [slot]. */
    fun componentFor(propertyId: String): Component? = builtFields[propertyId]

    /**
     * Appends all module fields matching [slot] to `slotLayout`.
     *
     * @return whether any field was rendered.
     */
    fun attachTo(slotLayout: FormLayout): Boolean {
        val specs = modules.flatMap { it.fields(ctx) }.filter { it.slot == slot }
        if (specs.isEmpty()) return false
        specs.forEach { spec ->
            val field = createField(spec)
            builtFields[spec.id] = field
            if (ctx.readOnly) {
                setReadOnly(field)
            } else if (!spec.enabled) {
                // createField only ever returns TextField/DatePicker/Checkbox/ComboBox — every one
                // of them implements HasEnabled via AbstractField -> HasValueAndElement, so the
                // `is HasEnabled` check the "false" side would need can never fail here.
                (field as com.vaadin.flow.component.HasEnabled).isEnabled = false
            }
            slotLayout.add(field)
            if (spec.defaultLabelColspan > 1) slotLayout.setColspan(field, spec.defaultLabelColspan)
        }
        return true
    }

    /**
     * [bindTyped] binds through the field's own concrete `HasValue<*, V>` (e.g. `TextField`
     * is a `HasValue<*, String>`) instead of a common `Component`/`HasValue<*, Any?>` reference:
     * [FieldSpec.getter]/[FieldSpec.setter] are `Any`-typed (a module may bind an enum or any other
     * value kind), but every caller here supplies [cast] as a concrete, non-generic target type
     * (`String?`/[LocalDate]?/`Boolean?`) resolved from the very `when (spec.kind)` branch that
     * built the field — a checked cast, not a generic-erasure one. [createField]'s callers keep
     * the field's concrete Vaadin type in scope for exactly this reason, rather than widening to
     * `Component` before binding.
     */
    // Takes the whole [spec] (property id + getter + setter travel
    // together) instead of three separate parameters, the same "bundle instead of grow the
    // parameter list" convention this codebase's other `OverviewJoins`-style holders already use.
    private fun <V> bindTyped(field: HasValue<*, V>, spec: FieldSpec, cast: (Any?) -> V) {
        val getter = spec.getter
        val setter = spec.setter
        if (getter != null && setter != null) {
            // Custom binding via the spec's getter/setter lambdas takes precedence — this is what
            // lets a module bind an enum-backed value without forcing a String bridge property
            // onto the bound bean.
            binder.bind(field, { bean -> cast(getter(bean)) }, { bean, value -> setter(bean, value) })
        } else {
            // Reflectively bind by JavaBean property name — via explicit getter/setter lambdas
            // resolved per access, deliberately NOT Binder.bind(field, name): that variant
            // registers a forField builder BEFORE resolving the property, and an unresolvable
            // name leaves the builder dangling, which turns the next readBean into an
            // IllegalStateException for the WHOLE form (PO-Bugreport 2026-08-30, /invoice/627 —
            // ProjectNumberModule's field vs. a form state lacking the property). With lambdas a
            // missing property degrades to the documented "rendered but unbound" behavior:
            // reads yield null, writes are dropped, one WARN per property. The field's actual
            // value type (String/LocalDate/Boolean) is preserved either way.
            binder.bind(
                field,
                { bean -> cast(accessor(bean, spec.id, "get")?.invoke(bean)) },
                { bean, value -> accessor(bean, spec.id, "set", value?.javaClass)?.invoke(bean, value) },
            )
        }
    }

    /** Binds [field] via [bindTyped], degrading to a logged warning instead of breaking the whole
     *  form when the bound bean does not expose [FieldSpec.id] (see [bindTyped]'s reflective
     *  branch KDoc for why this must be caught here rather than left to propagate). */
    private fun <V> bindOrWarn(field: HasValue<*, V>, spec: FieldSpec, cast: (Any?) -> V) {
        runCatching { bindTyped(field, spec, cast) }
            .onFailure {
                org.slf4j.LoggerFactory.getLogger(ModuleFieldSection::class.java)
                    .warn("Module field '{}' could not bind to property '{}': {}", spec.id, spec.id, it.message)
            }
    }

    /** Resolves the JavaBean accessor for [property] on [bean], or `null` (with a once-per-
     *  property WARN) when the bean does not expose it — see [bindTyped]'s reflective branch. */
    private fun accessor(
        bean: Any,
        property: String,
        prefix: String,
        valueType: Class<*>? = null,
    ): java.lang.reflect.Method? {
        val name = prefix + property.replaceFirstChar { it.uppercaseChar() }
        val method = bean.javaClass.methods.firstOrNull {
            it.name == name && (prefix == "get" || it.parameterCount == 1) &&
                (valueType == null || prefix == "get" || it.parameterTypes[0].isAssignableFrom(valueType) || it.parameterTypes[0].isPrimitive)
        }
        if (method == null && warnedMissingProperties.add("${bean.javaClass.name}#$property")) {
            org.slf4j.LoggerFactory.getLogger(ModuleFieldSection::class.java)
                .warn("Module field '{}' has no bean property on {} — rendered but unbound", property, bean.javaClass.simpleName)
        }
        return method
    }

    private val warnedMissingProperties = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Maps [FieldSpec.kind] to a Vaadin widget. The label comes from the module's i18n key,
     *  without any view coupling. [FieldSpec.helperText] and [FieldSpec.required]
     *  are applied uniformly here so no module needs its
     *  own widget-creation code for them. */
    private fun createField(spec: FieldSpec): Component {
        val field: Component = when (spec.kind) {
            FieldKind.TEXT, FieldKind.NUMBER ->
                textField(spec).also { bindOrWarn(it, spec) { v -> v as String? } }
            FieldKind.DATE -> DatePicker(UiTranslations.tr(spec.i18nKey)).apply {
                isClearButtonVisible = true
            }.also { bindOrWarn(it, spec) { v -> v as LocalDate? } }
            FieldKind.BOOLEAN ->
                Checkbox(UiTranslations.tr(spec.i18nKey)).also { bindOrWarn(it, spec) { v -> v as Boolean? } }
            FieldKind.ENUM -> ComboBox<String>(UiTranslations.tr(spec.i18nKey)).apply {
                setItems(spec.options.map { it.value })
                setItemLabelGenerator { value -> enumItemLabel(value, spec.options) }
                isClearButtonVisible = true
                setWidthFull()
            }.also { bindOrWarn(it, spec) { v -> v as String? } }
        }
        applyHelperTextAndRequired(field, spec)
        return field
    }

    private fun applyHelperTextAndRequired(field: Component, spec: FieldSpec) {
        if (field is com.vaadin.flow.component.HasHelper) {
            // helperTextKey (a message-bundle key an application-layer module handed
            // over because it may not depend on UiTranslations itself) takes precedence over the
            // already-resolved helperText string; a module should only ever set one of the two.
            val resolvedHelperText = spec.helperTextKey?.let { UiTranslations.tr(it) } ?: spec.helperText
            resolvedHelperText?.let { field.helperText = it }
        }
        if (spec.required) {
            field.element.setAttribute("aria-required", "true")
        }
        when (field) {
            is TextField -> field.isRequiredIndicatorVisible = spec.required
            is DatePicker -> field.isRequiredIndicatorVisible = spec.required
            is ComboBox<*> -> field.isRequiredIndicatorVisible = spec.required
            else -> {}
        }
    }

    private fun textField(spec: FieldSpec): TextField = TextField(UiTranslations.tr(spec.i18nKey)).apply {
        isClearButtonVisible = true
        setWidthFull()
        if (spec.kind == FieldKind.NUMBER) {
            pattern = "\\d+([.,]\\d+)?"
        }
    }

    private fun setReadOnly(field: Component) {
        when (field) {
            is TextField -> field.isReadOnly = true
            is DatePicker -> field.isReadOnly = true
            is Checkbox -> field.isReadOnly = true
            is ComboBox<*> -> field.isReadOnly = true
            else -> {}
        }
    }
}

/** The ENUM ComboBox's item label: the option's own i18n label, or the raw value itself for one
 *  the module never declared (defensive — a stale/foreign value must still render as something
 *  rather than crash the label generator). A top-level function, not a `ComboBox<String>` lambda
 *  a test would have to extract from the rendered tree unchecked, since it needs none of
 *  [ModuleFieldSection]'s own state. */
internal fun enumItemLabel(value: String, options: List<de.littletrotter.spi.form.EnumeratedOption>): String =
    options.firstOrNull { it.value == value }?.let { UiTranslations.tr(it.i18nKey) } ?: value
