package de.littletrotter.ui.view.invoice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [isMlxConfigured], the export panel's gate for offering the LLM analysis: endpoint,
 * key and a model are all required; `MLX_MODEL` wins over the first entry of `MLX_MODELS`.
 * The function lives in `LlmCheckAvailability.kt` (moved unchanged from the former
 * monolithic `InvoiceExportPanel.kt`).
 */
class LlmCheckAvailabilityMlxConfigTest {

    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? = mapOf(*pairs)::get

    private val endpoint = "MLX_ENDPOINT" to "http://localhost:8020/v1"
    private val key = "MLX_API_KEY" to "secret"

    @Test
    fun `happy path - endpoint, key and a single model enable the analysis`() {
        assertTrue(isMlxConfigured(env(endpoint, key, "MLX_MODEL" to "qwen")))
    }

    @Test
    fun `boundary - a blank model falls back to the first entry of the model list`() {
        assertTrue(isMlxConfigured(env(endpoint, key, "MLX_MODEL" to " ", "MLX_MODELS" to " qwen , llama")))
        assertTrue(isMlxConfigured(env(endpoint, key, "MLX_MODELS" to "qwen")))
        assertFalse(isMlxConfigured(env(endpoint, key, "MLX_MODELS" to " ,llama")), "a blank first list entry is no model")
        assertFalse(isMlxConfigured(env(endpoint, key)))
    }

    @Test
    fun `boundary - a missing or blank endpoint disables the analysis`() {
        assertFalse(isMlxConfigured(env(key, "MLX_MODEL" to "qwen")))
        assertFalse(isMlxConfigured(env("MLX_ENDPOINT" to "  ", key, "MLX_MODEL" to "qwen")))
    }

    @Test
    fun `boundary - a missing or blank api key disables the analysis`() {
        assertFalse(isMlxConfigured(env(endpoint, "MLX_MODEL" to "qwen")))
        assertFalse(isMlxConfigured(env(endpoint, "MLX_API_KEY" to "", "MLX_MODEL" to "qwen")))
    }
}
