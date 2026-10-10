package de.littletrotter.application.invoice

import org.eclipse.microprofile.context.ManagedExecutor
import org.eclipse.microprofile.context.ThreadContext
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * PO-Grundsatz 29.08.2026 (CDI-Injektionen nie ausnehmen) — [GenerationJobProcessor]'s
 * `managedExecutor` constructor parameter is typed as the concrete [ManagedExecutor]
 * `quarkus-smallrye-context-propagation` registers a bean for (see that parameter's own KDoc),
 * rather than the plain [Executor] the field used to carry — a bare `java.util.concurrent.Executor`
 * injection point has no single resolvable CDI bean of that exact type, which is exactly what
 * Qodana's CDI inspector flagged as an "unsatisfied dependency".
 *
 * This adapter exists purely to keep [GenerationJobProcessor]'s non-CDI constructors (used only by
 * tests and by that class's own same-thread default — see there for both call sites) able to
 * supply a trivial, deterministic executor without spinning up a real managed executor pool.
 * [GenerationJobProcessor] itself only ever calls [execute] on its injected executor; every other
 * member below exists solely because [ManagedExecutor] extends
 * [java.util.concurrent.ExecutorService] and declares them, and is implemented in terms of
 * [delegate] the same way a plain same-thread/caller-supplied [Executor] would behave — not
 * because anything in this codebase calls them through this adapter.
 */
internal class DelegatingManagedExecutor(private val delegate: Executor) : AbstractExecutorService(), ManagedExecutor {

    override fun execute(command: Runnable) = delegate.execute(command)

    override fun shutdown() = Unit

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown(): Boolean = false

    override fun isTerminated(): Boolean = false

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

    override fun <U> completedFuture(value: U): CompletableFuture<U> = CompletableFuture.completedFuture(value)

    override fun <U> completedStage(value: U): CompletionStage<U> = CompletableFuture.completedFuture(value)

    override fun <U> failedFuture(ex: Throwable): CompletableFuture<U> =
        CompletableFuture<U>().apply { completeExceptionally(ex) }

    override fun <U> failedStage(ex: Throwable): CompletionStage<U> =
        CompletableFuture<U>().apply { completeExceptionally(ex) }

    override fun <U> newIncompleteFuture(): CompletableFuture<U> = CompletableFuture()

    override fun runAsync(runnable: Runnable): CompletableFuture<Void> = CompletableFuture.runAsync(runnable, this)

    override fun <U> supplyAsync(supplier: Supplier<U>): CompletableFuture<U> =
        CompletableFuture.supplyAsync(supplier, this)

    override fun <T> copy(stage: CompletableFuture<T>): CompletableFuture<T> = stage

    override fun <T> copy(stage: CompletionStage<T>): CompletionStage<T> = stage

    override fun getThreadContext(): ThreadContext =
        throw UnsupportedOperationException("DelegatingManagedExecutor carries no ThreadContext — never called by GenerationJobProcessor")
}
