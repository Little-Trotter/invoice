package de.littletrotter.testing

import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.shared.StampedOnCreation
import java.time.OffsetDateTime

/**
 * Mutable scratch builder for a [GenerationJobView] with the same defaults a new job row has.
 * [invoice] is a convenience that copies the invoice's id and number into the flat fields.
 */
class GenerationJobViewSpec {
    var id: Long? = null
    var invoiceId: Long? = null
    var invoiceNumber: String? = null
    var jobType: String = ""
    var status: GenerationJobStatus = GenerationJobStatus.PENDING
    var trigger: GenerationJobTrigger = GenerationJobTrigger.MANUAL
    var issued: Boolean = false
    var resultRefs: String? = null
    var kositReport: String? = null
    var llmStatus: String? = null
    var attempts: Int = 0
    var lastError: String? = null
    var createdAt: OffsetDateTime = StampedOnCreation.UNSET
    var finishedAt: OffsetDateTime? = null

    var invoice: Invoice? = null
        set(value) {
            field = value
            invoiceId = value?.id
            invoiceNumber = value?.invoiceNumber?.value
        }

    fun build(): GenerationJobView = GenerationJobView(
        id = id,
        invoiceId = invoiceId,
        invoiceNumber = invoiceNumber,
        jobType = jobType,
        status = status,
        trigger = trigger,
        issued = issued,
        resultRefs = resultRefs,
        kositReport = kositReport,
        llmStatus = llmStatus,
        attempts = attempts,
        lastError = lastError,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )
}

fun jobView(init: GenerationJobViewSpec.() -> Unit = {}): GenerationJobView =
    GenerationJobViewSpec().apply(init).build()

/** Copy of this view with [init] applied to its fields. */
fun GenerationJobView.edit(init: GenerationJobViewSpec.() -> Unit): GenerationJobView {
    val view = this
    return GenerationJobViewSpec().apply {
        id = view.id
        invoiceId = view.invoiceId
        invoiceNumber = view.invoiceNumber
        jobType = view.jobType
        status = view.status
        trigger = view.trigger
        issued = view.issued
        resultRefs = view.resultRefs
        kositReport = view.kositReport
        llmStatus = view.llmStatus
        attempts = view.attempts
        lastError = view.lastError
        createdAt = view.createdAt
        finishedAt = view.finishedAt
        init()
    }.build()
}
