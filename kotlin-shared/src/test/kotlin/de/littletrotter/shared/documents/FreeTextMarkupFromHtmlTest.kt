package de.littletrotter.shared.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** [FreeTextMarkup.fromHtml]: the jsoup allowlist import path and its round trip with [FreeTextMarkup.toHtml]. */
class FreeTextMarkupFromHtmlTest {
    // ── allowlist positives ───────────────────────────────────────────────

    @Test
    fun `each inline format and its tag aliases import to the matching marker`() {
        val cases =
            listOf(
                "<p><strong>x</strong></p>" to "**x**",
                "<p><b>x</b></p>" to "**x**",
                "<p><em>x</em></p>" to "*x*",
                "<p><i>x</i></p>" to "*x*",
                "<p><u>x</u></p>" to "++x++",
                "<p><s>x</s></p>" to "~~x~~",
                "<p><del>x</del></p>" to "~~x~~",
                "<p><strike>x</strike></p>" to "~~x~~",
            )
        cases.forEach { (html, expected) -> assertEquals(expected, FreeTextMarkup.fromHtml(html), html) }
    }

    @Test
    fun `br inside a paragraph starts a new line, an empty paragraph is one blank line`() {
        assertEquals("line1\nline2", FreeTextMarkup.fromHtml("<p>line1<br>line2</p>"))
        assertEquals("", FreeTextMarkup.fromHtml("<p><br></p>"))
        assertEquals("", FreeTextMarkup.fromHtml(""))
    }

    @Test
    fun `separate paragraphs become separate lines`() {
        assertEquals("first\nsecond", FreeTextMarkup.fromHtml("<p>first</p><p>second</p>"))
    }

    @Test
    fun `dom-nested lists reach level three, a fourth level flattens onto it`() {
        assertEquals(
            "- a\n    - b\n        - c",
            FreeTextMarkup.fromHtml("<ul><li>a<ul><li>b<ul><li>c</li></ul></li></ul></li></ul>"),
        )
        assertEquals(
            "- a\n    - b\n        - c\n        - d",
            FreeTextMarkup.fromHtml(
                "<ul><li>a<ul><li>b<ul><li>c<ul><li>d</li></ul></li></ul></li></ul></li></ul>",
            ),
        )
    }

    @Test
    fun `a paragraph inside a list item is unwrapped without blank lines`() {
        assertEquals("- a\n- b", FreeTextMarkup.fromHtml("<ul><li><p>a</p></li><li><p>b</p></li></ul>"))
        assertEquals("1. a\n2. b", FreeTextMarkup.fromHtml("<ol><li><p>a</p></li><li><p>b</p></li></ol>"))
    }

    @Test
    fun `nested lists with paragraph wrapped items keep their levels`() {
        val html = "<ul><li><p>a</p><ol><li><p>b</p><ul><li><p>c</p></li></ul></li></ol></li><li><p>d</p></li></ul>"
        assertEquals("- a\n    1. b\n        - c\n- d", FreeTextMarkup.fromHtml(html))
    }

    @Test
    fun `a second paragraph in one list item continues on its own line, an empty one adds nothing`() {
        assertEquals("- a\nb", FreeTextMarkup.fromHtml("<ul><li><p>a</p><p>b</p></li></ul>"))
        assertEquals("- a", FreeTextMarkup.fromHtml("<ul><li><p>a</p><p></p></li></ul>"))
    }

    @Test
    fun `formatting inside a paragraph wrapped list item survives`() {
        assertEquals(
            "- **a** *b*",
            FreeTextMarkup.fromHtml("<ul><li><p><strong>a</strong> <em>b</em></p></li></ul>"),
        )
    }

    @Test
    fun `all four marks nest in any order`() {
        assertEquals(
            "~~++***x***++~~",
            FreeTextMarkup.fromHtml("<p><s><u><strong><em>x</em></strong></u></s></p>"),
        )
        assertEquals(
            "***++~~x~~++***",
            FreeTextMarkup.fromHtml("<p><strong><em><u><s>x</s></u></em></strong></p>"),
        )
    }

    @Test
    fun `an empty paragraph is a blank line between paragraphs`() {
        assertEquals("a\n\nb", FreeTextMarkup.fromHtml("<p>a</p><p></p><p>b</p>"))
    }

