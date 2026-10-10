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

/** [QuoteTypstRenderer] against a mocked [TypstCompiler]: the compile request it builds and how it maps the outcomes. */
class QuoteTypstRendererTest {

    private val creation = Instant.ofEpochSecond(1_790_000_000L)
    private val payload = QuoteFixtures.payload()

    private fun renderer(compiler: TypstCompiler) = QuoteTypstRenderer(compiler, ClockTimeSource.fixedAt(creation))

    @Test
    fun `compile request carries the shipped offer template, the offer's data json and the time source's instant`() {
        val request = renderer(mock()).compileRequest(payload)

        assertTrue(request.templateSource.contains("json(\"data.json\")"))
        assertTrue(request.templateSource.contains("data.quoteInfo"))
        assertTrue(request.dataJson.contains("\"subject\":\"Angebot Nr. AN-2026-00007 – Sanierung Badezimmer\""), request.dataJson.take(200))
        assertEquals(creation, request.creationTimestamp)
    }

    @Test
    fun `a successful compile returns the PDF bytes unchanged`() {
        val compiler: TypstCompiler = mock()
        val pdf = "%PDF-1.7 typst".toByteArray()
        whenever(compiler.compile(any())).thenReturn(TypstCompileResult.Success(pdf))

        assertArrayEquals(pdf, renderer(compiler).render(payload))
        val captor = argumentCaptor<TypstCompileRequest>()
        verify(compiler).compile(captor.capture())
        assertEquals(creation, captor.firstValue.creationTimestamp)
    }

    @Test
    fun `a failed compile surfaces reason and diagnostics as one exception`() {
        val compiler: TypstCompiler = mock()
        whenever(compiler.compile(any())).thenReturn(TypstCompileResult.Failure("exit 1", "error: unknown variable"))

        val ex = assertThrows(TypstRenderException::class.java) { renderer(compiler).render(payload) }

        assertTrue(ex.message!!.contains("unknown variable"))
    }

    @Test
    fun `a missing bundled template is a loud failure naming the resource`() {
        val ex = assertThrows(TypstRenderException::class.java) { renderer(mock()).bundledTemplate { null } }
        assertTrue(ex.message!!.contains(QuoteTypstRenderer.TEMPLATE_RESOURCE))
    }

    @Test
    fun `data json of an offer has no payment section`() {
        assertTrue(!renderer(mock()).dataJson(payload).contains("\"payment\""))
    }
}
