package de.littletrotter.application.invoice

/** Runs the block directly: unit tests with in-memory repositories have no transaction manager. */
class DirectReceiptStoreTransaction : ReceiptStoreTransaction {
    override fun <T> inNewTransaction(block: () -> T): T = block()
}
