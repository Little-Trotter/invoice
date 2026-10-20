package de.littletrotter.domain.invoice

import de.littletrotter.domain.shared.ValidationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

@Entity
@Table(name = "invoice_document")
class InvoiceDocument : de.littletrotter.domain.shared.NamedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    @field:Size(max = 255)
    @Column(name = "short_name")
    var shortName: String? = null
    protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null
    protected set

    @field:Size(max = 50)
    var documentType: String = ""
    protected set

    @field:Size(max = 512)
    var objectKey: String = ""
    protected set

    @field:Size(max = 255)
    var fileName: String = ""
    protected set

    @field:Size(max = 100)
    var mimeType: String = ""
    protected set
    val sizeBytes: Long? = null

    @field:Size(max = 64)
    @Column(length = 64)
    var sha256Checksum: String? = null
    protected set

    var generatedAt: OffsetDateTime? = null
    protected set

    @field:Size(max = 255)
    val generatedBy: String? = null

    @jakarta.persistence.Convert(converter = de.littletrotter.domain.invoice.converters.DocumentGenerationStatusConverter::class)
    @Column(nullable = false)
    var generationStatus: DocumentGenerationStatus = DocumentGenerationStatus.PENDING
    protected set

    // validation-status-truthful-pending.md — same NOT_VALIDATED-by-default reasoning as
    // Invoice.validationStatus.
    @jakarta.persistence.Convert(converter = de.littletrotter.domain.shared.converters.ValidationStatusConverter::class)
    @Column(nullable = false)
    val validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED

    @Column(columnDefinition = "TEXT")
    val validationMessages: String? = null

    @Column(name = "kosit_report", columnDefinition = "TEXT")
    val kositReport: String? = null

    /** Internal tag: this document is a re-render because the original object was lost. Only the
     *  internal UI shows it; the document bytes carry no mark. */
    @Column(nullable = false)
    var reproduced: Boolean = false
    protected set

    var reproducedAt: OffsetDateTime? = null
    protected set

    /** Checksum of the lost original this document replaces. */
    @field:Size(max = 64)
    @Column(length = 64)
    var reproductionOfSha256: String? = null
    protected set

    @Version
    var version: Long? = 0
    protected set

    /** Stores the verified checksum of freshly rendered bytes and completes the row. */
    fun recordGenerated(sha256: String, at: OffsetDateTime) {
        sha256Checksum = sha256
        generatedAt = at
        generationStatus = DocumentGenerationStatus.COMPLETED
    }

    /** Tags the row as a re-render of a lost original whose checksum was [ofSha256]. */
    fun markReproduced(ofSha256: String?, at: OffsetDateTime) {
        reproduced = true
        reproducedAt = at
        reproductionOfSha256 = ofSha256
    }

    /** The stored object is gone and has to be rendered or restored again. */
    fun markLost() {
        generationStatus = DocumentGenerationStatus.LOST
    }

    /** The original bytes are back: the row takes their checksum and loses the reproduction tag. */
    fun restoreOriginal(sha256: String) {
        sha256Checksum = sha256
        reproduced = false
        reproducedAt = null
        reproductionOfSha256 = null
    }

    private fun populate(invoice: Invoice, documentType: String, objectKey: String, mimeType: String) {
        this.invoice = invoice
        this.documentType = documentType
        this.objectKey = objectKey
        this.mimeType = mimeType
        fileName = objectKey.substringAfterLast('/')
        shortName = fileName
    }

    companion object {
        /** A stored object of [invoice]; the file name is the last segment of [objectKey]. */
        fun create(invoice: Invoice, documentType: String, objectKey: String, mimeType: String): InvoiceDocument =
            InvoiceDocument().also { it.populate(invoice, documentType, objectKey, mimeType) }
    }
}
