package de.littletrotter.application.dto

/** Moved to [de.littletrotter.domain.shared.SortDirection] so domain-level
 *  repository ports can use it too (domain must not depend on `application.dto`); kept as a
 *  typealias here so existing `application.dto.SortDirection` imports keep compiling. */
typealias SortDirection = de.littletrotter.domain.shared.SortDirection

enum class InvoiceOverviewSortField {
    INVOICE_DATE,
    INVOICE_NUMBER,
    SELLER_NAME,
    RECIPIENT_NAME,
    GROSS_AMOUNT,
    STATUS,
    ITEMS_COUNT,
    VALIDATION_STATUS,
}

enum class RecipientSortField {
    CUSTOMER_NUMBER,
    NAME,
    CITY,
    CONTACT_PERSON,
}

enum class ArticleSortField {
    NAME,
    NUMBER,
    CATEGORY,
    UNIT,
}

/**
 * fix-grid-sorting, PO narrowing 14.09.2026 — Referenzdaten grids offer exactly one sortable
 * column (the tab's name/identifying field); RATE_PER_KM/VALID_FROM were removed together
 * with the columns that used to expose them. [COUNTRY] stays as the fallback default order
 * (`MileageRatesTab`'s grid has no visible country column any more — see [VEHICLE_TYPE]'s KDoc).
 */
enum class MileageRateSortField {
    COUNTRY,

    /** PO correction 14.09.2026 — `MileageRatesTab`'s first (and only sortable) column is
     *  "Fahrzeugtyp", not country: the vehicle type is persisted as a smallint ordinal
     *  ([de.littletrotter.domain.reference.converters.VehicleTypeConverter], project rule: enums
     *  are never persisted as varchar), so sorting by this field orders by that ordinal — which
     *  happens to match the rendered label order (CAR, then OTHER_MOTOR_VEHICLE), with legacy
     *  rows (`vehicleType IS NULL`, pre-R2 country-only rows) sorting last on ASC (Postgres
     *  default `NULLS LAST`). Do not "fix" this into a label-based sort — the ordinal IS the
     *  label order by construction. */
    VEHICLE_TYPE,
}

/** fix-grid-sorting, PO narrowing 14.09.2026 — Referenzdaten grids offer exactly one sortable
 *  column; DUE_IN_DAYS was removed together with the column that used to expose it. */
enum class PaymentTermSortField {
    NAME,
}

/** fix-grid-sorting, PO narrowing 14.09.2026 — the Hotelsätze tab's one sortable column. */
enum class HotelRateSortField {
    COUNTRY,
}

/** fix-grid-sorting, PO narrowing 14.09.2026 — the Verpflegungspauschalen tab's one sortable
 *  column. */
enum class PerDiemRateSortField {
    COUNTRY,
}

/** fix-grid-sorting, PO narrowing 14.09.2026 — the Steuersätze tab's one sortable column; the
 *  unsorted default stays `country, rate` (unchanged), so this field is null-able at the call
 *  site rather than carrying a second "default" member. */
enum class TaxRateSortField {
    NAME,
}
