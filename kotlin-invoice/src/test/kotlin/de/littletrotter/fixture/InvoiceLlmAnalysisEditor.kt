package de.littletrotter.fixture

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import java.time.OffsetDateTime

/**
 * Property-style access to the stored state of an [InvoiceLlmAnalysis], as a loaded row carries it.
 *
 * Status, result fields and timestamps change together through `complete`/`fail`/`disable`; a test needing one
 * of them alone hydrates it as a loaded row would carry it.
 */
class InvoiceLlmAnalysisEditor internal constructor(val entity: InvoiceLlmAnalysis) {
    var modelName: String
        get() = entity.modelName
        set(value) { entity.adoptModel(value) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var invoice: Invoice?
        get() = entity.invoice
        set(value) { PersistenceEmulation.hydrate(entity, "invoice", value) }
    var status: LlmAnalysisStatus
        get() = entity.status
        set(value) { PersistenceEmulation.hydrate(entity, "status", value) }
    var rating: Int?
        get() = entity.rating
        set(value) { PersistenceEmulation.hydrate(entity, "rating", value) }
    var summary: String?
        get() = entity.summary
        set(value) { PersistenceEmulation.hydrate(entity, "summary", value) }
    var issues: String?
        get() = entity.issues
        set(value) { PersistenceEmulation.hydrate(entity, "issues", value) }
    var suggestions: String?
        get() = entity.suggestions
        set(value) { PersistenceEmulation.hydrate(entity, "suggestions", value) }
    var improvedXml: String?
        get() = entity.improvedXml
        set(value) { PersistenceEmulation.hydrate(entity, "improvedXml", value) }
    var analyzedAt: OffsetDateTime?
        get() = entity.analyzedAt
        set(value) { PersistenceEmulation.hydrate(entity, "analyzedAt", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
}

/** Edits [InvoiceLlmAnalysis] in place through its domain operations. */
fun InvoiceLlmAnalysis.edit(block: InvoiceLlmAnalysisEditor.() -> Unit): InvoiceLlmAnalysis = also { InvoiceLlmAnalysisEditor(it).block() }

/**
 * A blank analysis row; `InvoiceLlmAnalysis.start` and the complete/fail methods cover the production paths.
 */
fun anInvoiceLlmAnalysis(block: InvoiceLlmAnalysisEditor.() -> Unit = {}): InvoiceLlmAnalysis = InvoiceLlmAnalysis().edit(block)
