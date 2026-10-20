package de.littletrotter.spi.form

import de.littletrotter.shared.b2b.B2bTerm

/**
 * The UI field-flavors a field-[InvoiceFormModule] can register. Kept Vaadin-free so the SPI
 * contract has no widget types in its happy path (the Vaadin binding lives behind the interface,
 * in the module implementations).
 */
enum class FieldKind {
    /** Single-line text. */
    TEXT,

    /** Numeric input. */
    NUMBER,

    /** Date picker. */
    DATE,

    /** Toggle / checkbox. */
    BOOLEAN,

    /** Drop-down fed from [FieldSpec.options]. */
    ENUM,
}

/**
 * A single selectable value for a [FieldKind.ENUM] field, already resolved to its i18n key so
 * the module needs no application lookup at registration time.
 */
data class EnumeratedOption(
    /** The stable value that is bound to the data model (e.g. `"TYPE_A"`). */
    val value: String,
    /** Message-bundle key for the human-readable label. */
    val i18nKey: String,
)

/**
 * Reference to the XML-export target a field feeds, mirroring the "null/blank &rarr; skipped"
 * convention of the export layer. `null` means the field is only rendered on the PDF/UI and has
 * no XML slot.
 */
data class XmlTarget(
    /**
     * The [B2bTerm] this field's XML export slot corresponds to, e.g. [B2bTerm.BT_11].
     * Typed now (was a free `"BT-nn"` string); the earlier "informational, no strict
     * format" softness is exactly what typing this removes — a module can no longer name a
     * business term that doesn't exist, or (review finding, 29.08.2026) the *wrong* one.
     */
    val businessTerm: B2bTerm? = null,
    /** XPath-like location hint inside the invoice XML, e.g. `/Invoice/ProjectNumber`. */
    val xpath: String,
)

/**
 * Where in the form a module's fields may dock. A stable enum (not free strings) so the view can
 * render them at a defined place and the SPI surface stays machine-checkable. New slots are a
 * minor (additive) release of the SPI.
 */
enum class FormSlot {
    /** The references strip next to order/offer references on the overview/edit layout. */
    OVERVIEW_REFERENCES,

    /** Legal/commercial block of the seller party. */
    PARTY_SELLER_LEGAL,

    /** Position detail: per-item fields on the item grid/dialog. */
    ITEM_DETAIL,
}

/**
 * Description of a single field a [InvoiceFormModule] contributes to the form. Pure data, free of
 * Vaadin view types in the registration path — the view is responsible for turning [kind] into an
 * actual widget.
 *
 * @param id stable, module-unique identifier (used as the key to read/write the bound value).
 * @param i18nKey message-bundle key for the field's label.
 * @param kind the UI flavor the field should be rendered as.
 * @param slot the [FormSlot] in which this field belongs.
 * @param xmlTarget optional export destination; `null` = PDF/UI visual only.
 * @param defaultLabelColspan preferred label right-alignment span within a FormLayout row.
 * @param options required when [kind] is [FieldKind.ENUM], ignored otherwise.
 * @param required whether the field is mandatory (module-defined, e.g. a B2B
 *   profile field marked `MANDATORY`). Defaults to `false` so every pre-existing [FieldSpec]
 *   construction site keeps behaving as before this parameter existed.
 * @param helperText optional user-facing definition/help text shown under the
 *   field, already resolved (e.g. a B2B profile field's anwenderdefinierte Definition, which is
 *   domain-stored free text, not a message-bundle key). `null` = no helper text, the pre-existing
 *   rendering. Ignored when [helperTextKey] is set.
 * @param helperTextKey message-bundle key for a helper text a module cannot resolve
 *   to a string itself: `application`-layer modules (e.g. `InvoiceFormatModule`) must not depend
 *   on `ui.i18n.UiTranslations` (enforced by `ArchitectureRulesTest`'s layering rule), so they hand
 *   over the *key* instead and [de.littletrotter.ui.component.ModuleFieldSection] — already in the
 *   `ui` layer — resolves it at render time. Takes precedence over [helperText] when both are set;
 *   a module should only ever populate one of the two. `null` = no helper text (unless
 *   [helperText] is set).
 * @param enabled whether the rendered widget accepts input right now. `false` still
 *   renders the field (unlike the older pattern of a module returning no [FieldSpec] at all to
 *   hide a field outright) — a visibly-present, disabled field paired with [helperText] explaining
 *   *why* it is disabled is the intended UX for a contextually-forced value (e.g.
 *   `InvoiceFormatModule`'s `ComboBox`, forced to `GERMAN` when neither of its two enabling
 *   conditions holds). Overridden by [FormContext.readOnly] regardless of this value — a
 *   finalized/cancelled invoice's fields stay read-only either way. Defaults to `true` so every
 *   pre-existing [FieldSpec] construction site keeps behaving as before this parameter existed.
 * @param getter optional custom getter lambda overriding the reflective JavaBean property
 *   binding: when provided, it is called to read the field value instead of resolving a
 *   property by [id].
 * @param setter optional custom setter lambda overriding the reflective JavaBean property
 *   binding: when provided, it is called to write the field value instead of resolving a
 *   property by [id]. Getter and setter must be provided together for custom binding to apply.
 */
data class FieldSpec(
    val id: String,
    val i18nKey: String,
    val kind: FieldKind,
    val slot: FormSlot,
    val xmlTarget: XmlTarget? = null,
    val defaultLabelColspan: Int = 1,
    val options: List<EnumeratedOption> = emptyList(),
    val required: Boolean = false,
    val helperText: String? = null,
    val helperTextKey: String? = null,
    val enabled: Boolean = true,
    val getter: ((Any) -> Any?)? = null,
    val setter: ((Any, Any?) -> Unit)? = null,
)
