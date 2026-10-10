package de.littletrotter.shared.catalogue

/**
 * The four kinds of invoice position. [code] is the persisted value
 * (`invoice_item.category`/`article.category`, `V1__baseline_schema.sql`'s
 * `chk_invoice_item_category`/`chk_article_category`), read/written explicitly by
 * [de.littletrotter.domain.shared.converters.InvoiceItemCategoryConverter] — never the enum
 * ordinal, unlike most other enum-as-SMALLINT columns in this codebase (that file's own
 * file-level comment on enum-CHECK styles). TRAVEL_EXPENSE (`2`), EXPENSE (`3`) and
 * PER_DIEM (`4`) existed as independent, freestanding position categories and were
 * deleted (greenfield): a business trip is now a single [TRIP] position that groups its
 * own expense lines ([de.littletrotter.domain.invoice.TripExpense]) and per-diem days
 * ([de.littletrotter.domain.reference.TravelExpenseEntry]) — never its own top-level category
 * again. `TRIP`'s code (`5`) deliberately continues after the three deleted codes rather than
 * reusing `2`: a future reader of an old backup/export must never misread a `2`-`4` row as `TRIP`.
 *
 * Only ever append a new constant with a new, never-before-used code — reordering or reusing a
 * code corrupts every already-persisted row.
 */
enum class InvoiceItemCategory(val code: Int) {
    SERVICE(0),
    PRODUCT(1),
    TRIP(5),
    /** Catalogue work priced by area, length, volume or a lump sum. */
    UNIT_PRICE(6),
    ;

    companion object {
        fun fromCode(code: Int): InvoiceItemCategory =
            entries.first { it.code == code }

        fun fromCodeOrNull(code: Int): InvoiceItemCategory? = entries.firstOrNull { it.code == code }
    }
}
