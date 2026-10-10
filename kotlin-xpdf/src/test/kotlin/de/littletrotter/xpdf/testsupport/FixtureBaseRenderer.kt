package de.littletrotter.xpdf.testsupport

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto

/**
 * A [BasePdfRendererPort] stand-in that returns a fixed, pre-rendered PDF regardless of
 * [InvoiceDto] — for tests whose subject is the export pipeline around the render step (job
 * routing, validation wiring, warm-up), not a layout engine itself. This module has no layout
 * engine on its classpath; see `src/test/resources/fixtures/README.md` for how the fixture bytes
 * were produced.
 */
class FixtureBaseRenderer : BasePdfRendererPort {

    override fun render(invoice: InvoiceDto): ByteArray = FIXTURE_BYTES

    private companion object {
        val FIXTURE_BYTES: ByteArray = requireNotNull(
            FixtureBaseRenderer::class.java.classLoader.getResourceAsStream("fixtures/base-invoice.pdf"),
        ) { "test fixture missing: fixtures/base-invoice.pdf" }.use { it.readBytes() }
    }
}
