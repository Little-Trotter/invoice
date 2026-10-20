package de.littletrotter.infrastructure.quote

import de.littletrotter.shared.quote.QuoteApiPaths
import java.net.URLEncoder

/** Builds concrete offer URLs from the contract's path templates, encoding every identifier as one path segment. */
internal object QuoteRoutes {

    /** `/api/quotes/{id}` for [quoteId]. */
    fun byId(quoteId: String): String = of(QuoteApiPaths.BY_ID, quoteId)

    /** `/api/quotes/by-number/{number}` for [number]. */
    fun byNumber(number: String): String = QuoteApiPaths.QUOTES + QuoteApiPaths.BY_NUMBER.replace("{number}", segment(number))

    /** The offers root plus [template] with `{id}` and the child id (`{aid}` attachment, `{did}` document) filled in. */
    fun of(template: String, quoteId: String, childId: String? = null): String =
        QuoteApiPaths.QUOTES + template
            .replace("{id}", segment(quoteId))
            .replace("{aid}", segment(childId.orEmpty()))
            .replace("{did}", segment(childId.orEmpty()))

    /** The pool root [QuoteApiPaths.ATTACHMENT_LIBRARY] plus [template] with the entry id and, for a version, its id filled in. */
    fun library(template: String = "", entryId: String? = null, versionId: String? = null): String =
        QuoteApiPaths.ATTACHMENT_LIBRARY + template
            .replace("{id}", segment(entryId.orEmpty()))
            .replace("{vid}", segment(versionId.orEmpty()))

    private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
