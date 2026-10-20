package de.littletrotter.application.dto

data class NatsStatus(
    val brokerUp: Boolean,
    val serverUrl: String,
    val sentMessages: Long,
    val totalLag: Long,
    val subjects: List<SubjectStatus>,
    // AP-M3 — the DLQ's own stream (LITTLE_TROTTER_DLQ) message count and byte size,
    // queried separately via JetStreamManagement.getStreamInfo; 0 when unavailable/unreachable.
    val dlqMessages: Long = 0,
    val dlqBytes: Long = 0,
    val error: String? = null,
)
