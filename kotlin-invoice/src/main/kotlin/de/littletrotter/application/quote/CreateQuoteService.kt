package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.CreateQuoteUseCase
import de.littletrotter.application.quote.port.outbound.CreateQuoteRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `CreateQuoteUseCase`; the call itself is the outbound `CreateQuoteRemote`. */
@ApplicationScoped
class CreateQuoteService @Inject constructor(
    private val remote: CreateQuoteRemote,
) : CreateQuoteUseCase {

    override fun create(customerId: String): String =
        remote.create(customerId)
}
