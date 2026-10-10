package de.littletrotter.shared.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Random

/** T2 — [FreeTextMarkup]: the single free-text syntax definition and its three renderings. */
class FreeTextMarkupTest {

    /** Single-line sources: expected is the content of the one rendered line. */
    private fun html(vararg cases: Pair<String, String>) {
        cases.forEach { (source, expected) ->
            assertEquals(
                "<div class=\"lt-ft\"><div class=\"lt-ft-line\">$expected</div></div>",
                FreeTextMarkup.toHtml(source),
                source,
            )
        }
    }

    // ── inline formats ────────────────────────────────────────────────────

    @Test
    fun `every inline format converts in both renderings`() {
        val cases =
            listOf(
                "**x**" to "<b>x</b>",
                "*x*" to "<i>x</i>",
                "***x***" to "<b><i>x</i></b>",
                "++x++" to "<u>x</u>",
                "~~x~~" to "<s>x</s>",
            )
        cases.forEach { (source, html) ->
            assertEquals("x", FreeTextMarkup.toPlain(source), source)
            assertEquals(
                "<div class=\"lt-ft\"><div class=\"lt-ft-line\">$html</div></div>",
                FreeTextMarkup.toHtml(source),
            )
        }
    }

    @Test
    fun `formats nest without crossing`() {
        html(
            "**a *b* c**" to "<b>a <i>b</i> c</b>",
            "*a **b** c*" to "<i>a <b>b</b> c</i>",
            "++a ~~b~~ c++" to "<u>a <s>b</s> c</u>",
            "**a** and *b*" to "<b>a</b> and <i>b</i>",
        )
    }

    @Test
    fun `the crossing example of the old converter no longer crosses`() {
        html(
            "**a *b** c*" to "<b>a *b</b> c*",
            "***x***" to "<b><i>x</i></b>",
        )
    }

    @Test
    fun `a format never nests in itself`() {
        html("***a *b* c***" to "<b><i>a *b* c</i></b>")
    }

    @Test
    fun `plain and worked prose pass through`() {
        html(
            "Vielen Dank fuer Ihren Auftrag." to "Vielen Dank fuer Ihren Auftrag.",
            "**a** and *unmatched" to "<b>a</b> and *unmatched",
        )
    }

    // ── border cases ──────────────────────────────────────────────────────

    @Test
    fun `unmatched openers stay literal`() {
        html(
            "**abc" to "**abc",
            "**abc*" to "**abc*",
            "~~x" to "~~x",
            "+++" to "+++",
            "**unclosed bold" to "**unclosed bold",
            "***x**" to "***x**",
        )
    }

    @Test
    fun `whitespace flanked markers stay literal`() {
        html(
            "5 * 3 * 2" to "5 * 3 * 2",
            "5 * 3 = 15" to "5 * 3 = 15",
            "C++ und D++ " to "C++ und D++ ",
            "** x **" to "** x **",
            "**x **" to "**x **",
        )
    }

    @Test
    fun `a marker never spans a line break`() {
        assertEquals(2, FreeTextMarkup.toLines("**a\nb**").size)
        assertEquals("**a\nb**", FreeTextMarkup.toPlain("**a\nb**"))
    }

    @Test
    fun `empty and marker-only sources`() {
        assertEquals("", FreeTextMarkup.toPlain(""))
        html("****" to "****", "++++" to "++++", "~~~~" to "~~~~")
    }

    @Test
    fun `special characters are escaped before anything else`() {
        html(
            "Kaufmanns-Und & Co." to "Kaufmanns-Und &amp; Co.",
            "Literal <b> tag" to "Literal &lt;b&gt; tag",
            "<b>bold</b>" to "&lt;b&gt;bold&lt;/b&gt;",
            "<script>\"x\"</script>" to "&lt;script&gt;&quot;x&quot;&lt;/script&gt;",
            "**a & <b>**" to "<b>a &amp; &lt;b&gt;</b>",
        )
        assertEquals(
            "<div class=\"lt-ft\"><div class=\"lt-ft-line\">&lt;script&gt;&quot;x&quot; &amp;</div></div>",
            FreeTextMarkup.toHtml("<script>\"x\" &"),
        )
    }

