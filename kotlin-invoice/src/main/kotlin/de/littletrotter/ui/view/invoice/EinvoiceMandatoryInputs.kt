package de.littletrotter.ui.view.invoice

import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermLevel

// Which mandatory e-invoice inputs exist for the invoice being entered and which of them are
// still blank — the pure rule behind the E-Rechnung tab's caption count, the guided progress bar,
// the step squares, the PRÜFUNG step and the save flow's header-issue check. Free of Vaadin, so
// every one of those places reads the same answer.

/** Stable id for BT-10 in the mandatory-input lists below. BT-10 is a reference term, never a
 *  catalog-eligible profile field, so its code can't collide with a [B2bProfileFieldDto] term and
 *  callers tell "the buyer reference" apart from a profile field without a second data type. */
internal val BUYER_REFERENCE_INPUT_ID = B2bTerm.BT_10.officialCode

/** Which trigger (if any) puts the E-Rechnung tab into guided mode, resolved once at entry-pane
 *  build time. Either trigger alone is enough; the profile trigger is checked first only because
 *  it's the richer of the two (it also unlocks the PROFILFELDER step). */
internal enum class EInvoiceGuidedTrigger { PROFILE, ROUTING_ID, NONE }

internal fun einvoiceGuidedModeTrigger(effectiveB2bProfileId: Long?, recipientRoutingId: String?): EInvoiceGuidedTrigger =
    when {
        effectiveB2bProfileId != null -> EInvoiceGuidedTrigger.PROFILE
        !recipientRoutingId.isNullOrBlank() -> EInvoiceGuidedTrigger.ROUTING_ID
        else -> EInvoiceGuidedTrigger.NONE
    }

/** The guided-mode step rail's content, in display order. Flat mode (trigger ==
 *  [EInvoiceGuidedTrigger.NONE]) has no steps at all — it's a plain form, not a wizard. */
internal enum class EInvoiceStep { REFERENCES, PROFILE_FIELDS, REVIEW }

internal fun einvoiceGuidedSteps(trigger: EInvoiceGuidedTrigger): List<EInvoiceStep> = when (trigger) {
    EInvoiceGuidedTrigger.NONE -> emptyList()
    // Routing-id-only trigger has no effective profile, so the PROFILFELDER step — which is
    // exactly "b2bProfileBox + b2bHeaderSection" — is omitted entirely, not just emptied.
    EInvoiceGuidedTrigger.ROUTING_ID -> listOf(EInvoiceStep.REFERENCES, EInvoiceStep.REVIEW)
    EInvoiceGuidedTrigger.PROFILE -> listOf(EInvoiceStep.REFERENCES, EInvoiceStep.PROFILE_FIELDS, EInvoiceStep.REVIEW)
}

/** One mandatory FREE header field of the effective B2B profile — id/label only, independent of
 *  Vaadin so [missingEInvoiceInputs] stays a plain-value pure function. */
internal data class MandatoryB2bHeaderField(val btCode: String, val label: String)

/**
 * The union of every `FREE` field the guided PROFILFELDER step (and the flat-mode B2B section, which shares the same `b2bHeaderSection`
 * instance) renders: [profileId]'s `INVOICE`-placed header fields, its `SELLER`-placed
 * `HEADER`/`SELLER_PARTY`-level fields (the only levels a `SELLER`-placed field can ever carry),
 * and its `RECIPIENT`-placed fields. `null` profile = nothing to render, same "no section" contract
 * every `find*Fields` field-definition read already has. Single source for [mandatoryB2bHeaderFields] below
 * and for [InvoiceEntryPane]'s own `b2bHeaderSection` group construction, so a mandatory
 * seller/customer field counts in both places identically.
 */
internal fun b2bGuidedProfileFields(b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort, profileId: Long?): List<B2bProfileFieldDto> {
    if (profileId == null) return emptyList()
    return b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(profileId) +
        b2bFieldDefinitionQueries.findB2bSellerFields(profileId).filter { field ->
            field.term?.level == B2bTermLevel.HEADER || field.term?.level == B2bTermLevel.SELLER_PARTY
        } +
        b2bFieldDefinitionQueries.findB2bRecipientFields(profileId)
}

