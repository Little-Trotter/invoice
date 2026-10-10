package de.littletrotter.infrastructure.export

import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.quote.port.outbound.QuoteListRemote

/** The offer component's largest accepted window. */
private const val PAGE_SIZE = 500

/**
 * Every offer [criteria] matches, read page by page with the same call the list itself uses.
 * The pages are walked lazily; an empty page ends the walk even if the reported total was higher.
 */
internal fun QuoteListRemote.allRows(criteria: QuoteListCriteria): Sequence<QuoteSummary> = sequence {
    var offset = 0
    do {
        val page = findPage(criteria.copy(offset = offset, limit = PAGE_SIZE))
        yieldAll(page.rows)
        offset += page.rows.size
    } while (page.rows.isNotEmpty() && offset < page.total)
}
