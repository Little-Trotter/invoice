package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.InvoiceHeaderFormState
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.component.ModuleFieldSection
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import java.util.Locale

// The entry pane's "E-Rechnung" tab: guided B2B stepper, or a plain flat form when there's nothing
// to guide. The mandatory-input rule lives in EinvoiceMandatoryInputs.kt, the guided-mode stepper
// in [EinvoiceGuidedSteps].

internal const val BAU_STATUS_SQUARE = "bau-status-square"
internal const val BAU_STATUS_SQUARE_FILLED = "bau-status-square--filled"
internal const val BAU_STATUS_SQUARE_OPEN = "bau-status-square--open"

/** A Bauhaus status square: 10×10px, filled = complete, outline = open — filled vs.
 *  outline shape difference so the state reads without relying on color alone. */
internal fun statusSquare(filled: Boolean): Span = Span().apply {
    addClassName(BAU_STATUS_SQUARE)
    element.classList.set(BAU_STATUS_SQUARE_FILLED, filled)
    element.classList.set(BAU_STATUS_SQUARE_OPEN, !filled)
}

/** The base label for the E-Rechnung tab caption: plain "E-Rechnung" when not guided, or
 *  "E-Rechnung (geführt)" when guided mode is active. Pure function: single source of truth
 *  for the label, reused by the tab caption and tests. */
internal fun einvoiceTabLabelBase(trigger: EInvoiceGuidedTrigger, tr: (String) -> String): String {
    val baseLabel = tr("invoiceDetail.entry.tab.einvoice")
    return if (trigger == EInvoiceGuidedTrigger.NONE) {
        baseLabel
    } else {
        "$baseLabel ${tr("invoiceDetail.entry.tab.einvoice.guided.suffix")}"
    }
}

/** What [InvoiceEinvoiceEntryTab.build] hands back to [InvoiceEntryPane]: the tab's caption
 *  (live status square + label), its content, and a way to reveal one of this tab's fields —
 *  switching the guided step that hosts it, if any — before `UiNotifications.errorOn` runs on it. */
internal data class EInvoiceTabResult(
    val captionComponent: Component,
    val content: Component,
    /** Reveals `field` if it belongs to this tab (switching its guided step) and returns true;
     *  returns false — doing nothing — for a field that lives on tab 1. */
    val revealField: (Component) -> Boolean,
    /** The already-built "projectNumber" module widget ([de.littletrotter.application.invoice
     *  .ProjectNumberModule]), so the notes tab's free-text chips can read its live value without
     *  re-rendering or re-deriving it. `null` only if that module is ever unregistered. */
    val projectNumberField: TextField?,
)

/** The Vaadin fields [InvoiceEntryPane.build] created and bound; this tab only lays them out
 *  differently depending on the guided-mode trigger — never mutates or re-derives them. */
internal class EinvoiceFormFields(
    val buyerReferenceField: TextField,
    val purchaseOrderReferenceField: TextField,
    val orderReferenceField: TextField,
    val orderReferenceDateField: DatePicker,
    val sellerVatCountryBox: ComboBox<Country>,
    val b2bProfileBox: ComboBox<B2bProfileDto>,
    val b2bHeaderSection: B2bProfileFieldsSection,
)

/** The parameters [InvoiceEinvoiceEntryTab.build] receives from its caller — grouped to avoid
 *  exceeding the LongParameterList threshold. */
internal class EinvoiceTabParameters(
    val current: InvoiceDetailDto,
    val party: EntryPartyFields,
    val b2bProfile: B2bProfileSelection,
    val binder: Binder<InvoiceHeaderFormState>,
    val references: EntryReferenceFields,
    val b2bHeaderSection: B2bProfileFieldsSection,
)

/** Everything [InvoiceEinvoiceEntryTab.build] derives once at entry and hands to the guided/flat
 *  content builders, so neither needs its own >6-parameter signature. */
