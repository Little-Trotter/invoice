package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * A dedicated CDI bean, the same reasoning
 * (PO-Grundsatz 29.08.2026, "CDI-Injektionen nie ausnehmen") as [GenerationJobFastPublishTransaction]'s own
 * KDoc: its sole job is to give [GenerationJobRecoveryService.resolveStaleLlmPendingForIds]'s per-batch
 * write a genuinely separate `@Transactional` scope. A same-class self-call on
 * [GenerationJobRecoveryService] itself would bypass the Jakarta interceptor chain entirely (Kotlin/JVM
 * self-invocation never goes through a CDI proxy), so [GenerationJobRecoveryService
 * .resetStaleLlmPendingBatched] calls [resolveBatch] on this genuinely distinct injected bean
 * instead, once per chunk of ids — that is what actually makes each chunk commit in its own
 * transaction instead of accumulating into one unbounded one.
 *
 * Fixes the finding: [GenerationJobRecoveryService.resetStaleLlmPending] used to run as a single
 * `@Transactional` method over the whole `findCompletedBetween` result set, which could hit the
 * Narayana transaction timeout once that window held enough stale rows under load.
 *
 * Injects [GenerationJobRecoveryService] directly; the dissolved `GenerationJobService` facade
 * used to sit in between.
 */
@ApplicationScoped
class LlmPendingRecoveryBatchTransaction @Inject constructor(
    private val recoveryService: GenerationJobRecoveryService,
) {
    @Transactional
    fun resolveBatch(ids: List<Long>): Int = recoveryService.resolveStaleLlmPendingForIds(ids)
}
