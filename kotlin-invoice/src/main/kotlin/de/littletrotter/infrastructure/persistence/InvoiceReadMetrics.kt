package de.littletrotter.infrastructure.persistence

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.TimeUnit

@ApplicationScoped
class InvoiceReadMetrics @Inject constructor(registry: MeterRegistry) {

    private val readTimer: Timer = Timer.builder("invoice.read.duration")
        .description("Time to build InvoiceDetailDto from DB")
        .register(registry)

    private val readCounter = registry.counter("invoice.read.count")
    private val failureCounter = registry.counter("invoice.read.failures")

    fun recordSuccess(latencyMs: Long) {
        readCounter.increment()
        readTimer.record(latencyMs, TimeUnit.MILLISECONDS)
    }

    fun recordFailure() {
        failureCounter.increment()
    }
}
