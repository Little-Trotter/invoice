package de.littletrotter.ui.view.invoice.report

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser as CmParser
import org.commonmark.renderer.html.HtmlRenderer as CmHtmlRenderer

/**
 * Markdown-to-HTML rendering used by the export panel's report dialogs
 * ([ValidationReportDialog], [AiReportDialog]).
 *
 * `escapeHtml(true)` is deliberate and must stay that way: the result of [toHtml] is written
 * straight into `element.setProperty("innerHTML", …)` in both dialogs, and the Markdown sources
 * are untrusted — one is raw LLM output, the other is built from invoice free text that ultimately
 * comes from user or imported data. With `escapeHtml(false)`, any raw HTML embedded in that text
 * (e.g. `<img onerror=…>`) would land executable in the admin's browser via the innerHTML sink.
 * Escaping raw HTML does not affect legitimate Markdown formatting (`**bold**`, headings, code
 * fences, …), which still renders normally. Pipe tables (the validation stage overview) render
 * as real tables through the GFM tables extension.
 */
internal object ReportMarkdown {
    private val extensions = listOf(TablesExtension.create())
    private val parser = CmParser.builder().extensions(extensions).build()
    private val renderer = CmHtmlRenderer.builder().extensions(extensions).escapeHtml(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown))
}
