package de.littletrotter.shared.tax

/**
 * R4 — UNTDID 5305 / EN 16931 duty-and-tax-category codes as a typed model over
 * plain `String?` category columns, so the export layer always sees the literal external code,
 * never a derived value (external codes are mapped explicitly rather than derived from the enum
 * ordinal — this enum is therefore explicitly NOT persisted via an ordinal converter, unlike most
 * other project enums; [code] is the single source of truth both ways).
 *
 * Monkey finding — the V1 CHECK constraint (`chk_tax_rate_category_values`) used to only accept
 * `S`/`AE`/`E`/`O`, silently rejecting a `tax_rate` row for any of the `Z`/`G`/`K` values already
 * modeled here; the baseline was widened in place to the full enum, so all seven values are now
 * persistable.
 */
enum class VatCategory(val code: String) {
    STANDARD("S"),
    ZERO_RATED("Z"),
    EXEMPT("E"),
    REVERSE_CHARGE("AE"),
    OUTSIDE_SCOPE("O"),
    FREE_EXPORT_ITEM("G"),
    INTRA_COMMUNITY_SUPPLY("K");

    companion object {
        fun fromCodeOrNull(code: String?): VatCategory? = code?.let { c -> entries.firstOrNull { it.code == c } }
    }
}
