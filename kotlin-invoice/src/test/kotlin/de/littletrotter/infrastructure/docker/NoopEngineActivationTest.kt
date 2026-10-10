package de.littletrotter.infrastructure.docker

import de.littletrotter.domain.invoice.DocumentEngine
import org.junit.jupiter.api.Test

/**
 * Acceptance criterion — "ohne Proxy-Konfiguration passiert nichts
 * außer dem Stufe-1-Hinweis": this test demonstrates the no-op path is genuinely inert by simply
 * calling both methods for both engines and letting them return normally — a no-op that somehow
 * threw or blocked would fail this test by not completing.
 */
class NoopEngineActivationTest {

    @Test
    fun `ensureRunning and releaseIdle do nothing and never throw, for either engine`() {
        val activation = NoopEngineActivation()

        DocumentEngine.entries.forEach { engine ->
            activation.ensureRunning(engine)
            activation.releaseIdle(engine)
        }
    }
}
