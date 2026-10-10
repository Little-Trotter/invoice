package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.SubmitQuoteForReviewUseCase
import de.littletrotter.application.quote.port.outbound.SubmitQuoteForReviewRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `SubmitQuoteForReviewUseCase`; the call itself is the outbound `SubmitQuoteForReviewRemote`. */
@ApplicationScoped
class SubmitQuoteForReviewService @Inject constructor(
    private val remote: SubmitQuoteForReviewRemote,
) : SubmitQuoteForReviewUseCase {

    override fun submit(quoteId: String): String =
        remote.submit(quoteId)
}
