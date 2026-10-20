package de.littletrotter.e2e.receipts

import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.invoice.JtaReceiptStoreTransaction
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.application.invoice.port.outbound.NoopReceiptPageInspector
import de.littletrotter.application.invoice.port.outbound.NoopReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptPageInspector
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.time.TimeSource

/** Upload service on real repositories: the insert runs in a real JTA transaction, the other collaborators are no-ops. */
fun receiptUploadService(receiptRepository: ReceiptRepository, documentStore: DocumentStore, time: TimeSource): ReceiptUploadService =
    receiptUploadService(receiptRepository, documentStore, NoopInvoiceCachePort(), time)

fun receiptUploadService(
    receiptRepository: ReceiptRepository,
    documentStore: DocumentStore,
    cache: InvoiceCachePort,
    time: TimeSource,
    receiptPdfReducer: ReceiptPdfReducer = NoopReceiptPdfReducer(),
    pageInspector: ReceiptPageInspector = NoopReceiptPageInspector(),
): ReceiptUploadService =
    ReceiptUploadService(receiptRepository, documentStore, cache, time, receiptPdfReducer, pageInspector, JtaReceiptStoreTransaction())
