package de.littletrotter.domain.invoice

/**
 * What a [TripExpense] line was for. [code] is the persisted value
 * (`trip_expense.kind`, `V1__baseline_schema.sql`'s `chk_trip_expense_kind`), read/written
 * explicitly by [de.littletrotter.domain.invoice.converters.TripExpenseKindConverter] — never the
 * enum ordinal (project-wide explicit-code convention for enums whose declaration order is not
 * guaranteed to match persistence order forever). Only ever append a new constant with a new,
 * never-before-used code.
 *
 * [labelKey] is the i18n key (`trip.kind.*`) every writer that shows this kind to a human resolves
 * against its own label bundle — mirrors [de.littletrotter.domain.invoice.InvoiceStatus.labelKey]'s
 * own convention. This originally kept this mapping private to [de.littletrotter.application.invoice
 * .InvoiceDtoMapper]; the `Positionen` sheet's own "Art" column needs the exact same mapping,
 * so it moved here — the one place both callers (and every export after them) resolve it from.
 *
 * `TAXI` (formerly code 3) is removed (greenfield, PO 2026-09-13): no production instance
 * exists yet, so dev/demo `kind = 3` rows are handled by resetting seed data, not migrating it.
 * `OTHER` takes the freed code 3, keeping the persisted range contiguous.
 */
enum class TripExpenseKind(val code: Int, val labelKey: String) {
    HOTEL(0, "trip.kind.hotel"),
    TRANSPORT(1, "trip.kind.transport"),
    MILEAGE(2, "trip.kind.mileage"),
    OTHER(3, "trip.kind.other"),
    TRAVEL_TIME(4, "trip.kind.travelTime"),
    ;

    companion object {
        fun fromCode(code: Int): TripExpenseKind =
            entries.first { it.code == code }

        fun fromCodeOrNull(code: Int): TripExpenseKind? = entries.firstOrNull { it.code == code }
    }
}
