package de.littletrotter.domain.invoice

import java.time.OffsetDateTime

/** The row stored under [objectKey]; the most recently generated one if a key was ever reused. */
fun Invoice.documentByObjectKey(objectKey: String): InvoiceDocument? =
    documentList.filter { it.objectKey == objectKey }.maxByOrNull { it.generatedAt ?: OffsetDateTime.MIN }

/**
 * Registers a stored object of this invoice, or returns the row already registered under
 * [objectKey] so a redelivered result updates it instead of adding a duplicate. Generated
 * documents arrive after issuance, so this is not subject to the editability guard.
 */
fun Invoice.attachDocument(documentType: String, objectKey: String, mimeType: String): InvoiceDocument =
    documentList.firstOrNull { it.objectKey == objectKey }
        ?: InvoiceDocument.create(this, documentType, objectKey, mimeType).also { documentRows.add(it) }

/**
 * Queues an analysis run for [model]: a record of that model is put back in the queue, otherwise a
 * new PENDING one starts. Runs after the invoice was finalized, so it is not subject to the
 * editability guard.
 */
fun Invoice.recordLlmAnalysis(model: String): InvoiceLlmAnalysis {
    val existing = llmAnalysisList.firstOrNull { it.modelName == model }
    if (existing != null) {
        existing.requeue()
        return existing
    }
    return InvoiceLlmAnalysis.start(this, model).also { llmAnalysisRows.add(it) }
}
