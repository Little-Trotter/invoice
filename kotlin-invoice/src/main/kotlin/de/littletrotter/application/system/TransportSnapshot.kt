package de.littletrotter.application.system

/**
 * The raw JetStream figures [JobPipelineStatusMapper] needs, gathered by the
 * infrastructure adapter ([de.littletrotter.infrastructure.messaging.NatsJobPipelineStatusService])
 * so the mapper itself stays free of any NATS client type and unit-testable without a broker.
 * `null` at the call site (not a field here) means "broker unreachable" — see
 * [JobPipelineStatusMapper.map].
 */
data class TransportSnapshot(
    val queueDepth: Long,
    val ackPending: Long,
    val unprocessedResults: Long,
    val redelivered: Long,
    val workerConnected: Boolean,
    val dlqMessages: Long,
    val dlqBytes: Long,
)
