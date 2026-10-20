package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.receipt.ReceiptPageProfile
import de.littletrotter.shared.receipt.ReceiptRasterKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "receipt")
class Receipt : de.littletrotter.domain.shared.NamedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    @field:Size(max = 255)
    var fileName: String = ""
    protected set

    /** Required (non-blank). Defaults to the empty string only so JPA can instantiate the
     *  entity; [validate] rejects a blank value on every write path. */
    @Column(columnDefinition = "TEXT", nullable = false)
    var description: String = ""
    protected set

    @field:Size(max = 512)
    @Column(unique = true)
    var objectKey: String = ""
    protected set

    var sizeBytes: Long? = null
    protected set

    @field:Size(max = 64)
    @Column(length = 64)
    var sha256Checksum: String? = null
    protected set

    var uploadedAt: OffsetDateTime? = null
    protected set

    /** The date the document itself carries (Belegdatum) — not the upload timestamp. Required:
     *  the type stays nullable only because no default date would be honest (a forced default
     *  would silently record a wrong date on a document); [validate] rejects null on every
     *  write path and the column is NOT NULL. */
    @Column(nullable = false)
    var documentDate: LocalDate? = null
    protected set

    @Version
    var version: Long? = 0
    protected set

    /** How far the stored bytes were reduced from what was uploaded — see
     *  [ReceiptNormalisationStage]. Defaults to [ReceiptNormalisationStage.NONE]: a receipt that
     *  already met the target at upload is stored unchanged. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var normalisationStage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE
    protected set

    /** The size of the bytes as uploaded, before any reduction — always set on upload, whether or
     *  not a reduction actually ran ([normalisationStage] carries that distinction). Kept for the
     *  receipt list and audit trail; the pool itself never keeps the original bytes, only this
     *  number. A reducer's contract guarantees its output is never larger than its input, so this
     *  is validated as non-negative only — comparing it against [sizeBytes] here would duplicate a
     *  guarantee the reducer already owns. */
    var originalSizeBytes: Long? = null
    protected set

    /** Pages of the stored bytes, read at upload without rendering. */
    @Column(nullable = false)
    var pageCount: Int = 1
    protected set

    /** Sum of the displayed page areas relative to A4; the budget estimate of a rendered receipt. */
    @Column(nullable = false)
    var a4Factor: Double = 1.0
    protected set

    /** Whether the print copies the pages as they are or renders them — see [ReceiptRasterKind]. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var rasterKind: ReceiptRasterKind = ReceiptRasterKind.DOCUMENT
    protected set

    /** Bytes the receipt's pages weigh in the printed annex, measured at upload — see
     *  [ReceiptPageProfile.annexBytes]. Zero only on a row that bypassed the upload; the profile
     *  then assumes the pages weigh as much as the stored bytes. */
    @Column(nullable = false)
    var annexBytes: Long = 0
    protected set

    /** The page facts as one value, for the budget estimate and the export contract. */
    fun pageProfile(): ReceiptPageProfile =
        if (annexBytes > 0) {
            ReceiptPageProfile(pageCount, a4Factor, rasterKind, annexBytes)
        } else {
            ReceiptPageProfile(pageCount, a4Factor, rasterKind, maxOf(sizeBytes ?: 0L, 1L))
        }

    /** Corrects the required description and Belegdatum of a pooled receipt. */
    fun describe(description: String, documentDate: LocalDate) {
        this.description = description
        this.documentDate = documentDate
    }

    /** Remembers the size of the uploaded bytes once, before any reduction; later calls keep the
     *  first value. */
    fun recordOriginalSize(sizeBytes: Long) {
        if (originalSizeBytes == null) originalSizeBytes = sizeBytes
    }

    private fun recordProfile(profile: ReceiptPageProfile) {
        pageCount = profile.pageCount
        a4Factor = profile.a4Factor
        rasterKind = profile.rasterKind
        annexBytes = profile.annexBytes
    }

    companion object {
        /** A receipt as stored on upload; callers run [validate] before persisting it. */
        fun create(upload: ReceiptUpload): Receipt = Receipt().also {
            it.fileName = upload.fileName
            it.description = upload.description
            it.objectKey = upload.objectKey
            it.documentDate = upload.documentDate
            it.sizeBytes = upload.sizeBytes
            it.sha256Checksum = upload.sha256
            it.uploadedAt = upload.uploadedAt
            it.normalisationStage = upload.normalisationStage
            it.originalSizeBytes = upload.originalSizeBytes
            it.recordProfile(upload.profile)
        }
    }

    fun validate() {
        if (fileName.isBlank()) throw InvalidParameterException("Receipt file name must not be blank.")
        if (description.isBlank()) throw InvalidParameterException("Receipt description must not be blank.")
        if (documentDate == null) throw InvalidParameterException("Receipt document date is missing.")
        if (objectKey.isBlank()) throw InvalidParameterException("Receipt object key must not be blank.")
        val size = sizeBytes ?: throw InvalidParameterException("Receipt size is missing.")
        if (size < 0) throw InvalidParameterException("Receipt size must not be negative.")
        if (sha256Checksum.isNullOrBlank()) throw InvalidParameterException("Receipt SHA-256 checksum is missing.")
        if (uploadedAt == null) throw InvalidParameterException("Receipt upload timestamp is missing.")
        originalSizeBytes?.let {
            if (it < 0) throw InvalidParameterException("Receipt original size must not be negative.")
        }
    }
}
