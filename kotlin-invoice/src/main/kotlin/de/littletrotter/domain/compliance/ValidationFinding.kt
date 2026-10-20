package de.littletrotter.domain.compliance

import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

/**
 * F5 — one row per validation finding, the queryable view alongside the
 * [de.littletrotter.domain.invoice.GenerationJob.kositReport] blob. Written once, in
 * [de.littletrotter.application.invoice.GenerationJobService.markCompleted], never updated.
 */
@Entity
@Table(name = "validation_finding")
@EntityListeners(CreationStampListener::class)
class ValidationFinding : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "generation_job_id", nullable = false)
    var generationJobId: Long = 0
        protected set

    @Column(name = "invoice_id", nullable = false)
    var invoiceId: Long = 0
        protected set

    @field:Size(max = 20)
    @Column(length = 20)
    var stage: String? = null
        protected set

    @field:Size(max = 50)
    @Column(length = 50)
    var validator: String? = null
        protected set

    @field:Size(max = 50)
    @Column(name = "rule_id", length = 50)
    var ruleId: String? = null
        protected set

    @field:Size(max = 20)
    @Column(nullable = false, length = 20)
    var severity: String = ""
        protected set

    @Column(columnDefinition = "TEXT")
    var location: String? = null
        protected set

    @Column(nullable = false, columnDefinition = "TEXT")
    var message: String = ""
        protected set

    // Joined versions of every validation stage (KoSIT engine/scenario release, veraPDF, XSD, ...),
    // which exceeds 100 characters on a full run.
    @field:Size(max = 500)
    @Column(name = "validator_version", length = 500)
    var validatorVersion: String? = null
        protected set

    @Column(name = "created_at")
    var createdAt: OffsetDateTime? = null
        protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    companion object {
        /** One finding of a validator run on the generation job's document, written once. */
        fun record(
            generationJobId: Long,
            invoiceId: Long,
            result: ValidatorResult,
        ): ValidationFinding = ValidationFinding().apply {
            this.generationJobId = generationJobId
            this.invoiceId = invoiceId
            stage = result.stage
            validator = result.validator
            ruleId = result.ruleId
            severity = result.severity
            location = result.location
            message = result.message
            validatorVersion = result.validatorVersion
        }
    }
}

/** What a validator reported for one rule, as stored in a [ValidationFinding]. */
data class ValidatorResult(
    val stage: String?,
    val validator: String?,
    val ruleId: String?,
    val severity: String,
    val location: String?,
    val message: String,
    val validatorVersion: String?,
)
