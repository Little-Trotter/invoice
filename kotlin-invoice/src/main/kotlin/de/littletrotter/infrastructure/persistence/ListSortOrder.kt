package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.shared.SortDirection

/** Only trusted property expressions enter ORDER BY; id makes page boundaries deterministic. */
internal object ListSortOrder {
    private const val BILLING_CITY = "(SELECT a.city FROM Address a WHERE a.senderProfile = s AND a.addressType = :sortBillingType AND NOT EXISTS (SELECT other FROM Address other WHERE other.senderProfile = s AND other.addressType = a.addressType AND ((other.isDefault = true AND a.isDefault = false) OR (other.isDefault = a.isDefault AND other.id < a.id))))"

    fun senders(field: String, direction: SortDirection): String {
        val expression = when (field) {
            "name" -> "LOWER(s.name)"
            "cityCountry" -> "LOWER($BILLING_CITY)"
            "invoicePrefix" -> "LOWER(s.invoicePrefix)"
            else -> return "ORDER BY s.isDefault DESC, s.name ASC, s.id ASC"
        }
        return "ORDER BY $expression ${direction.name} NULLS LAST, s.id ASC"
    }

    fun receipts(field: String, direction: SortDirection): String {
        val expression = when (field) {
            "fileName" -> "LOWER(r.fileName)"
            "description" -> "LOWER(r.description)"
            "documentDate" -> "r.documentDate"
            "uploadedAt" -> "r.uploadedAt"
            "sizeBytes" -> "r.sizeBytes"
            else -> return "ORDER BY r.documentDate DESC, r.uploadedAt DESC, r.id DESC"
        }
        return "ORDER BY $expression ${direction.name} NULLS LAST, r.id ASC"
    }
}