    @Test
    fun `carriage returns are normalised`() {
        assertEquals(2, FreeTextMarkup.toLines("a\r\nb").size)
        assertEquals(2, FreeTextMarkup.toLines("a\rb").size)
        assertEquals("a\nb", FreeTextMarkup.toPlain("a\r\nb"))
    }

    @Test
    fun `very long and maximum-size inputs finish quickly`() {
        val oneLine = "**a** ".repeat(FreeTextMarkup.MAX_LENGTH / 6)
        val openersOnly = "**a ".repeat(FreeTextMarkup.MAX_LENGTH / 4)
        val noise = "* + ~ a b - 1.\n".repeat(FreeTextMarkup.MAX_LENGTH / 15)
        listOf(oneLine, openersOnly, noise, "x".repeat(100_000)).forEach { source ->
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                FreeTextMarkup.toPlain(source)
                FreeTextMarkup.toHtml(source)
            }
        }
    }

    @Test
    fun `a list marker inside bold is not a list`() {
        html("**- x**" to "<b>- x</b>")
        assertEquals("- x", FreeTextMarkup.toPlain("**- x**"))
    }

    // ── plain / html ──────────────────────────────────────────────────────

    @Test
    fun `plain keeps list prefixes and indentation as typed`() {
        val source = "**Wichtig** ++unterstrichen++ ~~alt~~\n1. Eins\n    - Unterpunkt"
        assertEquals("Wichtig unterstrichen alt\n1. Eins\n    - Unterpunkt", FreeTextMarkup.toPlain(source))
    }

    @Test
    fun `plain leaves special characters unescaped`() {
        assertEquals("a & <b> c", FreeTextMarkup.toPlain("a & <b> **c**"))
    }

    @Test
    fun `html renders lines list marks and blank lines`() {
        assertEquals(
            "<div class=\"lt-ft\">" +
                "<div class=\"lt-ft-line\"><b>a</b></div>" +
                "<div class=\"lt-ft-line\"><br></div>" +
                "<div class=\"lt-ft-line lt-ft-l0\"><span class=\"lt-ft-mark\">•</span>x</div>" +
                "<div class=\"lt-ft-line lt-ft-l1\"><span class=\"lt-ft-mark\">2.</span>y</div>" +
                "</div>",
            FreeTextMarkup.toHtml("**a**\n\n- x\n    2. y"),
        )
        assertEquals("<div class=\"lt-ft\"></div>", FreeTextMarkup.toHtml(""))
    }

    @Test
    fun `numbered items count on from the first typed number per level and run`() {
        val source = "1. a\n1. b\n    1. c\n    1. d\n1. e\n\n1. f\n3. g\n- h\n3. i\n1. j"
        assertEquals(
            listOf("1.", "2.", "1.", "2.", "3.", null, "1.", "2.", "•", "3.", "4."),
            FreeTextMarkup.toLines(source).map { it.mark },
        )
    }

    @Test
    fun `plain text and html show the counted numbers`() {
        assertEquals("1. a\n2. b\n    1. c", FreeTextMarkup.toPlain("1. a\n1. b\n    1. c"))
        val html = FreeTextMarkup.toHtml("1. a\n1. b")
        assertTrue(html.contains("<span class=\"lt-ft-mark\">2.</span>b"), html)
    }

    @Test
    fun `renumber stores the counted numbers and is idempotent`() {
        val stored = "intro\r\n1. a\n1. **b**\n    1. c\n- d\n7. e"
        val expected = "intro\n1. a\n2. **b**\n    1. c\n- d\n7. e"
        assertEquals(expected, FreeTextMarkup.renumber(stored))
        assertEquals(expected, FreeTextMarkup.renumber(expected))
        assertEquals("", FreeTextMarkup.renumber(""))
    }

    @Test
    fun `html emits no attribute other than class`() {
        val html = FreeTextMarkup.toHtml("**a** \"q\" <img src=x onerror=y>\n- <a href=z>")
        assertFalse(Regex("<[a-z]+ (?!class=)").containsMatchIn(html), html)
        assertFalse(html.contains("<img"), html)
    }

    // ── format completeness ───────────────────────────────────────────────

    @Test
    fun `list formats have no inline marker and inline formats have one`() {
        assertEquals(
            listOf(FreeTextFormat.BULLET_LIST, FreeTextFormat.NUMBERED_LIST),
            FreeTextFormat.entries.filter { it.marker == null },
        )
    }

    // ── structural property ───────────────────────────────────────────────

    private val alphabet = listOf('*', '+', '~', 'a', 'b', ' ', '\n', '<', '&', '-', '1', '.')
    private val listPrefix = Regex("^( *)(- |\\d{1,3}\\. )")

    private fun randomSources(): List<String> {
        val random = Random(20260918L)
        return List(2000) { String(CharArray(random.nextInt(25)) { alphabet[random.nextInt(alphabet.size)] }) }
    }

    @Test
    fun `random sources always consume exactly the markup delimiters`() {
        randomSources().forEach { source ->
            val out = FreeTextMarkup.toHtml(source)
            // No list line here: list prefixes are re-rendered (glyphs, NBSP indent) and would skew the count.
            if (source.split('\n').none { listPrefix.containsMatchIn(it) }) {
                val consumed =
                    2 * (
                        2 * Regex("<b>").findAll(out).count() + Regex("<i>").findAll(out).count() +
                            2 * Regex("<u>").findAll(out).count() + 2 * Regex("<s>").findAll(out).count()
                    )
                val plain = FreeTextMarkup.toPlain(source)
                assertEquals(source.length - consumed, plain.length, "plain of ${source.replace("\n", "\\n")}")
            }
        }
    }

    @Test
    fun `the html rendering of random sources is well-nested too`() {
        randomSources().forEach { source ->
            val html = FreeTextMarkup.toHtml(source)
            val stack = ArrayDeque<String>()
            Regex("</?(b|i|u|s|div|span)[ >]").findAll(html).forEach { m ->
                val name = m.groupValues[1]
                if (m.value.startsWith("</")) {
                    assertEquals(name, stack.removeLastOrNull(), "in $html")
                } else {
                    stack.addLast(name)
                }
            }
            assertTrue(stack.isEmpty(), html)
        }
    }

    @Test
    fun `parseListLine splits bullet and numbered lines and rejects plain text`() {
        assertEquals(FreeTextListLine(0, false, "eins"), FreeTextMarkup.parseListLine("- eins"))
        assertEquals(FreeTextListLine(4, true, "zwei"), FreeTextMarkup.parseListLine("    12. zwei"))
        assertEquals(FreeTextListLine(0, false, ""), FreeTextMarkup.parseListLine("- "))
        assertEquals(null, FreeTextMarkup.parseListLine("-eins"))
        assertEquals(null, FreeTextMarkup.parseListLine("plain"))
        assertEquals(null, FreeTextMarkup.parseListLine(""))
    }

    // ── toLines: the structured rendering for a layout engine without a tag parser ──

    private fun run(text: String, vararg formats: FreeTextFormat) = FreeTextRun(text, formats.toSet())

    @Test
    fun `toLines yields one line per source line with merged runs and the same marks as toHtml`() {
        val lines = FreeTextMarkup.toLines("**a** *b*\n- c\n    - ++d++ e\n3. ~~f~~")
        assertEquals(
            listOf(
                FreeTextLine(0, null, listOf(run("a", FreeTextFormat.BOLD), run(" "), run("b", FreeTextFormat.ITALIC))),
                FreeTextLine(0, "•", listOf(run("c"))),
                FreeTextLine(1, "–", listOf(run("d", FreeTextFormat.UNDERLINE), run(" e"))),
                FreeTextLine(0, "3.", listOf(run("f", FreeTextFormat.STRIKE))),
            ),
            lines,
        )
    }

    @Test
    fun `toLines nests formats as sets and keeps unmatched markers literal`() {
        assertEquals(
            listOf(FreeTextLine(0, null, listOf(run("x ", FreeTextFormat.BOLD), run("y", FreeTextFormat.BOLD, FreeTextFormat.ITALIC), run(" z", FreeTextFormat.BOLD)))),
            FreeTextMarkup.toLines("**x *y* z**"),
        )
        assertEquals(listOf(FreeTextLine(0, null, listOf(run("**a b* c")))), FreeTextMarkup.toLines("**a b* c"))
        assertEquals(listOf(FreeTextLine(0, null, listOf(run("a&<>b")))), FreeTextMarkup.toLines("a&<>b"))
    }

    @Test
    fun `toLines represents empty lines as lines without runs and clamps list levels`() {
        assertEquals(listOf(FreeTextLine(0, null, emptyList())), FreeTextMarkup.toLines(""))
        val lines = FreeTextMarkup.toLines("a\n\n            - deep\r\nb")
        assertEquals(4, lines.size)
        assertEquals(emptyList<FreeTextRun>(), lines[1].runs)
        assertEquals(FreeTextLine(2, "›", listOf(run("deep"))), lines[2])
        assertEquals(FreeTextLine(0, null, listOf(run("b"))), lines[3])
    }

    @Test
    fun `toLines and toPlain agree on the visible text of every line`() {
        val source = "**Wichtig:** *jetzt*\n- ++eins++\n    1. ~~zwei~~ drei\nEnde & Aus"
        val fromLines = FreeTextMarkup.toLines(source).map { line -> line.runs.joinToString("") { it.text } }
        val fromPlain = FreeTextMarkup.toPlain(source).split('\n').map { it.trimStart().removePrefix("- ").replaceFirst(Regex("^\\d+\\. "), "") }
        assertEquals(fromPlain, fromLines)
    }

    // ── literal ───────────────────────────────────────────────────────────

    private val literalSamples = listOf(
        "",
        "plain",
        "*",
        "**",
        "***",
        "++",
        "~~",
        "*a*",
        "**a**",
        "***a***",
        "++a++",
        "~~a~~",
        "a * b * c",
        "- x",
        "1. x",
        "  - nested",
        "a\n- x\n12. y\n    - z",
        "mix *a* **b** ++c++ ~~d~~\n- e",
    )

    @Test
    fun `literal text renders as itself in every rendering`() {
        literalSamples.forEach { x ->
            val guarded = FreeTextMarkup.literal(x)
            assertEquals(x, FreeTextMarkup.toPlain(guarded), x)
            val lines = FreeTextMarkup.toLines(guarded)
            assertEquals(x.split('\n'), lines.map { l -> l.runs.joinToString("") { it.text } }, x)
            assertTrue(lines.all { it.mark == null && it.runs.all { r -> r.formats.isEmpty() } }, x)
            val html = FreeTextMarkup.toHtml(guarded)
            assertFalse(html.contains("<b>") || html.contains("<i>") || html.contains("<u>") || html.contains("<s>"), x)
            assertFalse(html.contains("lt-ft-mark"), x)
            assertFalse(html.contains('\u200B'), x)
        }
    }

    @Test
    fun `literal text cannot pair with markers around it`() {
        assertEquals("*a* b", FreeTextMarkup.toPlain("*" + FreeTextMarkup.literal("a*") + " b"))
        assertEquals(
            listOf(FreeTextRun("x ", emptySet()), FreeTextRun("y", setOf(FreeTextFormat.BOLD))),
            FreeTextMarkup.toLines(FreeTextMarkup.literal("x ") + "**y**").single().runs,
        )
        // The template's own pair formats the value; the value's star stays a visible star.
        assertEquals(
            "<div class=\"lt-ft\"><div class=\"lt-ft-line\"><i>*</i></div></div>",
            FreeTextMarkup.toHtml("*" + FreeTextMarkup.literal("*") + "*"),
        )
    }

    @Test
    fun `literal leaves text without markup characters unchanged`() {
        assertEquals("Max Mustermann, Berlin", FreeTextMarkup.literal("Max Mustermann, Berlin"))
        assertEquals("1.5 und 2-3", FreeTextMarkup.literal("1.5 und 2-3"))
    }

    // ── literal text through the HTML import ─────────────────────────────

    private fun noFormats(markup: String) =
        FreeTextMarkup.toLines(markup).all { it.mark == null && it.runs.all { r -> r.formats.isEmpty() } }

    @Test
    fun `marker characters in imported HTML text stay literal`() {
        val imported = FreeTextMarkup.fromHtml("<p>*A* **B** ++C++ ~~D~~</p>")

        assertEquals("*A* **B** ++C++ ~~D~~", FreeTextMarkup.toPlain(imported))
        assertTrue(noFormats(imported), imported)
    }

    @Test
    fun `a list-like text line in imported HTML is not a list`() {
        val imported = FreeTextMarkup.fromHtml("<p>- x</p><p>1. y</p>")

        assertEquals("- x\n1. y", FreeTextMarkup.toPlain(imported))
        assertTrue(noFormats(imported), imported)
    }

    @Test
    fun `tags still format while their text stays literal`() {
        val bold = FreeTextMarkup.toLines(FreeTextMarkup.fromHtml("<p><strong>*x*</strong></p>")).single().runs

        assertEquals(listOf(FreeTextRun("*x*", setOf(FreeTextFormat.BOLD))), bold)
        val list = FreeTextMarkup.toLines(FreeTextMarkup.fromHtml("<ul><li>- a</li></ul>")).single()
        assertEquals("•", list.mark)
        assertEquals("- a", list.runs.joinToString("") { it.text })
    }

    @Test
    fun `editor round trip is stable after one pass`() {
        val sources = listOf(
            "**fett** und *kursiv*\n- a\n    - b\n1. c",
            FreeTextMarkup.literal("*A* **B** ++C++ ~~D~~\n- x\n1. y"),
            "**Hallo** " + FreeTextMarkup.literal("*A* **B**"),
        )
        sources.forEach { m ->
            val once = FreeTextMarkup.fromHtml(FreeTextMarkup.toHtml(m))
            assertEquals(FreeTextMarkup.toHtml(m), FreeTextMarkup.toHtml(once), m)
            assertEquals(once, FreeTextMarkup.fromHtml(FreeTextMarkup.toHtml(once)), m)
        }
    }

    @Test
    fun `truncating a literal value at any limit cannot re-enable a marker`() {
        val value = FreeTextMarkup.literal("*".repeat(30))
        (0..value.length + 1).forEach { limit ->
            val cut = FreeTextMarkup.truncate(value, limit)
            assertTrue(cut.length <= limit, "limit $limit")
            assertTrue(noFormats("*a " + cut), "limit $limit")
            assertTrue(noFormats(cut), "limit $limit")
            assertTrue(FreeTextMarkup.toPlain(cut).all { it == '*' }, "limit $limit")
        }
        assertEquals("abc", FreeTextMarkup.truncate("abcdef", 3))
        assertEquals("abc", FreeTextMarkup.truncate("abc", 10))
    }

    @Test
    fun `an indented plain line keeps its indentation in every rendering`() {
        val source = "a\n    b **c**\n        d"
        assertEquals("a\n    b c\n        d", FreeTextMarkup.toPlain(source))
        assertEquals(source, FreeTextMarkup.renumber(source))
        val lines = FreeTextMarkup.toLines(source)
        assertEquals(listOf(0, 1, 2), lines.map { it.level })
        assertEquals(listOf(null, null, null), lines.map { it.mark })
        assertEquals("b ", lines[1].runs.first().text)
        assertEquals(
            "<div class=\"lt-ft-line lt-ft-l1 lt-ft-plain\">b <b>c</b></div>",
            FreeTextMarkup.toHtml("    b **c**").substringAfter("<div class=\"lt-ft\">").removeSuffix("</div>"),
        )
        assertTrue(FreeTextMarkup.toHtml("    ").contains("lt-ft-l1 lt-ft-plain\"><br>"))
    }

    @Test
    fun `fewer than four leading spaces stay content and surplus beyond two levels is kept as content`() {
        assertEquals(0, FreeTextMarkup.toLines("   x").single().level)
        assertEquals("   x", FreeTextMarkup.toPlain("   x"))
        val deep = FreeTextMarkup.toLines(" ".repeat(12) + "x").single()
        assertEquals(2, deep.level)
        assertEquals("    x", deep.runs.single().text)
        assertEquals(" ".repeat(12) + "x", FreeTextMarkup.renumber(" ".repeat(12) + "x"))
    }
}
