package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.AcceptQuoteUseCase
import de.littletrotter.application.quote.port.inbound.CancelQuoteUseCase
import de.littletrotter.application.quote.port.inbound.RejectQuoteUseCase
import de.littletrotter.application.quote.port.inbound.RequestQuoteInvoiceUseCase
import de.littletrotter.application.quote.port.inbound.ReturnQuoteToDraftUseCase
import de.littletrotter.application.quote.port.inbound.SendQuoteUseCase
import de.littletrotter.application.quote.port.inbound.SubmitQuoteForReviewUseCase
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * The life-cycle use cases the offer detail drives, bundled so the view's constructor stays within
 * the parameter limit. A plain holder: each member is still its own narrow input port.
 */
@Dependent
class QuoteLifecyclePorts
@Inject
constructor(
    val submit: SubmitQuoteForReviewUseCase,
    val returnToDraft: ReturnQuoteToDraftUseCase,
    val cancel: CancelQuoteUseCase,
    val accept: AcceptQuoteUseCase,
    val reject: RejectQuoteUseCase,
    val send: SendQuoteUseCase,
    val requestInvoice: RequestQuoteInvoiceUseCase,
)
