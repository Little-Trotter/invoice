package de.littletrotter.fixture

import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Property-style access to the stored state of a [Receipt].
 *
 * A receipt is stored as uploaded; `Receipt.create` needs every mandatory field, so a row missing some of them
 * (the cases the validation guards) can only be hydrated.
 */
class ReceiptEditor internal constructor(val entity: Receipt) {
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var fileName: String
        get() = entity.fileName
        set(value) { PersistenceEmulation.hydrate(entity, "fileName", value) }
    var description: String
        get() = entity.description
        set(value) { PersistenceEmulation.hydrate(entity, "description", value) }
    var objectKey: String
        get() = entity.objectKey
        set(value) { PersistenceEmulation.hydrate(entity, "objectKey", value) }
    var sizeBytes: Long?
        get() = entity.sizeBytes
        set(value) { PersistenceEmulation.hydrate(entity, "sizeBytes", value) }
    var sha256Checksum: String?
        get() = entity.sha256Checksum
        set(value) { PersistenceEmulation.hydrate(entity, "sha256Checksum", value) }
    var uploadedAt: OffsetDateTime?
        get() = entity.uploadedAt
        set(value) { PersistenceEmulation.hydrate(entity, "uploadedAt", value) }
    var documentDate: LocalDate?
        get() = entity.documentDate
        set(value) { PersistenceEmulation.hydrate(entity, "documentDate", value) }
    var version: Long?
        get() = entity.version
        set(value) { PersistenceEmulation.assignVersion(entity, value) }
    var normalisationStage: ReceiptNormalisationStage
        get() = entity.normalisationStage
        set(value) { PersistenceEmulation.hydrate(entity, "normalisationStage", value) }
    var originalSizeBytes: Long?
        get() = entity.originalSizeBytes
        set(value) { PersistenceEmulation.hydrate(entity, "originalSizeBytes", value) }
}

/** Edits [Receipt] in place through its domain operations. */
fun Receipt.edit(block: ReceiptEditor.() -> Unit): Receipt = also { ReceiptEditor(it).block() }

/**
 * A blank receipt row; partial rows (no size, no date, no checksum) exist only as loaded data, because
 * `Receipt.create` demands all of them.
 */
fun aReceipt(block: ReceiptEditor.() -> Unit = {}): Receipt = Receipt().edit(block)
