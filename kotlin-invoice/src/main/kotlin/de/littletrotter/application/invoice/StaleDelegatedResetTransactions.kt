package de.littletrotter.application.invoice

import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.StaleDelegatedCandidate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * The two short, separately-transactional database calls
 * [StaleDelegatedResetCoordinator] needs around its own off-transaction NATS decision. A dedicated
 * bean for the same reason as [GenerationJobFastPublishTransaction]/[LlmPendingRecoveryBatchTransaction] (PO-
 * Grundsatz 29.08.2026, "CDI-Injektionen nie ausnehmen"): [StaleDelegatedResetCoordinator] calls
 * [findCandidates] and [applyResets] as two genuinely separate cross-bean calls, so each one's own
 * `@Transactional` is reached through this bean's CDI proxy rather than bypassed by a same-instance
 * self-call — that separation is the whole point (see [StaleDelegatedResetCoordinator]'s own KDoc
 * for why the NATS round trip in between must never run inside either transaction).
 */
@ApplicationScoped
class StaleDelegatedResetTransactions @Inject constructor(
    private val generationJobRepository: GenerationJobRepository,
) {
    @Transactional
    fun findCandidates(staleCutoffMinutes: Long): List<StaleDelegatedCandidate> =
        generationJobRepository.findStaleDelegatedCandidates(staleCutoffMinutes)

    @Transactional
    fun applyResets(ids: List<Long>): Int =
        generationJobRepository.resetStaleDelegatedByIds(ids)
}