    @Test
    fun `word and outlook paste junk is reduced to text and structure`() {
        val word =
            "<!--[if gte mso 9]><xml><o:OfficeDocumentSettings/></xml><![endif]-->" +
                "<p class=\"MsoNormal\" style=\"margin:0cm\"><span style=\"mso-bidi-font-size:11pt\">Hallo" +
                "<o:p></o:p></span></p>" +
                "<p class=\"MsoListParagraph\"><span style=\"mso-list:Ignore\">&middot;<span>&nbsp;&nbsp;</span></span>x</p>"
        val markup = FreeTextMarkup.fromHtml(word)
        assertEquals("Hallo", markup.lines().first())
        assertEquals(false, markup.contains("mso") || markup.contains("<") || markup.contains("MsoNormal"))
    }

    @Test
    fun `word list paste with class and style attributes imports as a plain list`() {
        val html = "<ul style=\"margin-top:0cm\"><li class=\"MsoListParagraph\" style=\"mso-list:l0\"><span lang=\"DE\">a</span></li></ul>"
        assertEquals("- a", FreeTextMarkup.fromHtml(html))
    }

    // ── allowlist negatives: stripped structure/attributes, text kept where applicable ─────────

    @Test
    fun `script and its text are discarded entirely`() {
        assertEquals("before\nafter", FreeTextMarkup.fromHtml("<p>before</p><script>alert(1)</script><p>after</p>"))
    }

    @Test
    fun `a style attribute is discarded, the element's text is kept`() {
        assertEquals("hi", FreeTextMarkup.fromHtml("<p style=\"color:red\">hi</p>"))
    }

    @Test
    fun `an img tag is discarded, it contributes no text of its own`() {
        assertEquals("ab", FreeTextMarkup.fromHtml("<p>a<img src=\"x\">b</p>"))
    }

    @Test
    fun `table markup is discarded, its cell text is kept`() {
        assertEquals("cell", FreeTextMarkup.fromHtml("<table><tr><td>cell</td></tr></table>"))
    }

    @Test
    fun `a link's href is discarded, its text is kept`() {
        assertEquals("link text", FreeTextMarkup.fromHtml("<p><a href=\"http://x\">link text</a></p>"))
    }

    @Test
    fun `an iframe is discarded`() {
        assertEquals("after", FreeTextMarkup.fromHtml("<iframe src=\"x\"></iframe>after"))
    }

    @Test
    fun `an onclick attribute is discarded, the element's text is kept`() {
        assertEquals("click", FreeTextMarkup.fromHtml("<p onclick=\"evil()\">click</p>"))
    }

    @Test
    fun `a comment is discarded`() {
        assertEquals("ab", FreeTextMarkup.fromHtml("<p>a<!-- comment -->b</p>"))
    }

    @Test
    fun `a heading is treated as its own paragraph line`() {
        assertEquals("Title\nbody", FreeTextMarkup.fromHtml("<h1>Title</h1><p>body</p>"))
    }

    @Test
    fun `span and div wrappers are unwrapped, their text merges with no line break`() {
        assertEquals("outerinnertext", FreeTextMarkup.fromHtml("<div>outer<span>inner</span>text</div>"))
    }

    // ── round trip against toHtml, canonical generator ──────────────────────