internal class EinvoiceTabContext(
    params: EinvoiceTabParameters,
    val mandatoryHeaderFields: List<MandatoryB2bHeaderField>,
    val buyerReferenceLabel: String,
) {
    val current: InvoiceDetailDto = params.current
    val effectiveB2bProfile: B2bProfileDto? = params.b2bProfile.profile
    val binder: Binder<InvoiceHeaderFormState> = params.binder
    val fields = EinvoiceFormFields(
        params.references.buyerReferenceField, params.references.purchaseOrderReferenceField, params.references.orderReferenceField,
        params.references.orderReferenceDateField, params.party.sellerVatCountryBox, params.b2bProfile.box, params.b2bHeaderSection,
    )
    val trigger: EInvoiceGuidedTrigger = einvoiceGuidedModeTrigger(params.b2bProfile.profileId, current.recipient?.routingId)
    val guided: Boolean = trigger != EInvoiceGuidedTrigger.NONE
    val tab2Fields: Set<Component> = with(fields) {
        setOf(buyerReferenceField, purchaseOrderReferenceField, orderReferenceField, orderReferenceDateField, sellerVatCountryBox, b2bProfileBox)
    }

    fun currentStatuses(): List<EInvoiceInputStatus> = einvoiceInputStatuses(
        EInvoiceInputStatusQuery(guided, buyerReferenceLabel, fields.buyerReferenceField.value, mandatoryHeaderFields, fields.b2bHeaderSection.currentValues()),
    )
}

internal fun currentInvoiceDto(current: InvoiceDetailDto) = InvoiceDto(
    id = current.id,
    invoiceNumber = InvoiceNumberDto(current.invoiceNumber),
    // projectNumber/costCenter dropped: no module actually reads either off `ctx`
    // (`ProjectNumberModule.fields` ignores `ctx.invoice` entirely, a plain always-editable
    // field), and `InvoiceDetailDto` carries neither today — nothing regresses.
    references = InvoiceReferencesDto(),
    // InvoiceFormatModule's enabled/disabled decision (isInvoiceFormatEditable) needs
    // reverseCharge alongside sellerVatRegistrationCountry; omitting it here would silently
    // default totals.reverseCharge to false and keep the format ComboBox locked to GERMAN for
    // every reverse-charge-only invoice regardless of the persisted invoice.reverseCharge decision. Read
    // from the persisted `InvoiceDetailDto`, not the entry form's live edits — this snapshot is
    // taken once, at pane-build time, exactly like the earlier code read the then-current
    // `Invoice` bean before any field write-back (the Binder here is never `setBean`-continuous).
    totals = InvoiceTotalsDto(reverseCharge = current.reverseCharge),
    meta = InvoiceMetaDto(sellerVatRegistrationCountry = current.sellerVatRegistrationCountry),
)

/** Tab caption: status square + label (+ open count) — [refresh] keeps it live on every relevant
 *  value change. */
private class EinvoiceCaption(val square: Span, val open: Span, val component: Component)

private fun buildEinvoiceCaption(labelText: String): EinvoiceCaption {
    val square = statusSquare(true)
    val open = Span()
    val component = com.vaadin.flow.component.orderedlayout.HorizontalLayout(square, Span(labelText), open).apply {
        isPadding = false
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        addClassName("entry-tab-caption")
        addClassName("lt-gap-040")
    }
    return EinvoiceCaption(square, open, component)
}

private fun EinvoiceCaption.refresh(missing: Int, openLabel: (Int) -> String) {
    square.element.classList.set(BAU_STATUS_SQUARE_FILLED, missing == 0)
    square.element.classList.set(BAU_STATUS_SQUARE_OPEN, missing > 0)
    open.isVisible = missing > 0
    if (missing > 0) open.text = "(" + openLabel(missing) + ")"
}

internal fun referencesFormLayout(fields: EinvoiceFormFields): FormLayout = FormLayout(
    fields.buyerReferenceField, fields.purchaseOrderReferenceField, fields.orderReferenceField,
    fields.orderReferenceDateField, fields.sellerVatCountryBox,
).apply {
    setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("700px", 2))
    setColspan(fields.buyerReferenceField, 2)
    setColspan(fields.purchaseOrderReferenceField, 2)
}

/** What [EinvoiceGuidedSteps.build] / [InvoiceEinvoiceEntryTab.buildFlatEinvoiceContent] hand
 *  back: [moduleFieldSection] is captured from whichever branch actually attaches the
 *  OVERVIEW_REFERENCES module fields (guided step 01 or the flat "Referenzen" section), so
 *  [EInvoiceTabResult] can hand the live "projectNumber" widget to the notes tab's free-text
 *  chips. [refreshExtras] is guided mode's progress bar / step square recomputation on top of the
 *  caption refresh; flat mode leaves it the default no-op. */
internal class EinvoiceTabBuild(
    val content: Component,
    val revealField: (Component) -> Boolean,
    val moduleFieldSection: ModuleFieldSection,
    val refreshExtras: () -> Unit = {},
)

