package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuotePage

/** Remote side of `QuoteListQueryPort`: the offer component call behind the application service. */
fun interface QuoteListRemote {

    /** The window [criteria] describes, newest first, with the size of the whole filtered set. */
    fun findPage(criteria: QuoteListCriteria): QuotePage
}
