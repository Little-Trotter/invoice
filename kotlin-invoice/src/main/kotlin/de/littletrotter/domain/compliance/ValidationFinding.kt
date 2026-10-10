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

    @Column(name = "generation_job_id", nullable = false)
    var generationJobId: Long = 0

    @Column(name = "invoice_id", nullable = false)
    var invoiceId: Long = 0

    @field:Size(max = 20)
    @Column(length = 20)
    var stage: String? = null

    @field:Size(max = 50)
    @Column(length = 50)
    var validator: String? = null

    @field:Size(max = 50)
    @Column(name = "rule_id", length = 50)
    var ruleId: String? = null

    @field:Size(max = 20)
    @Column(nullable = false, length = 20)
    var severity: String = ""

    @Column(columnDefinition = "TEXT")
    var location: String? = null

    @Column(nullable = false, columnDefinition = "TEXT")
    var message: String = ""

    @field:Size(max = 100)
    @Column(name = "validator_version", length = 100)
    var validatorVersion: String? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime? = null

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }
}
