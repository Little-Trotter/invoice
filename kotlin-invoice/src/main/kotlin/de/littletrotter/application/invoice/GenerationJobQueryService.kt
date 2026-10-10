package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * GenerationJobService's UI-facing/plain read queries, extracted verbatim.
 */
@ApplicationScoped
class GenerationJobQueryService @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
) {
    fun findByInvoiceId(invoiceId: Long): List<GenerationJob> =
        generationJobRepository.findByInvoiceId(invoiceId)

    fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> =
        generationJobRepository.findByInvoiceIdWithTaxRates(invoiceId)

    fun findPending(): List<GenerationJob> =
        generationJobRepository.findPending()

    fun findPendingWithInvoice(): List<GenerationJob> =
        generationJobRepository.findPendingWithInvoice()

    /**
     * L2 — `@Transactional` is load
     * bearing here, for a different reason than on [findByIdWithInvoice] below.
     *
     * `GenerationJobService` carries a class-level `@SetsAuditGuc`, so EVERY method on it —
     * including a plain read like this one — was wrapped by
     * [de.littletrotter.application.shared.AuditGucInterceptor], which issues a native query
     * before the body runs. Without a transaction that query opened a SECOND persistence context
     * alongside the one the caller's next `@Transactional` call opens, and both were then
     * enlisted in the same JTA transaction. This class carries no `@SetsAuditGuc` itself, but the
     * `@Transactional` is kept for the same one-persistence-context guarantee its callers rely on.
     */
    @Transactional
    fun findById(id: Long): GenerationJob? = generationJobRepository.findById(id)

    /**
     * Like [findById] but eagerly fetches [GenerationJob.invoice], for the fast-path
     * publish ([GenerationJobFastPublishTransaction.publishOne]) which runs on the injected managed-executor
     * worker thread, not a request/`@Scheduled` thread. A plain `Executor` task gets **no** context
     * at all — `@Transactional` here is what actually opens one, activating the persistence
     * context for the query.
     */
    @Transactional
    fun findByIdWithInvoice(id: Long): GenerationJob? = generationJobRepository.findByIdWithInvoice(id)
}
