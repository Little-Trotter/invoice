package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.facturx.FacturXValidation
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage

/**
 * [PdfExportServiceImpl]'s default [FacturXEngine] collaborator. Never reached at
 * runtime: CDI always resolves the real `HttpFacturXEngineClient` bean (the sole implementation)
 * for the `@Inject` constructor. Exists purely so the many older tests that construct
 * [PdfExportServiceImpl] directly to exercise the Mustang path keep compiling without naming a
 * Factur-X collaborator they never call — and if one of them ever did call it by mistake, it
 * fails with the same `FACTURX-ENGINE-UNAVAILABLE` vocabulary a real unconfigured transport
 * would produce, not a `NullPointerException` or a silent empty result.
 */
internal object UnconfiguredFacturXEngine : FacturXEngine {
    override fun health(): Boolean = false

    override fun generateXml(invoice: InvoiceDto): FacturXEngineOutcome<ByteArray> =
        FacturXEngineOutcome.Failure(listOf(unconfiguredFinding()))

    override fun convert(basePdf: ByteArray, invoice: InvoiceDto): FacturXEngineOutcome<ByteArray> =
        FacturXEngineOutcome.Failure(listOf(unconfiguredFinding()))

    override fun validate(xml: ByteArray, invoice: InvoiceDto): FacturXValidation =
        FacturXValidation(KositOutcome.ERROR, listOf(unconfiguredFinding()))

    private fun unconfiguredFinding() = ValidationMessage(
        severity = "error",
        message = "FacturXEngine not configured (UnconfiguredFacturXEngine test default)",
        ruleId = "FACTURX-ENGINE-UNAVAILABLE",
        stage = "FACTURX",
        validator = "facturx-engine",
    )
}
