package de.littletrotter.application.invoice.port.inbound

/** Input port answering whether large receipts are reduced at upload without asking. */
fun interface ReceiptAutoReduceQueryPort {
    fun isAutoReduceEnabled(): Boolean
}