// Flat mode — nothing to guide: the same fields, grouped like today's sections
// instead of stepper chrome.
private fun buildFlatEinvoiceContent(ctx: EinvoiceTabContext, invoiceFormModuleRegistry: InvoiceFormModuleRegistry, tr: (String) -> String): EinvoiceTabBuild {
    val referencesSection = entrySection(tr("invoiceDetail.entry.section.references"), referencesFormLayout(ctx.fields))

    val formatForm = FormLayout().apply {
        setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep("700px", 2))
    }
    val moduleFieldSection = ModuleFieldSection(
        slot = FormSlot.OVERVIEW_REFERENCES,
        modules = invoiceFormModuleRegistry.modules(),
        ctx = FormContext(currentInvoiceDto(ctx.current), ctx.current.isReadOnly(), Locale.GERMAN),
        binder = ctx.binder,
    )
    val hasFormatField = moduleFieldSection.attachTo(formatForm)

    val b2bContent = Div(FormLayout(ctx.fields.b2bProfileBox).apply { setResponsiveSteps(FormLayout.ResponsiveStep("0", 1)) })
    if (ctx.fields.b2bHeaderSection.hasContent) b2bContent.add(ctx.fields.b2bHeaderSection)
    val b2bSection = entrySection(tr("invoiceDetail.entry.section.b2b"), b2bContent)

    val content = Div().apply {
        addClassName("entry-tab-content")
        add(referencesSection)
        if (hasFormatField) add(entrySection(tr("invoiceDetail.entry.section.format"), formatForm))
        add(b2bSection)
    }
    return EinvoiceTabBuild(content, revealField = { field -> field in ctx.tab2Fields }, moduleFieldSection = moduleFieldSection)
}

/**
 * Builds the "E-Rechnung" tab of the entry pane: guided mode (a header, a
 * progress bar, a freely-navigable step rail and a read-only PRÜFUNG step, delegated to
 * [EinvoiceGuidedSteps]) when [EinvoiceTabParameters.b2bProfile] carries an effective profile id
 * or the recipient carries a routing id, a plain flat form otherwise. The fields themselves
 * ([EinvoiceFormFields.buyerReferenceField] etc.) are created and bound by [InvoiceEntryPane] —
 * this bean only lays them out differently depending on the trigger.
 */
@Dependent
class InvoiceEinvoiceEntryTab
@Inject
constructor(
    private val invoiceFormModuleRegistry: InvoiceFormModuleRegistry,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    // The B2B header section shown on this tab is built by [InvoiceEntryHeaderFields]; it reads
    // both B2B field facades off this bean rather than [InvoiceEntryPane] holding its own copies.
    internal val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    internal val b2bFieldValueQueries: B2bFieldValueQueryPort,
) {
    internal fun build(host: Component, params: EinvoiceTabParameters): EInvoiceTabResult {
        fun tr(key: String, vararg tParams: Any) = UiTranslations.tr(host, key, *tParams)

        // Only MANDATORY fields that are also FREE-bound (i.e. the ones
        // b2bHeaderSection actually renders a widget for) count; fed from the INVOICE+SELLER+RECIPIENT
        // union so a mandatory seller/customer field counts here too.
        val mandatoryHeaderFields = mandatoryB2bHeaderFields(
            b2bFieldDefinitionQueries,
            params.b2bProfile.profileId,
            params.b2bHeaderSection.currentValues().keys,
        )
        val ctx = EinvoiceTabContext(params, mandatoryHeaderFields, tr("invoiceDetail.buyerReference"))

        // Tab caption: live on every relevant value change, wired below once the guided/flat
        // content also exists.
        val caption = buildEinvoiceCaption(einvoiceTabLabelBase(ctx.trigger, ::tr))
        fun refreshCaption() = caption.refresh(ctx.currentStatuses().count { it.missing }) { n -> tr("invoiceDetail.entry.tab.einvoice.open", n.toString()) }

        val build = if (ctx.guided) {
            EinvoiceGuidedSteps(invoiceDetailQueryPort, invoiceFormModuleRegistry, host).build(ctx)
        } else {
            buildFlatEinvoiceContent(ctx, invoiceFormModuleRegistry) { key -> tr(key) }
        }

        fun refreshLiveState() {
            refreshCaption()
            build.refreshExtras()
        }

        ctx.fields.buyerReferenceField.addValueChangeListener { refreshLiveState() }
        params.b2bHeaderSection.addValueChangeListener { refreshLiveState() }
        refreshCaption()

        val projectNumberField = build.moduleFieldSection.componentFor("projectNumber") as? TextField
        return EInvoiceTabResult(caption.component, build.content, build.revealField, projectNumberField)
    }
}
