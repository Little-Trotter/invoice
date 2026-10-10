package de.littletrotter.domain.shared

/**
 * The `%…%` pattern for a `LIKE :f` filter parameter, with the user's own text neutralised.
 *
 * `%` and `_` are wildcards in SQL, so a search for `50_000` used to match `50X000` and a search
 * containing `%` matched far more than the anwender asked for (IMP-4). This is not an
 * injection — the value is always bound, never concatenated — but it is a wrong answer, and a
 * filter box is exactly where people type punctuation without meaning anything by it.
 *
 * The escape character is `\`, and every `LIKE :f` that binds this pattern spells out
 * `ESCAPE '\'` rather than relying on the PostgreSQL default: the clause states in the query
 * itself what the pattern means, instead of leaving it to the dialect. Whoever adds a new
 * `LIKE :f` must add that clause with it — without it the backslashes this function inserts would
 * be matched literally, and the filter would find nothing at all.
 *
 * The backslash must be escaped **first** — otherwise the backslashes introduced for `%` and `_`
 * would be escaped again in the second pass.
 *
 * Lives in `domain.shared`, not `infrastructure.persistence` where it started: it is pure text
 * logic without a database dependency, and both `application` (`InvoiceOverviewQueryServiceImpl`)
 * and `infrastructure` (the Panache repositories) call it. `domain` is the one package
 * both may depend on without inverting the hexagonal boundary
 * (`ArchitectureRulesTest` enforces the direction).
 */
fun likePattern(filterText: String): String {
    val escaped = filterText.lowercase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$escaped%"
}

/** The one filter parameter name every `LIKE` clause binds; the value comes from [likePattern]. */
const val FILTER_PARAM = "f"

/**
 * The complete filter clause for one queryable path: lowercasing, the
 * `:f` parameter and the `ESCAPE` clause in a single named building block. Use this instead of
 * writing `LIKE :f` by hand — the helper *is* the rule that pattern and ESCAPE clause travel
 * together (the rule that was forgotten once); `LikeEscapeGuardTest` enforces it
 * for any hand-written stragglers. Works in JPQL (entity paths) and native SQL (column names)
 * alike.
 */
fun likeFilter(path: String): String = likeFilterNamed(path, FILTER_PARAM)

/** Like [likeFilter], but binding an explicit parameter name — for queries that carry more than
 *  one independently-bound LIKE filter (e.g. the Artikel view's two-field Nr. + text search). */
fun likeFilterNamed(path: String, param: String): String = "lower($path) LIKE :$param ESCAPE '\\'"

/** Whitespace-separated search tokens; empty for a blank input. */
fun filterTokens(filterText: String?): List<String> =
    filterText?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()

/** Like [likeFilter], but with a per-token parameter (`:f0`, `:f1`, …) for multi-token AND queries. */
fun likeFilterIndexed(path: String, index: Int): String = likeFilterNamed(path, "$FILTER_PARAM$index")
