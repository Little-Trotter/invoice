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

    @field:Size(max = 255)
    @Column(name = "short_name")
    var shortName: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null

    @field:Size(max = 50)
    var documentType: String = ""

    @field:Size(max = 512)
    var objectKey: String = ""

    @field:Size(max = 255)
    var fileName: String = ""

    @field:Size(max = 100)
    var mimeType: String = ""
    var sizeBytes: Long? = null

    @field:Size(max = 64)
    @Column(length = 64)
    var sha256Checksum: String? = null

    var generatedAt: OffsetDateTime? = null

    @field:Size(max = 255)
    var generatedBy: String? = null

    @jakarta.persistence.Convert(converter = de.littletrotter.domain.invoice.converters.DocumentGenerationStatusConverter::class)
    @Column(nullable = false)
    var generationStatus: DocumentGenerationStatus = DocumentGenerationStatus.PENDING

    // validation-status-truthful-pending.md — same NOT_VALIDATED-by-default reasoning as
    // Invoice.validationStatus.
    @jakarta.persistence.Convert(converter = de.littletrotter.domain.shared.converters.ValidationStatusConverter::class)
    @Column(nullable = false)
    var validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED

    @Column(columnDefinition = "TEXT")
    var validationMessages: String? = null

    @Column(name = "kosit_report", columnDefinition = "TEXT")
    var kositReport: String? = null

    /** Internal tag: this document is a re-render because the original object was lost. Only the
     *  internal UI shows it; the document bytes carry no mark. */
    @Column(nullable = false)
    var reproduced: Boolean = false

    var reproducedAt: OffsetDateTime? = null

    /** Checksum of the lost original this document replaces. */
    @field:Size(max = 64)
    @Column(length = 64)
    var reproductionOfSha256: String? = null

    @Version
    var version: Long? = 0
}
