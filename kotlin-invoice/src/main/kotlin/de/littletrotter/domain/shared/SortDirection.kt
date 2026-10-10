package de.littletrotter.domain.shared

/**
 * Sort direction as a type instead of the `"ASC"`/`"DESC"` strings that used
 * to travel through repository signatures and get normalised by string comparison in
 * `BasePanacheRepository.resolveSortExpr`. Lives in `domain.shared`, not `application.dto` where
 * it was first introduced: repository ports in `domain.repository` need it in their signatures,
 * and domain must not depend on the application layer
 * (`ArchitectureRulesTest` enforces the direction).
 * `application.dto.SortDirection` is a `typealias` onto this enum so existing imports keep
 * compiling.
 */
enum class SortDirection {
    ASC,
    DESC,
    ;

    companion object {
        /** Exactly today's [BasePanacheRepository][de.littletrotter.infrastructure.persistence.BasePanacheRepository]
         *  semantics: `"desc"` (case-insensitive) is [DESC], everything else — including `null` —
         *  is [ASC]. The one place the Vaadin↔enum boundary converts a raw string. */
        fun fromString(raw: String?): SortDirection = if (raw.equals("desc", ignoreCase = true)) DESC else ASC
    }
}

/**
 * A generic grid column name paired with its [SortDirection] — the admin-grid list queries
 * ([de.littletrotter.domain.repository.ReceiptRepository.findAllSorted]/
 * [de.littletrotter.domain.repository.SenderProfileRepository.findAllSorted] and their
 * application/infrastructure counterparts) grouped so `findAllSorted` takes one parameter for it
 * instead of two (LongParameterList). Unlike [de.littletrotter.application.invoice
 * .InvoiceOverviewSort], [field] is a raw grid column key, not a typed sort-field enum — these
 * two repositories still resolve/allowlist it internally.
 */
data class GridSort(
    val field: String,
    val dir: SortDirection,
)
