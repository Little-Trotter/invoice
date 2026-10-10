package de.littletrotter.ui.view.invoice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [resolveFacturXEngineEnabled] must require both the reference-data switch and the
 * engine actually being deployed: a DB switch left ON from an earlier deployment must not surface
 * Factur-X output on a stack without the engine. The function lives in
 * `InvoicePrintControls.kt` (moved unchanged from the former monolithic `InvoiceExportPanel.kt`).
 */
class InvoicePrintControlsFacturXDeploymentTest {

    @Test
    fun `switch on and deployed enables Factur-X`() {
        assertTrue(resolveFacturXEngineEnabled(gateEnabled = true, deployed = true))
    }

    @Test
    fun `switch on but not deployed disables Factur-X`() {
        assertFalse(resolveFacturXEngineEnabled(gateEnabled = true, deployed = false))
    }

    @Test
    fun `switch off never enables Factur-X, regardless of deployment`() {
        assertFalse(resolveFacturXEngineEnabled(gateEnabled = false, deployed = true))
        assertFalse(resolveFacturXEngineEnabled(gateEnabled = false, deployed = false))
    }
}
