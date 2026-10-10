package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort

/**
 * Test double returning a constant result, for tests that construct
 * [PdfExportServiceImpl] directly without wiring a real [KositValidatorPort].
 */
internal class StubKositValidator(private val valid: Boolean) : KositValidatorPort {
    override fun validate(xml: String, timeoutMs: Long): KositValidationResult =
        KositValidationResult(
            status = if (valid) KositOutcome.VALID else KositOutcome.INVALID,
            summary = if (valid) "Stub" else "Stub: invalid"
        )
}
