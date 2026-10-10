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

    @ManyToOne(fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null

    @jakarta.persistence.Convert(converter = de.littletrotter.domain.invoice.converters.LlmAnalysisStatusConverter::class)
    @Column(nullable = false)
    var status: LlmAnalysisStatus = LlmAnalysisStatus.PENDING

    var rating: Int? = null

    @Column(columnDefinition = "TEXT")
    var summary: String? = null

    @Column(columnDefinition = "TEXT")
    var issues: String? = null

    @Column(columnDefinition = "TEXT")
    var suggestions: String? = null

    @Column(columnDefinition = "TEXT")
    var improvedXml: String? = null

    @field:Size(max = 100)
    @Column(length = 100)
    var modelName: String = ""

    var analyzedAt: OffsetDateTime? = null

    @Version
    var version: Long? = 0
}
