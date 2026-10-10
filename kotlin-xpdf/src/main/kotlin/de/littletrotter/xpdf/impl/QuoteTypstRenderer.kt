package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Typst renderer of the offer document: builds the preformatted [TypstQuoteData] from the offer
 * payload, writes it as `data.json` and compiles `standard-quote.typ` through the sandboxed
 * [TypstCompiler]. Only the shipped template ever renders here — layouts are product code, not
 * user uploads. Unlike the invoice renderer it does not implement the invoice-typed base renderer
 * port, which would make two beans compete for that port.
 *
 * The creation timestamp comes from the injected [TimeSource], so a fixed clock yields
 * byte-identical PDFs for identical offers. A compile failure is thrown as [TypstRenderException]
 * with the compiler's diagnostics, never a half-rendered document.
 */
@ApplicationScoped
class QuoteTypstRenderer @Inject constructor(
    private val compiler: TypstCompiler,
    private val time: TimeSource,
) {

    private val reportBuilder = QuoteReportBuilder()
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    /** Read once: the template is a classpath resource that cannot change while the worker runs. */
    private val template: String by lazy { bundledTemplate() }

    fun render(payload: QuoteDocumentPayload): ByteArray =
        when (val result = compiler.compile(compileRequest(payload))) {
            is TypstCompileResult.Success -> result.pdfBytes
            is TypstCompileResult.Failure -> throw TypstRenderException(result.reason, result.diagnostics)
        }

    /** Template, data and timestamp of one run. */
    fun compileRequest(payload: QuoteDocumentPayload): TypstCompileRequest = TypstCompileRequest(
        templateSource = template,
        dataJson = dataJson(payload),
        creationTimestamp = time.instant(),
    )

    /** The `data.json` this offer renders from; the layout tests read it, nothing else does. */
    fun dataJson(payload: QuoteDocumentPayload): String = mapper.writeValueAsString(reportBuilder.build(payload))

    /** `internal` with a defaulted lookup so a test can exercise the "template missing from the
     *  jar" branch without touching the real classpath. */
    internal fun bundledTemplate(
        resourceLookup: (String) -> java.io.InputStream? = { path -> javaClass.getResourceAsStream(path) },
    ): String {
        val resource = resourceLookup(TEMPLATE_RESOURCE)
            ?: throw TypstRenderException("Typst template $TEMPLATE_RESOURCE not found", "")
        return resource.bufferedReader().use { it.readText() }
    }

    companion object {
        const val TEMPLATE_RESOURCE = "/typst/standard-quote.typ"
    }
}
