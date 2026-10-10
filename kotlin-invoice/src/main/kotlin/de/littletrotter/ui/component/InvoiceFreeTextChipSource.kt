package de.littletrotter.ui.component

import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.ui.components.freetext.ChipCandidate
import de.littletrotter.ui.components.freetext.ChipSource
import de.littletrotter.ui.components.freetext.FreeTextChips
import de.littletrotter.ui.format.DateFormatter
import java.time.LocalDate

/**
 * One currently-filled non-standard invoice value: the invoice header's own service period /
 * project number, or a `FREE`/`HEADER` B2B profile term value ([b2bTermCode] non-null for the
 * latter — the entry pane's save wiring also marks that term "already imported" then, so the
 * automatic silent merge (de.littletrotter.domain.invoice.B2bHeaderFreeTextMerge) never appends
 * the same value again). [label] is a short (possibly abbreviated) caption shown in front of the
 * value on the chip only, [fullName] the plain full name for the tooltip and screen readers; a
 * click appends just [value] to the free text.
 */
data class FreeTextChipCandidate(
    val label: String,
    val fullName: String,
    val value: String,
    val b2bTermCode: String? = null,
)

/** One `FREE`/`HEADER` catalog term as the chip row knows it: official [code], short [label], [fullName]. */
data class FreeTextChipTerm(val code: String, val label: String, val fullName: String)

/** The invoice header's own service-period input, as the chip row needs it — bundled so
 *  [freeTextChipCandidates] takes one parameter for it instead of three (LongParameterList). */
internal data class ServicePeriodChipInput(
    val label: String,
    val from: LocalDate?,
    val to: LocalDate?,
)

/** The invoice header's own project-number input, bundled for the same reason as
 *  [ServicePeriodChipInput]. */
internal data class ProjectNumberChipInput(
    val label: String,
    val value: String?,
)

/**
 * Pure, generic derivation of the currently-filled candidates: never a hardcoded field list —
 * [b2bTermValues] is whatever `FREE`/`HEADER` term (label, value) pairs the caller resolved from
 * the invoice's B2B profile (reusing the exact same filter
 * de.littletrotter.domain.invoice.B2bHeaderFreeTextMerge's caller already applies), so a future
 * profile field gets a chip with no change here. Only a non-blank value becomes a candidate.
 */
internal fun freeTextChipCandidates(
    servicePeriod: ServicePeriodChipInput,
    projectNumber: ProjectNumberChipInput,
    b2bTermValues: List<Pair<FreeTextChipTerm, String?>>,
): List<FreeTextChipCandidate> {
    val candidates = mutableListOf<FreeTextChipCandidate>()
    if (servicePeriod.from != null || servicePeriod.to != null) {
        val range = when {
            servicePeriod.from != null && servicePeriod.to != null ->
                "${DateFormatter.format(servicePeriod.from)} – ${DateFormatter.format(servicePeriod.to)}"
            servicePeriod.from != null -> DateFormatter.format(servicePeriod.from)
            else -> DateFormatter.format(servicePeriod.to)
        }
        candidates += FreeTextChipCandidate(servicePeriod.label, servicePeriod.label, range)
    }
    if (!projectNumber.value.isNullOrBlank()) {
        candidates += FreeTextChipCandidate(projectNumber.label, projectNumber.label, projectNumber.value.trim())
    }
    b2bTermValues.forEach { (term, value) ->
        if (!value.isNullOrBlank()) {
            candidates += FreeTextChipCandidate(term.label.trim(), term.fullName.trim(), value.trim(), b2bTermCode = term.code)
        }
    }
    return candidates
}

/**
 * i18n key of the short chip label of a catalog [term] (`BT-18` -> `...chips.term.18`). Every
 * `FREE`/`HEADER` catalog term needs one — pinned by a completeness test, so there is no
 * fallback to the profile field's long definition text.
 */
internal fun freeTextChipTermLabelKey(term: B2bTerm): String = "invoiceDetail.freeText.chips.term.${term.code}"

/**
 * i18n key of the full plain name of a catalog [term] (tooltip and screen reader text; the chip
 * itself shows the short label of [freeTextChipTermLabelKey]).
 */
internal fun freeTextChipTermNameKey(term: B2bTerm): String = "invoiceDetail.freeText.chips.termName.${term.code}"

/**
 * The [FreeTextChipTerm] (code, short label, full name) of every `FREE`/`HEADER` B2B term the profile carries, assembled from the three
 * field lists the entry pane's `b2bHeaderSection` already fetched — never a second port round
 * trip, never a hardcoded field list, so a future catalog field gets a chip automatically.
 * [sellerHeaderFields] alone also carries `SELLER_PARTY`-level (and possibly `BOUND`) fields, so it
 * needs the extra filter [invoiceHeaderFields]/[recipientHeaderFields]'s own port methods already
 * apply (see `B2bFieldDefinitionQueryPort.findB2bInvoiceHeaderFields`/`findB2bRecipientFields`'s KDoc).
 */
