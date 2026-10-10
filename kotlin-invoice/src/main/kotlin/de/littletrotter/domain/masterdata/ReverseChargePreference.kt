package de.littletrotter.domain.masterdata

/**
 * The recipient's standing default for how Reverse Charge is decided at invoice creation
 * (`InvoiceLifecycleService.create`). Ordinal order is significant: it is the
 * persisted `SMALLINT` value (`ReverseChargePreferenceConverter`) and matches the source note's
 * own numbering (0/1/2) — do not reorder.
 */
enum class ReverseChargePreference {
    /** 0 — never propose Reverse Charge for this recipient; no creation-time dialog. */
    NEVER,

    /** 1 — always propose Reverse Charge for this recipient; no creation-time dialog. */
    ALWAYS,

    /** 2 — ask at every invoice creation (today's unconditional behaviour, kept as the default). */
    ASK,
}
