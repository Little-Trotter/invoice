package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Qualifier
import org.eclipse.microprofile.context.ManagedExecutor
import org.eclipse.microprofile.context.ThreadContext

/**
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — qualifies the specific
 * [ManagedExecutor] bean [GenerationJobProcessor] needs for its `onGenerationJobCreated` fast
 * path, distinct from Quarkus's own unqualified default [ManagedExecutor] bean.
 *
 * Empirically verified while fixing the CDI-injection-point type (`Executor` → `ManagedExecutor`,
 * see [GenerationJobProcessor.managedExecutor]'s own KDoc): Quarkus's default, unqualified
 * `ManagedExecutor` propagates the JTA Transaction context onto the executed task by default. That
 * is exactly wrong here — [GenerationJobProcessor.onGenerationJobCreated] fires at
 * `TransactionPhase.AFTER_SUCCESS`, i.e. after the creating transaction has already committed; the
 * calling thread's Transaction-context marker at that instant does not describe a transaction the
 * worker thread can actually continue. Propagating it anyway causes
 * [GenerationJobFastPublishTransaction.publishOne]'s own `@Transactional` interceptor to believe a
 * transaction is already active (`invokeInCallerTx`) instead of starting the fresh one the whole
 * point of that method's own KDoc requires, and the first Panache write inside it then fails with
 * "No active transaction for update or delete query" — a genuine production bug this qualifier
 * exists to prevent, not just a Qodana-satisfying formality.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class FastPathExecutor

@ApplicationScoped
class FastPathManagedExecutorProducer {

    /**
     * Propagates every other context as usual (`ALL_REMAINING` — CDI, MDC, etc., same as
     * Quarkus's own unqualified default) but explicitly clears the Transaction context — see this
     * file's own class KDoc for the incident this one exception prevents. `@ApplicationScoped` on
     * the produced bean: one shared executor, not one per injection point.
     */
    @Produces
    @ApplicationScoped
    @FastPathExecutor
    fun fastPathManagedExecutor(): ManagedExecutor =
        ManagedExecutor.builder()
            .cleared(ThreadContext.TRANSACTION)
            .propagated(ThreadContext.ALL_REMAINING)
            .build()
}
