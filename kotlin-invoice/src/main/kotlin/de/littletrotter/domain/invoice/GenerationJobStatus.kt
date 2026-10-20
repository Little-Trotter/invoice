package de.littletrotter.domain.invoice

enum class GenerationJobStatus {
    PENDING,
    DELEGATED,
    RUNNING,
    COMPLETED,
    FAILED
}
