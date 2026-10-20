package de.littletrotter.domain.reference

/**
 * Derives a data-sourced prefix (`prefixSource = FROM_DATA`) from a curated [PrefixDataField].
 * Pure text logic, DB-free, so the derivation rules can be tested without a database. If
 * the source field is empty, the prefix is omitted entirely (no `XXX` placeholder — that would
 * look like a real value).
 */
object NumberPrefixDeriver {

    /** Every derived prefix (initials/category short form) is this many characters long. */
    private const val PREFIX_LENGTH = 3

    fun fromData(field: PrefixDataField, sourceValue: String?): String? {
        if (sourceValue.isNullOrBlank()) return null
        return when (field) {
            PrefixDataField.NAME_INITIALS -> initials(sourceValue)
            PrefixDataField.COUNTRY_CODE -> sourceValue.trim().uppercase().take(2)
            PrefixDataField.CATALOGUE_CATEGORY -> categoryShort(sourceValue.trim())
        }
    }

    /** First three ASCII-normalized uppercase letters of the name. Umlauts are expanded to
     *  digraphs (`ü → ue`), so "Müller" yields `MUE`. */
    private fun initials(name: String): String {
        val expanded = name
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "AE").replace("Ö", "OE").replace("Ü", "UE")
            .replace("ß", "ss")
        val letters = expanded.uppercase().filter { it in 'A'..'Z' }
        return letters.take(PREFIX_LENGTH)
    }

    /** Short form of a category name (`InvoiceItemCategory` enum name). Deleting the
     *  TRAVEL_EXPENSE/EXPENSE/PER_DIEM categories — a business trip is now TRIP — meant their
     *  "TRV"/"EXP"/"PDM" branches were removed with them (no live code path ever produces those
     *  strings any more; `InvoiceItemCategory.entries` has only SERVICE/PRODUCT/TRIP). */
    private fun categoryShort(category: String): String = when (category) {
        "SERVICE" -> "SVC"
        "PRODUCT" -> "PRO"
        "UNIT_PRICE" -> "UPE"
        else -> category.take(PREFIX_LENGTH).uppercase()
    }
}
