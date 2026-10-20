package de.littletrotter.fixture

import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.shared.ValidationStatus
import java.time.OffsetDateTime

/**
 * Property-style access to the stored state of an [InvoiceDocument], as a loaded row carries it.
 *
 * The stored-object fields, checksum, status and reproduction tags change together through
 * `create`/`recordGenerated`/`markReproduced`; a test needing one of them alone hydrates it as a loaded row
 * would carry it.
 */
class InvoiceDocumentEditor internal constructor(val entity: InvoiceDocument) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var shortName: String?
        get() = entity.shortName
        set(value) { PersistenceEmulation.hydrate(entity, "shortName", value) }
    var invoice: Invoice?
        get() = entity.invoice
        set(value) { PersistenceEmulation.hydrate(entity, "invoice", value) }
    var documentType: String
        get() = entity.documentType
        set(value) { PersistenceEmulation.hydrate(entity, "documentType", value) }
    var objectKey: String
        get() = entity.objectKey
        set(value) { PersistenceEmulation.hydrate(entity, "objectKey", value) }
    var fileName: String
        get() = entity.fileName
        set(value) { PersistenceEmulation.hydrate(entity, "fileName", value) }
    var mimeType: String
        get() = entity.mimeType
        set(value) { PersistenceEmulation.hydrate(entity, "mimeType", value) }
    var sizeBytes: Long?
        get() = entity.sizeBytes
        set(value) { PersistenceEmulation.hydrate(entity, "sizeBytes", value) }
    var sha256Checksum: String?
        get() = entity.sha256Checksum
        set(value) { PersistenceEmulation.hydrate(entity, "sha256Checksum", value) }
    var generatedAt: OffsetDateTime?
        get() = entity.generatedAt
        set(value) { PersistenceEmulation.hydrate(entity, "generatedAt", value) }
    var generatedBy: String?
        get() = entity.generatedBy
        set(value) { PersistenceEmulation.hydrate(entity, "generatedBy", value) }
    var generationStatus: DocumentGenerationStatus
        get() = entity.generationStatus
        set(value) { PersistenceEmulation.hydrate(entity, "generationStatus", value) }
    var validationStatus: ValidationStatus
        get() = entity.validationStatus
        set(value) { PersistenceEmulation.hydrate(entity, "validationStatus", value) }
    var validationMessages: String?
        get() = entity.validationMessages
        set(value) { PersistenceEmulation.hydrate(entity, "validationMessages", value) }
    var kositReport: String?
        get() = entity.kositReport
        set(value) { PersistenceEmulation.hydrate(entity, "kositReport", value) }
    var reproduced: Boolean
        get() = entity.reproduced
        set(value) { PersistenceEmulation.hydrate(entity, "reproduced", value) }
    var reproducedAt: OffsetDateTime?
        get() = entity.reproducedAt
        set(value) { PersistenceEmulation.hydrate(entity, "reproducedAt", value) }
    var reproductionOfSha256: String?
        get() = entity.reproductionOfSha256
        set(value) { PersistenceEmulation.hydrate(entity, "reproductionOfSha256", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
}

/** Edits [InvoiceDocument] in place through its domain operations. */
fun InvoiceDocument.edit(block: InvoiceDocumentEditor.() -> Unit): InvoiceDocument = also { InvoiceDocumentEditor(it).block() }

/**
 * A blank stored-document row; `InvoiceDocument.create` and the record/mark methods cover the production
 * paths.
 */
fun anInvoiceDocument(block: InvoiceDocumentEditor.() -> Unit = {}): InvoiceDocument = InvoiceDocument().edit(block)
