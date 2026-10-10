package de.littletrotter.application.invoice

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * E2E Welle C, Punkt 6 — [DocumentGeneratedReactionHandler] swallows
 * every reaction failure by design (a reaction is a side effect of a completed
 * [de.littletrotter.domain.invoice.GenerationJob], never the point of it, so it must never roll
 * back or rethrow onto the CDI async executor thread). The PO decision keeps that behavior but
 * demands visibility: this bean gives the otherwise-silent catch blocks a counter, tagged by which
 * of the three reactions failed, so an operator can alert on a rising `document_generated.reaction
 * .failures` count instead of relying on grepping logs.
 *
 * All three tagged counters are registered **eagerly**, in the constructor, and therefore appear in
 * `/q/metrics` reading 0 from startup. That is the whole point of the bean and not an incidental
 * detail: Micrometer creates a meter on first use, so registering inside [recordFailure] would keep
 * the series absent until the first failure ever occurred. An operator asked to "check the counter"
 * would then find nothing — and "no failures yet" and "this build does not have the counter" would
 * look exactly alike. A Prometheus alert has the same problem: it cannot establish a baseline on a
 * series that does not exist, so `rate(document_generated_reaction_failures_total[1h]) > 0` stays
 * silent rather than firing on the very first failure. Registering up front makes absence mean
 * "not deployed" and 0 mean "no failures", which is what the decision in Punkt 6 relies on.
 *
 * Follows the same dedicated-metrics-bean shape as
 * [de.littletrotter.infrastructure.persistence.InvoiceReadMetrics], which registers its counters as
 * fields for the same reason: a public constructor taking a plain [MeterRegistry] (so plain unit
 * tests can wire a [io.micrometer.core.instrument.simple.SimpleMeterRegistry] with no CDI
 * container), plus the `@Inject`-annotated one Quarkus/ArC actually uses in production.
 */
@ApplicationScoped
class DocumentGeneratedReactionMetrics @Inject constructor(registry: MeterRegistry) {

    private val failureCounters: Map<Reaction, Counter> = Reaction.entries.associateWith { reaction ->
        Counter.builder(FAILURE_COUNTER_NAME)
            .description("Swallowed DocumentGenerated reaction failures, tagged by reaction")
            .tag("reaction", reaction.metricTag)
            .register(registry)
    }

    /** Increments the failure counter for [reaction] (one of [Reaction]'s [Reaction.metricTag]s). */
    fun recordFailure(reaction: Reaction) {
        failureCounters.getValue(reaction).increment()
    }

    /** Current failure count for [reaction] — used by tests; production code only ever writes. */
    fun failureCount(reaction: Reaction): Double = failureCounters.getValue(reaction).count()

    /** The three [DocumentGeneratedReactionHandler] observer methods, identified for tagging. */
    enum class Reaction(val metricTag: String) {
        UPDATE_VALIDATION_STATUS("updateValidationStatus"),
        PERSIST_FINDINGS("persistFindings"),
        EVICT_CACHE("evictCache"),
    }

    private companion object {
        const val FAILURE_COUNTER_NAME = "document_generated.reaction.failures"
    }
}
