package de.littletrotter.application.invoice

import de.littletrotter.application.cache.NoopInvoiceCachePort
import de.littletrotter.application.cache.port.outbound.InvoiceCachePort
import de.littletrotter.application.invoice.port.outbound.NoopReceiptPageInspector
import de.littletrotter.application.invoice.port.outbound.NoopReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptPageInspector
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.time.TimeSource

/** Test wiring of [ReceiptUploadService]: every collaborator defaults to its no-op. */
fun receiptUploadService(
    receiptRepository: ReceiptRepository,
    documentStore: DocumentStore,
    cache: InvoiceCachePort = NoopInvoiceCachePort(),
    time: TimeSource,
    receiptPdfReducer: ReceiptPdfReducer = NoopReceiptPdfReducer(),
    pageInspector: ReceiptPageInspector = NoopReceiptPageInspector(),
    storeTransaction: ReceiptStoreTransaction = DirectReceiptStoreTransaction(),
): ReceiptUploadService =
    ReceiptUploadService(receiptRepository, documentStore, cache, time, receiptPdfReducer, pageInspector, storeTransaction)
