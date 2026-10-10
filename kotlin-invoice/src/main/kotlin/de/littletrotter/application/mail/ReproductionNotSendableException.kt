package de.littletrotter.application.mail

/**
 * The invoice's document of record is a reproduction of a lost original, which must never reach a
 * customer: the recipient already holds the original, and a re-rendered copy could differ from it.
 * The UI maps this type to its own localized text.
 */
class ReproductionNotSendableException(val invoiceNumber: String) :
    MailDispatchException("Invoice $invoiceNumber: the document is a reproduction and is not sendable ($CODE)") {
    val code: String = CODE

    companion object {
        const val CODE = "DOCUMENT_REPRODUCTION_NOT_SENDABLE"
    }
}
