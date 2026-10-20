package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.system.port.outbound.NatsStatusService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

@Readiness
@ApplicationScoped
class NatsHealthCheck @Inject constructor(
    private val natsStatusService: NatsStatusService,
) : HealthCheck {

    override fun call(): HealthCheckResponse {
        val status = natsStatusService.currentStatus()
        val builder = HealthCheckResponse.named("nats")
            .withData("serverUrl", status.serverUrl)
            .withData("sentMessages", status.sentMessages)
            .withData("totalLag", status.totalLag)
            // AP-M3 §6 — a non-empty DLQ is a warning, not a readiness failure: still UP below.
            .withData("dlqMessages", status.dlqMessages)
        if (!status.error.isNullOrBlank()) {
            builder.withData("error", status.error)
        }
        return if (status.brokerUp) builder.up().build() else builder.down().build()
    }
}
