package de.littletrotter.shared

interface KositValidatorPort {
    /**
     * Validate the provided XML and return a KositValidationResult.
     * Implementations should respect timeoutMs and provide a concise result.
     */
    fun validate(xml: String, timeoutMs: Long = 30_000): KositValidationResult
}
