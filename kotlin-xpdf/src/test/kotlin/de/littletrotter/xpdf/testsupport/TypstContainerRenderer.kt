package de.littletrotter.xpdf.testsupport

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.xpdf.impl.PaymentQrSetting
import de.littletrotter.xpdf.impl.TypstCompiler
import de.littletrotter.xpdf.impl.TypstInvoiceRenderer
import java.time.Instant

/**
 * A [BasePdfRendererPort] that renders through the pinned Typst CLI in a [TypstTestResource]
 * container — the real engine, not a fixture. Unit tests use `FixtureBaseRenderer` because they
 * exercise the export pipeline around the render step; E2E and conformance tests exist to prove
 * the real pipeline on the product's only layout engine (ADR-030), so they render for real.
 *
 * The container starts lazily on the first [render] rather than eagerly in a constructor or a
 * JUnit `@Container` field, so a test class can hold this renderer as a plain collaborator;
 * Testcontainers' own reaper stops the container at JVM shutdown, so callers need no `close()`.
 */
class TypstContainerRenderer : BasePdfRendererPort {

    private val container: TypstTestResource by lazy { TypstTestResource().also { it.start() } }
    // Parity and export checks compare the Typst base against a fixed reference; a GiroCode is
    // a Typst-only feature, so the reference renderer prints none.
    private val renderer = TypstInvoiceRenderer(
        TypstCompiler(),
        ClockTimeSource.fixedAt(FIXED_CREATION_TIMESTAMP),
        PaymentQrSetting.OFF,
    )

    override fun render(invoice: InvoiceDto): ByteArray {
        val request = renderer.compileRequest(invoice)
        val outcome = container.compile(request.templateSource, request.dataJson, request.creationTimestamp)
        check(outcome.exitCode == 0) {
            "typst ${container.installedVersion()} failed for ${invoice.invoiceNumber.value}:\n${outcome.stderr}"
        }
        return checkNotNull(outcome.pdfBytes)
    }

    private companion object {
        val FIXED_CREATION_TIMESTAMP: Instant = Instant.ofEpochSecond(1_790_000_000L)
    }
}