    private val words =
        listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "invoice", "betrag", "kunde", "posten")
    private val formats = listOf(FreeTextFormat.BOLD, FreeTextFormat.ITALIC, FreeTextFormat.UNDERLINE, FreeTextFormat.STRIKE)

    private fun randomWord(random: Random): String = words[random.nextInt(words.size)]

    private fun randomFormat(
        random: Random,
        exclude: FreeTextFormat? = null,
    ): FreeTextFormat {
        val choices = formats.filter { it != exclude }
        return choices[random.nextInt(choices.size)]
    }

    // Plain word, a single formatted word, or an outer format wrapping an inner-formatted word —
    // every shape `toHtml`'s recursive inline scanner (and its `fromHtml` mirror) supports.
    private fun randomSpan(random: Random): String =
        when (random.nextInt(3)) {
            0 -> randomWord(random)
            1 -> {
                val format = randomFormat(random)
                "${format.marker}${randomWord(random)}${format.marker}"
            }
            else -> {
                val outer = randomFormat(random)
                val inner = randomFormat(random, exclude = outer)
                val content = "${randomWord(random)} ${inner.marker}${randomWord(random)}${inner.marker} ${randomWord(random)}"
                "${outer.marker}$content${outer.marker}"
            }
        }

    private fun randomTextLine(random: Random): String {
        val tokenCount = 1 + random.nextInt(4)
        return (0 until tokenCount).joinToString(" ") { randomSpan(random) }
    }

    // Every numbered item is generated as "1. " (the only number HTML carries) and the
    // whole text is then `renumber`ed into canonical form; no line carries stray whitespace.
    private fun randomListLine(
        random: Random,
        level: Int,
    ): String {
        val marker = if (random.nextBoolean()) "1. " else "- "
        return " ".repeat(FreeTextMarkup.INDENT_SPACES * level) + marker + randomTextLine(random)
    }

    private fun randomMarkup(random: Random): String {
        val lineCount = 1 + random.nextInt(6)
        return (0 until lineCount).joinToString("\n") {
            when (random.nextInt(4)) {
                0 -> ""
                1 -> randomListLine(random, random.nextInt(FreeTextMarkup.MAX_LEVEL + 1))
                2 -> " ".repeat(FreeTextMarkup.INDENT_SPACES * (1 + random.nextInt(FreeTextMarkup.MAX_LEVEL))) + randomTextLine(random)
                else -> randomTextLine(random)
            }
        }
    }

    @Test
    fun `toHtml and fromHtml round trip for 200 generated canonical markup texts`() {
        val random = Random(42)
        repeat(200) {
            val markup = FreeTextMarkup.renumber(randomMarkup(random))
            val html = FreeTextMarkup.toHtml(markup)
            val imported = FreeTextMarkup.fromHtml(html)
            assertEquals(markup, imported, "fromHtml(toHtml(m)) == m for ${markup.replace("\n", "\\n")}")
            assertEquals(
                html,
                FreeTextMarkup.toHtml(imported),
                "toHtml(fromHtml(toHtml(m))) == toHtml(m) for ${markup.replace("\n", "\\n")}",
            )
        }
    }

    // ── edge whitespace and indented plain lines ──────────────────────────

    @Test
    fun `whitespace at the edge of a formatted element stays outside the markers`() {
        assertEquals("**bold** text", FreeTextMarkup.fromHtml("<p><strong>bold </strong>text</p>"))
        assertEquals("a b", FreeTextMarkup.fromHtml("<p>a<em> </em>b</p>"))
        assertEquals("x  ++y++  z", FreeTextMarkup.fromHtml("<p>x <u> y </u> z</p>"))
        assertEquals("***text*** x", FreeTextMarkup.fromHtml("<p><strong><em>text </em></strong>x</p>"))
        assertEquals("**a**\n**b**", FreeTextMarkup.fromHtml("<p><strong> a<br>b </strong></p>"))
    }

    @Test
    fun `an indented plain line renders without a mark and imports back unchanged`() {
        val html = FreeTextMarkup.toHtml("    x")
        assertEquals("<div class=\"lt-ft\"><div class=\"lt-ft-line lt-ft-l1 lt-ft-plain\">x</div></div>", html)
        assertEquals("    x", FreeTextMarkup.fromHtml(html))
    }

    // ── list kind from tag only ───────────────────────────────────────────

    @Test
    fun `plain ul and ol keep their tag meaning`() {
        assertEquals("- a", FreeTextMarkup.fromHtml("<ul><li>a</li></ul>"))
        assertEquals("1. a", FreeTextMarkup.fromHtml("<ol><li>a</li></ol>"))
    }

    @Test
    fun `list and indent attributes from foreign editors have no effect`() {
        assertEquals("- a\n- b", FreeTextMarkup.fromHtml("<ul><li data-kind=\"ordered\">a</li><li class=\"x-indent-2\">b</li></ul>"))
        assertEquals("a", FreeTextMarkup.fromHtml("<p class=\"x-indent-3\">a</p>"))
    }

    @Test
    fun `bullet and numbered nested lists round trip through toHtml and fromHtml`() {
        val markup = "- a\n    - b\n        - c\n1. d\n    1. e"
        assertEquals(markup, FreeTextMarkup.fromHtml(FreeTextMarkup.toHtml(markup)))
    }
}
