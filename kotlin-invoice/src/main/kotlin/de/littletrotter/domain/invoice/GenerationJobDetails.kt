package de.littletrotter.domain.invoice

/** Everything a new [GenerationJob] is created with, besides its invoice and creation moment. */
data class GenerationJobDetails(
    val jobType: String,
    val trigger: GenerationJobTrigger,
    val lane: DocumentJobLane,
    val llmOptimize: Boolean,
    val issued: Boolean,
    val correlationId: String?,
)
