package de.littletrotter.ui.view.invoice

import de.littletrotter.application.error.ApplicationUserException
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.domain.exception.InvoiceImmutableException
import jakarta.persistence.OptimisticLockException

/**
 * The shared click-listener guard for every receipt detach/undo write in
 * ReceiptDialogAttachedTab and InvoiceDetailViewReceiptsTab: a listener must not let an
 * exception escape into the Vaadin session, but that guard duplicated seven times as
 * `catch (ex: Exception)` is exactly what detekt's TooGenericExceptionCaught flags — and rightly
 * so, since it also hides genuine bugs behind one generic message. Catches only the exception
 * types the invoked [de.littletrotter.application.invoice.ReceiptLinkService] write actually documents
 * throwing for a detach/undo call: [ApplicationUserException] (an unknown invoice/item id, or a
 * receipt missing its size), [InvoiceImmutableException] (the invoice became read-only
 * concurrently), [OptimisticLockException] (a concurrent write elsewhere changed the version), and
 * [ReceiptCountLimitExceededException] a re-attaching undo can still hit. The size counterpart
 * (`InvoiceDocumentSizeExceededException`) no longer belongs here (PO decision) — linking/undoing a
 * receipt never refuses on size any more, only [GenerationJobCreationService] does, well outside
 * this guard's reach. Same idiom [ReceiptPoolView]'s upload handler already uses — several specific
 * `catch` clauses instead of one broad one — just shared here instead of copied seven times.
 * [onFailure] keeps each call site's own existing log-and-notify wording; only the dispatch itself
 * is shared.
 */
internal fun guardReceiptAction(onFailure: (Exception) -> Unit, action: () -> Unit) {
    try {
        action()
    } catch (ex: ApplicationUserException) {
        onFailure(ex)
    } catch (ex: InvoiceImmutableException) {
        onFailure(ex)
    } catch (ex: OptimisticLockException) {
        onFailure(ex)
    } catch (ex: ReceiptCountLimitExceededException) {
        onFailure(ex)
    }
}
