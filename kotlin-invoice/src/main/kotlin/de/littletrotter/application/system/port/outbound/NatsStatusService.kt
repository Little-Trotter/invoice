package de.littletrotter.application.system.port.outbound

import de.littletrotter.application.dto.NatsStatus

/**
 * Application-layer port for NATS JetStream health and throughput visibility.
 */
fun interface NatsStatusService {
    fun currentStatus(): NatsStatus
}
