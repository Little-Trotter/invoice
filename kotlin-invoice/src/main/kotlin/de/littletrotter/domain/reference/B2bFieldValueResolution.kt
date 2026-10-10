package de.littletrotter.domain.reference

/**
 * fix-einvoice-guided-profile-party-values — which tier of
 * [de.littletrotter.application.reference.B2bFieldValueService]'s resolution chain a
 * [ResolvedB2bFieldValue.value] came from: an invoice-level override, the field's party (seller or
 * recipient) master data, or the profile field's own [B2bProfileField.defaultValue]. The guided
 * entry-pane step renders [PARTY] with a helper hint ("carried over from master data, not yet an
 * invoice-level override"); [DEFAULT] gets no such hint — a profile default is not master data
 * about a specific party.
 */
enum class B2bFieldValueOrigin { INVOICE, PARTY, DEFAULT }

/**
 * fix-einvoice-guided-profile-party-values — one [B2bProfileField]'s resolved value for one
 * invoice: [value] is the effective value (invoice value wins over the party's own stored value,
 * which wins over the field's own default), [origin] says which tier produced it, and
 * [inheritedValue] is what [value] would be *without* any invoice-level override (party value, or
 * the field's default) — the write-back rule compares a widget's live value against this to decide
 * whether an edit is a genuine override or just an untouched inherited value (see
 * [de.littletrotter.application.reference.B2bFieldValueService.resolvedGuidedFieldValues]).
 */
data class ResolvedB2bFieldValue(
    val value: String?,
    val origin: B2bFieldValueOrigin,
    val inheritedValue: String?,
)
