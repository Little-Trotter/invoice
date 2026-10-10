package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
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

    @field:Size(max = 255)
    var fileName: String = ""

    /** Required (non-blank). Defaults to the empty string only so JPA can instantiate the
     *  entity; [validate] rejects a blank value on every write path. */
    @Column(columnDefinition = "TEXT", nullable = false)
    var description: String = ""

    @field:Size(max = 512)
    @Column(unique = true)
    var objectKey: String = ""

    var sizeBytes: Long? = null

    @field:Size(max = 64)
    @Column(length = 64)
    var sha256Checksum: String? = null

    var uploadedAt: OffsetDateTime? = null

    /** The date the document itself carries (Belegdatum) — not the upload timestamp. Required:
     *  the type stays nullable only because no default date would be honest (a forced default
     *  would silently record a wrong date on a document); [validate] rejects null on every
     *  write path and the column is NOT NULL. */
    @Column(nullable = false)
    var documentDate: LocalDate? = null

    @Version
    var version: Long? = 0

    /** How far the stored bytes were reduced from what was uploaded — see
     *  [ReceiptNormalisationStage]. Defaults to [ReceiptNormalisationStage.NONE]: a receipt that
     *  already met the target at upload is stored unchanged. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var normalisationStage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE

    /** The size of the bytes as uploaded, before any reduction — always set on upload, whether or
     *  not a reduction actually ran ([normalisationStage] carries that distinction). Kept for the
     *  receipt list and audit trail; the pool itself never keeps the original bytes, only this
     *  number. A reducer's contract guarantees its output is never larger than its input, so this
     *  is validated as non-negative only — comparing it against [sizeBytes] here would duplicate a
     *  guarantee the reducer already owns. */
    var originalSizeBytes: Long? = null

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
