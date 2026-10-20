package de.littletrotter.xpdf.impl

import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** [TypstInvoiceRenderer] against a mocked [TypstCompiler]: the compile request it builds and how it maps the two outcomes. */
class TypstInvoiceRendererTest {

    private val creation = Instant.ofEpochSecond(1_790_000_000L)
    private val invoice = TypstParityFixtures.cases().first().invoice

    private fun renderer(compiler: TypstCompiler, paymentQr: PaymentQrSetting = PaymentQrSetting.OFF) =
        TypstInvoiceRenderer(compiler, ClockTimeSource.fixedAt(creation), paymentQr)

    @Test
    fun `compile request carries the shipped template, the invoice's data json and the time source's instant`() {
        val request = renderer(mock()).compileRequest(invoice)

        assertTrue(request.templateSource.contains("json(\"data.json\")"), "template must read data.json")
        assertTrue(request.dataJson.contains("\"subject\":\"Rechnung Nr. PAR-2026-0001\""), request.dataJson.take(200))
        assertEquals(creation, request.creationTimestamp)
    }

    @Test
    fun `a successful compile returns the PDF bytes unchanged`() {
        val compiler: TypstCompiler = mock()
        val pdf = "%PDF-1.7 typst".toByteArray()
        whenever(compiler.compile(any())).thenReturn(TypstCompileResult.Success(pdf))

        val rendered = renderer(compiler).render(invoice)

        assertArrayEquals(pdf, rendered)
        val captor = argumentCaptor<TypstCompileRequest>()
        verify(compiler).compile(captor.capture())
        assertEquals(creation, captor.firstValue.creationTimestamp)
    }

    @Test
    fun `a failed compile surfaces reason and diagnostics as one exception`() {
        val compiler: TypstCompiler = mock()
        whenever(compiler.compile(any())).thenReturn(TypstCompileResult.Failure("Typst beendete sich mit Exitcode 1", "error: unknown variable: foo"))

        val ex = assertThrows(TypstRenderException::class.java) { renderer(compiler).render(invoice) }

        assertTrue(ex.message!!.startsWith("Typst beendete sich mit Exitcode 1"))
        assertTrue(ex.message!!.contains("unknown variable"))
        assertEquals("error: unknown variable: foo", ex.diagnostics)
    }

    @Test
    fun `a failure without diagnostics reads as the bare reason`() {
        assertEquals("Timeout", TypstRenderException("Timeout", "").message)
    }

    @Test
    fun `a missing bundled template is a loud failure naming the resource`() {
        val ex = assertThrows(TypstRenderException::class.java) { renderer(mock()).bundledTemplate { null } }
        assertTrue(ex.message!!.contains(TypstInvoiceRenderer.TEMPLATE_RESOURCE))
    }

    // ── GiroCode staging ────────────────────────────────────────────────────

    @Test
    fun `setting ON with an IBAN in EUR stages the code file and references it in data json`() {
        val request = renderer(mock(), PaymentQrSetting.ON).compileRequest(invoice)

        assertTrue(request.files.getValue("qr.svg").startsWith("<svg"), request.files["qr.svg"])
        assertTrue(request.dataJson.contains("\"qrFile\":\"qr.svg\""), request.dataJson)
    }

    @Test
    fun `setting OFF stages no file and leaves qrFile null regardless of the invoice`() {
        val request = renderer(mock(), PaymentQrSetting.OFF).compileRequest(invoice)

        assertTrue(request.files.isEmpty())
        assertTrue(request.dataJson.contains("\"qrFile\":null"), request.dataJson)
    }

    @Test
    fun `a cancellation invoice has no payment block, so ON still stages no code`() {
        val cancellation = TypstParityFixtures.invoice("PAR-2026-0099", (1..2).map { TypstParityFixtures.plainItem(it) }, cancellation = true)

        val request = renderer(mock(), PaymentQrSetting.ON).compileRequest(cancellation)

        assertTrue(request.files.isEmpty())
        assertTrue(request.dataJson.contains("\"qrFile\":null"), request.dataJson)
    }

    @Test
    fun `an invoice without an IBAN fails the EPC gate, so ON still stages no code`() {
        val noBank = TypstParityFixtures.invoice("PAR-2026-0098", (1..2).map { TypstParityFixtures.plainItem(it) }, withBank = false)

        val request = renderer(mock(), PaymentQrSetting.ON).compileRequest(noBank)

        assertTrue(request.files.isEmpty())
        assertTrue(request.dataJson.contains("\"qrFile\":null"), request.dataJson)
    }
}
