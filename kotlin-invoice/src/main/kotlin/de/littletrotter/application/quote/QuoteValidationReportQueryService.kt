package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.application.quote.port.inbound.QuoteValidationReportQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteValidationReportRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteValidationReportQueryPort`; the call itself is the outbound `QuoteValidationReportRemote`. */
@ApplicationScoped
class QuoteValidationReportQueryService @Inject constructor(
    private val remote: QuoteValidationReportRemote,
) : QuoteValidationReportQueryPort {

    override fun report(quoteId: String, documentId: String): QuoteValidationReport? =
        remote.report(quoteId, documentId)
}
