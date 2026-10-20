package de.littletrotter.application.invoice.port.inbound

/** Input port answering whether this deployment can run a local model for the document check at all. */
interface LlmAnalysisAvailabilityQueryPort {
    /** `false` on targets that structurally cannot run a local model. */
    val enabled: Boolean
}
