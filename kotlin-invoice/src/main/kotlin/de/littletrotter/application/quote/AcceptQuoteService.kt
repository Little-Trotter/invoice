package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AcceptQuoteUseCase
import de.littletrotter.application.quote.port.outbound.AcceptQuoteRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/** Application service of `AcceptQuoteUseCase`; the call itself is the outbound `AcceptQuoteRemote`. */
@ApplicationScoped
class AcceptQuoteService @Inject constructor(
    private val remote: AcceptQuoteRemote,
) : AcceptQuoteUseCase {

    override fun accept(quoteId: String, acceptedOn: LocalDate, customerOrderReference: String?) =
        remote.accept(quoteId, acceptedOn, customerOrderReference)
}
