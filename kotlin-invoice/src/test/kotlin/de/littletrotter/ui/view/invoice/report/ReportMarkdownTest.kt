package de.littletrotter.ui.view.invoice.report

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * PO feedback (2026-08-25) — the AI report embedded as section 3 of the combined Markdown is
 * arbitrary LLM-generated Markdown with its own `#`/`##` headings, code fences and tables. These
 * tests pin down that [ReportMarkdown.toHtml] never silently eats any of that content — a
 * rendering gap would look exactly like the "AI report not displayed" symptom the PO reported,
 * even once the section-2 wording bug (buildValidationReportMarkdown) is fixed.
 */
class ReportMarkdownTest {

    @Test
    fun `heading text inside the AI report section survives rendering`() {
        val markdown = """
            # Validierungsbericht — Rechnung 2026-0001
            ## 3. Verbesserungsspezifikation (KI)
            # Bewertung
            8/10
            ## Verbesserungsvorschläge
            BT-5 (Waehrungscode) pruefen.
        """.trimIndent()
        val html = ReportMarkdown.toHtml(markdown)
        assertTrue(html.contains("Bewertung"), "the AI report's own top-level heading must survive, got:\n$html")
        assertTrue(html.contains("Verbesserungsvorschläge"))
        assertTrue(html.contains("BT-5"))
    }

    @Test
    fun `code fence content inside the AI report section survives rendering`() {
        val markdown = """
            ## 3. Verbesserungsspezifikation (KI)
            Vorschlag:
            ```xml
            <BT-5>EUR</BT-5>
            ```
        """.trimIndent()
        val html = ReportMarkdown.toHtml(markdown)
        assertTrue(html.contains("<pre"), "a fenced code block must render as <pre>, got:\n$html")
        assertTrue(html.contains("BT-5"))
        assertTrue(html.contains("EUR"))
    }

    @Test
    fun `pipe table content inside the AI report section is not eaten even without GFM table support`() {
        // No GFM-table extension is registered (plain commonmark-java core only) — a table
        // renders as a paragraph rather than an HTML <table>, but the text itself must still be
        // present, never silently dropped.
        val markdown = """
            ## 3. Verbesserungsspezifikation (KI)
            | Priorität | Beschreibung |
            |---|---|
            | hoch | BT-5 fehlt |
        """.trimIndent()
        val html = ReportMarkdown.toHtml(markdown)
        assertTrue(html.contains("Priorität"), "table header text must survive, got:\n$html")
        assertTrue(html.contains("BT-5 fehlt"), "table cell text must survive, got:\n$html")
    }

    @Test
    fun `multiple top-level headings from KoSIT sections and the embedded AI report coexist`() {
        val markdown = """
            # Validierungsbericht — Rechnung 2026-0001
            ## 1. Normprüfung (KoSIT)
            bestanden
            ## 2. KI-Prüfung
            KI-Bericht vorhanden — siehe Abschnitt 3 (Verbesserungsspezifikation).
            ## 3. Verbesserungsspezifikation (KI)
            # Zusammenfassung
            Alles gut, kleine Anmerkung zu BT-5.
        """.trimIndent()
        val html = ReportMarkdown.toHtml(markdown)
        assertTrue(html.contains("Normprüfung"))
        assertTrue(html.contains("KI-Bericht vorhanden"))
        assertTrue(html.contains("Zusammenfassung"))
        assertTrue(html.contains("Alles gut, kleine Anmerkung zu BT-5."))
    }

    @Test
    fun `markdown formatting still renders as HTML after the XSS fix`() {
        val html = ReportMarkdown.toHtml("**bold** and _emphasis_")
        assertTrue(html.contains("<strong>bold</strong>"), "bold markdown must still render, got:\n$html")
        assertTrue(html.contains("<em>emphasis</em>"), "emphasis markdown must still render, got:\n$html")
    }

    @Test
    fun `raw HTML embedded in untrusted markdown is escaped, not passed through to the innerHTML sink`() {
        // UI-6: buildValidationReportMarkdown embeds invoice free text, and the AI report is raw
        // LLM output — both untrusted. The rendered HTML is written straight into
        // element.setProperty("innerHTML", …) in AiReportDialog / ValidationReportDialog, so any
        // raw HTML tag in the source must never survive as an executable tag.
        val html = ReportMarkdown.toHtml("""<img src=x onerror="alert(1)">""")
        assertTrue(
            html.contains("&lt;img"),
            "a raw <img onerror=...> tag must be escaped to &lt;img..., got:\n$html",
        )
        assertTrue(!html.contains("<img"), "the escaped output must not contain a live <img tag, got:\n$html")
    }

    @Test
    fun `a raw script tag in untrusted markdown does not survive as a tag`() {
        val html = ReportMarkdown.toHtml("before <script>alert('xss')</script> after")
        assertTrue(!html.contains("<script>"), "a raw <script> tag must not survive rendering, got:\n$html")
        assertTrue(html.contains("&lt;script&gt;"), "the script tag must be escaped instead, got:\n$html")
        assertTrue(html.contains("before"))
        assertTrue(html.contains("after"))
    }

    @Test
    fun `pipe table renders as html table instead of raw pipes`() {
        val markdown = """
            | Stufe | Ergebnis |
            |---|---|
            | KoSIT | OK |
        """.trimIndent()
        val html = ReportMarkdown.toHtml(markdown)
        assertTrue(html.contains("<table>"), "stage table must render as table, got:\n$html")
        assertTrue(html.contains("<th>Stufe</th>"))
        assertTrue(html.contains("<td>OK</td>"))
    }
}