/**
 * The single [MandatoryB2bHeaderField] computation, fed from [b2bGuidedProfileFields]'s union so a
 * mandatory seller- or customer-placed field counts here too. [renderedFieldCodes] is `b2bHeaderSection.currentValues().keys`: the section itself
 * already drops non-`FREE` terms, so this is exactly "the terms actually rendered", matching what
 * the caller can fill in.
 */
internal fun mandatoryB2bHeaderFields(
    b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    profileId: Long?,
    renderedFieldCodes: Set<String>,
): List<MandatoryB2bHeaderField> =
    b2bGuidedProfileFields(b2bFieldDefinitionQueries, profileId)
        .filter { it.requirement == B2bFieldRequirement.MANDATORY }
        .mapNotNull { field ->
            val code = field.term?.officialCode ?: return@mapNotNull null
            if (code !in renderedFieldCodes) return@mapNotNull null
            MandatoryB2bHeaderField(code, field.businessDefinition)
        }

/** The identifiers of the mandatory e-invoice inputs that are currently blank. [mandatoryB2bHeaderFields] must already be filtered to
 *  MANDATORY + FREE-bound fields (the caller does this by keeping only btCodes present in
 *  [de.littletrotter.ui.component.B2bProfileFieldsSection.currentValues] — the section itself
 *  only ever renders FREE terms, so that map's keys are exactly "FREE fields this profile
 *  renders", matching what [B2bProfileFieldsSection] shows without this function needing its own
 *  catalog lookup). */
internal fun missingEInvoiceInputs(
    guidedModeTriggered: Boolean,
    buyerReference: String?,
    mandatoryB2bHeaderFields: List<MandatoryB2bHeaderField>,
    b2bHeaderFieldValues: Map<String, String?>,
): List<String> {
    val missing = mutableListOf<String>()
    if (guidedModeTriggered && buyerReference.isNullOrBlank()) missing += BUYER_REFERENCE_INPUT_ID
    mandatoryB2bHeaderFields.forEach { field ->
        if (b2bHeaderFieldValues[field.btCode].isNullOrBlank()) missing += field.btCode
    }
    return missing
}

/** One mandatory e-invoice input with its current fill state and display label — what the tab
 *  caption count, the progress bar, the step squares and the PRÜFUNG step all render from.
 *  Delegates the actual "missing" decision to [missingEInvoiceInputs] so none of those four
 *  places can drift apart on what counts as missing. */
internal data class EInvoiceInputStatus(val id: String, val label: String, val missing: Boolean)

/** LongParameterList — the single query every [einvoiceInputStatuses] caller (the save-flow
 *  header-issue check, [InvoiceEntryPane]'s guided-hint controller, and [EinvoiceTabContext]
 *  itself) assembles: which mandatory e-invoice inputs exist right now and what the user has
 *  currently typed into them. */
internal data class EInvoiceInputStatusQuery(
    val guidedModeTriggered: Boolean,
    val buyerReferenceLabel: String,
    val buyerReference: String?,
    val mandatoryB2bHeaderFields: List<MandatoryB2bHeaderField>,
    val b2bHeaderFieldValues: Map<String, String?>,
)

internal fun einvoiceInputStatuses(query: EInvoiceInputStatusQuery): List<EInvoiceInputStatus> {
    val missingIds = missingEInvoiceInputs(
        query.guidedModeTriggered,
        query.buyerReference,
        query.mandatoryB2bHeaderFields,
        query.b2bHeaderFieldValues,
    ).toSet()
    val statuses = mutableListOf<EInvoiceInputStatus>()
    if (query.guidedModeTriggered) {
        statuses += EInvoiceInputStatus(BUYER_REFERENCE_INPUT_ID, query.buyerReferenceLabel, BUYER_REFERENCE_INPUT_ID in missingIds)
    }
    query.mandatoryB2bHeaderFields.forEach { field ->
        statuses += EInvoiceInputStatus(field.btCode, field.label, field.btCode in missingIds)
    }
    return statuses
}