internal fun b2bFreeHeaderChipTerms(
    invoiceHeaderFields: List<B2bProfileFieldDto>,
    sellerHeaderFields: List<B2bProfileFieldDto>,
    recipientHeaderFields: List<B2bProfileFieldDto>,
    tr: (String) -> String,
): List<FreeTextChipTerm> = (
    invoiceHeaderFields +
        // A single null-check, not two: `term.binding == FREE` already proves `term` non-null, so
        // re-testing `it.term?.level` afterwards left a permanently-unreachable null branch.
        sellerHeaderFields.filter { field -> field.term?.let { it.binding == B2bTermBinding.FREE && it.level == B2bTermLevel.HEADER } == true } +
        recipientHeaderFields
    ).mapNotNull { field ->
        field.term?.let { term ->
            FreeTextChipTerm(term.officialCode, tr(freeTextChipTermLabelKey(term)), tr(freeTextChipTermNameKey(term)))
        }
    }

/**
 * The live widgets/data [wireFreeTextChips] reads on every refresh — grouped into one holder so
 * that function stays under detekt's parameter-count gate (LongParameterList).
 */
data class InvoiceChipFieldSources(
    val serviceFromField: DatePicker,
    val serviceToField: DatePicker,
    val projectNumberField: TextField?,
    val b2bHeaderSection: B2bProfileFieldsSection,
    val b2bChipTerms: List<FreeTextChipTerm>,
)

/**
 * Mutable indirection between [FreeTextChips] (built early, in `InvoiceEntryHeaderFields
 * .buildFreeTextFields`, before the header's other live widgets exist) and the real
 * [InvoiceFreeTextChipSource] ([wireFreeTextChips] assigns [delegate] once those widgets do, then
 * calls [FreeTextChips.refresh]) — [FreeTextChips] itself needs a [ChipSource] at construction
 * time, and this is the one thing that can be handed to it immediately.
 */
internal class ReplaceableChipSource {
    var delegate: ChipSource = ChipSource { emptyList() }

    /** The stable source handed to the chips; it reads whichever delegate is current on every call. */
    val source: ChipSource = ChipSource { delegate.candidates() }
}

/**
 * Adapts the invoice's own [freeTextChipCandidates] derivation to the generic [ChipSource] the
 * free-text editor module understands: a candidate's [FreeTextChipCandidate.b2bTermCode] becomes
 * the opaque [ChipCandidate.key] a click handler recognises again (see [FreeTextChipCandidate]'s
 * own KDoc); [group] tells a B2B term chip apart from a plain header-field chip without either
 * this component or the module needing to know why that distinction might ever matter.
 */
internal class InvoiceFreeTextChipSource(
    private val sources: InvoiceChipFieldSources,
    private val tr: (String) -> String,
) : ChipSource {
    override fun candidates(): List<ChipCandidate> {
        val b2bPairs = sources.b2bChipTerms.map { term -> term to sources.b2bHeaderSection.displayedValues()[term.code] }
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput(
                label = tr("invoiceDetail.freeText.chips.servicePeriod"),
                from = sources.serviceFromField.value,
                to = sources.serviceToField.value,
            ),
            projectNumber = ProjectNumberChipInput(
                label = tr("invoiceModule.projectNumber"),
                value = sources.projectNumberField?.value,
            ),
            b2bTermValues = b2bPairs,
        )
        return candidates.map { candidate ->
            ChipCandidate(
                label = candidate.label,
                fullName = candidate.fullName,
                insertText = candidate.value,
                group = if (candidate.b2bTermCode != null) "b2bTerm" else "field",
                key = candidate.b2bTermCode,
            )
        }
    }
}

/**
 * Wires [chips]/[chipSource] to the header's live widgets: every relevant change — [sources]'
 * serviceFromField/serviceToField/projectNumberField and b2bHeaderSection (the FREE/HEADER term
 * widgets, read through its own live [B2bProfileFieldsSection.displayedValues]) — refreshes the
 * row, and once immediately so it already reflects whatever the invoice opened with.
 */
internal fun wireFreeTextChips(
    chips: FreeTextChips,
    chipSource: ReplaceableChipSource,
    tr: (String) -> String,
    sources: InvoiceChipFieldSources,
) {
    chipSource.delegate = InvoiceFreeTextChipSource(sources, tr)
    sources.serviceFromField.addValueChangeListener { chips.refresh() }
    sources.serviceToField.addValueChangeListener { chips.refresh() }
    sources.projectNumberField?.addValueChangeListener { chips.refresh() }
    sources.b2bHeaderSection.addValueChangeListener { chips.refresh() }
    chips.refresh()
}
