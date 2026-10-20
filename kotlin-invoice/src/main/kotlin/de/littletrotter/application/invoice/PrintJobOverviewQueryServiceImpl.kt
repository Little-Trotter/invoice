package de.littletrotter.application.invoice

import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.invoice.port.inbound.PrintJobOverviewQueryPort

/**
 * [PrintJobOverviewQueryPort] backed by [GenerationJobRepository.findPaged]/[GenerationJobRepository
 * .countFiltered]. Unpacks [GenerationJobFilter] into that port's plain parameters (the domain
 * port must not depend on this application-layer type, see [de.littletrotter.arch
 * .ArchitectureRulesTest]) and maps the fetch-joined entities the repository returns into the
 * flat [PrintJobOverviewRow] projection the UI is allowed to hold.
 */
@ApplicationScoped
class PrintJobOverviewQueryServiceImpl @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
) : PrintJobOverviewQueryPort {

    override fun findPaged(filter: GenerationJobFilter, offset: Int, limit: Int): List<PrintJobOverviewRow> =
        generationJobRepository.findPaged(
            statuses = filter.statuses,
            recipientId = filter.recipientId,
            createdFrom = filter.createdFrom,
            createdTo = filter.createdTo,
            offset = offset,
            limit = limit,
        ).map(::toRow)

    override fun count(filter: GenerationJobFilter): Long =
        generationJobRepository.countFiltered(
            statuses = filter.statuses,
            recipientId = filter.recipientId,
            createdFrom = filter.createdFrom,
            createdTo = filter.createdTo,
        )

    override fun findDaySummaries(filter: GenerationJobFilter): List<PrintJobDaySummary> =
        generationJobRepository.findDaySummaries(
            statuses = filter.statuses,
            recipientId = filter.recipientId,
            createdFrom = filter.createdFrom,
            createdTo = filter.createdTo,
        ).map { PrintJobDaySummary(day = it.day, jobCount = it.jobCount, failedCount = it.failedCount) }

    companion object {
        // findPaged's own JOIN FETCH j.invoice is an inner join (see
        // PanacheGenerationJobRepository.findPaged's own KDoc), so invoice is never null here: a
        // job with no invoice association is excluded from the result set rather than reaching
        // this mapper with one.
        // No Java caller exists anywhere in this all-Kotlin codebase, so @JvmStatic
        // bought nothing but an unreachable synthetic Companion-instance delegate (Kotlin call
        // sites always resolve straight to the static method, never through that delegate) —
        // dropped so the whole function is exercisable.
        fun toRow(job: GenerationJob): PrintJobOverviewRow {
            val invoice = job.invoice!!
            // Lightweight artifact-key fields, parsed straight out of resultRefs here
            // (extractResultRef/derivePdfObjectKey/deriveXmlObjectKey, this same package) rather
            // than shared with the ui-layer helpers of the same name — see that file's own KDoc.
            val pdfObjectKey = derivePdfObjectKey(job.resultRefs)
            return PrintJobOverviewRow(
                jobId = job.id!!,
                invoiceId = invoice.id!!,
                invoiceNumber = invoice.invoiceNumber.value,
                recipientName = invoice.recipient?.name,
                status = job.status,
                createdAt = job.createdAt,
                finishedAt = job.finishedAt,
                attempts = job.attempts,
                lastError = job.lastError,
                llmStatus = job.llmStatus,
                pdfObjectKey = pdfObjectKey,
                xmlObjectKey = deriveXmlObjectKey(job.resultRefs),
            )
        }
    }
}
