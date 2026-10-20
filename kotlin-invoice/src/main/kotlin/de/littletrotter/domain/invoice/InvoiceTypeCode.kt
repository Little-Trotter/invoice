package de.littletrotter.domain.invoice

/**
 * UNTDID 1001 (BT-3) document-type codes this system emits. Persisted via
 * [de.littletrotter.domain.invoice.converters.InvoiceTypeCodeConverter], which stores the
 * **ordinal**, not [code] — per the enum-as-smallint project convention, a new entry must always
 * be appended at the end, never inserted between existing ones, or every already-persisted row
 * silently decodes to the wrong type.
 *
 * PO decision 1 (2026-09-02, cancellation type migration) — [CORRECTED_INVOICE] (`384`) is the ONLY
 * type a cancellation (full cancellation) document may carry;
 * [de.littletrotter.application.invoice.InvoiceCancellationService.createCreditNote] stamps it.
 * [CREDIT_NOTE] (`381`) stays in the list, reserved for a genuine credit note — a document this
 * system does not currently produce through any code path — and must never be used for a
 * cancellation again.
 */
enum class InvoiceTypeCode(val code: String) {
    COMMERCIAL_INVOICE("380"),
    CREDIT_NOTE("381"),
    CORRECTED_INVOICE("384");

    companion object {
        fun fromCode(code: String): InvoiceTypeCode =
            entries.first { it.code == code }
    }
}
