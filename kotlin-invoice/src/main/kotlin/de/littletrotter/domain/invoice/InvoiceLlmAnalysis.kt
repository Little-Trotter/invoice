package de.littletrotter.domain.invoice

import jakarta.persistence.CascadeType
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
@Table(name = "invoice_llm_analysis")
class InvoiceLlmAnalysis : de.littletrotter.domain.shared.NamedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    @ManyToOne(fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null
    protected set

    @jakarta.persistence.Convert(converter = de.littletrotter.domain.invoice.converters.LlmAnalysisStatusConverter::class)
    @Column(nullable = false)
    var status: LlmAnalysisStatus = LlmAnalysisStatus.PENDING
    protected set

    val rating: Int? = null

    @Column(columnDefinition = "TEXT")
    var summary: String? = null
    protected set

    @Column(columnDefinition = "TEXT")
    var issues: String? = null
    protected set

    @Column(columnDefinition = "TEXT")
    val suggestions: String? = null

    @Column(columnDefinition = "TEXT")
    val improvedXml: String? = null

    @field:Size(max = 100)
    @Column(length = 100)
    var modelName: String = ""
    protected set

    var analyzedAt: OffsetDateTime? = null
    protected set

    @Version
    var version: Long? = 0
    protected set

    /** A PENDING run requested for [model]. */
    private fun populate(invoice: Invoice, model: String) {
        this.invoice = invoice
        modelName = model
    }

    /** Puts an existing record back into the queue for another run. */
    fun requeue() {
        status = LlmAnalysisStatus.PENDING
    }

    /** Remembers which model the reported run used. */
    fun adoptModel(model: String) {
        modelName = model
    }

    fun complete(summary: String, analyzedAt: OffsetDateTime) {
        status = LlmAnalysisStatus.COMPLETED
        this.analyzedAt = analyzedAt
        this.summary = summary
        issues = null
    }

    fun fail(issues: String, summary: String?, analyzedAt: OffsetDateTime) {
        status = LlmAnalysisStatus.FAILED
        this.analyzedAt = analyzedAt
        this.issues = issues
        this.summary = summary
    }

    /** The job ran without the check, so the record resolves without a result. */
    fun disable(summary: String, analyzedAt: OffsetDateTime) {
        status = LlmAnalysisStatus.DISABLED
        this.analyzedAt = analyzedAt
        this.summary = summary
    }

    companion object {
        fun start(invoice: Invoice, modelName: String): InvoiceLlmAnalysis =
            InvoiceLlmAnalysis().also { it.populate(invoice, modelName) }
    }
}
