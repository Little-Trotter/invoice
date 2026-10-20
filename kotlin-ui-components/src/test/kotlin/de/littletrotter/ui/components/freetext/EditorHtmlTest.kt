package de.littletrotter.ui.components.freetext

import de.littletrotter.shared.documents.FreeTextFormat
import de.littletrotter.shared.documents.FreeTextMarkup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [EditorHtml.fromMarkup]: every construct it emits, plus a seeded round trip through
 * [FreeTextMarkup.fromHtml] proving the dialect it produces is exactly what that allowlist parser
 * expects back — the two are never tested in isolation from each other, because neither one alone
 * says anything about whether [HtmlFreeTextEditor] actually round-trips a value.
 */
class EditorHtmlTest {
    // ── one construct at a time ──────────────────────────────────────────────

    @Test
    fun `a plain line becomes its own paragraph`() {
        assertEquals("<p>hello</p>", EditorHtml.fromMarkup("hello"))
    }

    @Test
    fun `an indented plain line loses its indentation because the editor has no indented paragraphs`() {
        assertEquals("<p>x</p>", EditorHtml.fromMarkup("    x"))
        assertEquals("<p></p>", EditorHtml.fromMarkup("        "))
    }

    @Test
    fun `an empty line becomes an empty paragraph`() {
        assertEquals("<p></p>", EditorHtml.fromMarkup(""))
    }

    @Test
    fun `text is escaped`() {
        assertEquals("<p>a &amp; b &lt;c&gt; &quot;d&quot;</p>", EditorHtml.fromMarkup("a & b <c> \"d\""))
    }

    @Test
    fun `separate plain lines become separate paragraphs`() {
        assertEquals("<p>first</p><p>second</p>", EditorHtml.fromMarkup("first\nsecond"))
    }

    @Test
    fun `each inline format gets the editor's own tag`() {
        assertEquals("<p><strong>x</strong></p>", EditorHtml.fromMarkup("**x**"))
        assertEquals("<p><em>x</em></p>", EditorHtml.fromMarkup("*x*"))
        assertEquals("<p><u>x</u></p>", EditorHtml.fromMarkup("++x++"))
        assertEquals("<p><s>x</s></p>", EditorHtml.fromMarkup("~~x~~"))
    }

    @Test
    fun `a nested format produces one properly nested element, not two independent ones`() {
        assertEquals(
            "<p><strong>bold <em>and</em> end</strong></p>",
            EditorHtml.fromMarkup("**bold *and* end**"),
        )
    }

    @Test
    fun `a bullet list at level zero is a flat list`() {
        assertEquals("<ul><li><p>a</p></li><li><p>b</p></li></ul>", EditorHtml.fromMarkup("- a\n- b"))
    }

    @Test
    fun `a numbered list uses ol`() {
        assertEquals("<ol><li><p>a</p></li><li><p>b</p></li></ol>", EditorHtml.fromMarkup("1. a\n1. b"))
    }

    @Test
    fun `nested list levels become nested lists inside the parent item`() {
        assertEquals(
            "<ul><li><p>top</p><ul><li><p>mid</p><ul><li><p>deep</p></li></ul></li></ul></li></ul>",
            EditorHtml.fromMarkup("- top\n    - mid\n        - deep"),
        )
    }

    @Test
    fun `leaving a nested level continues the outer list`() {
        assertEquals(
            "<ul><li><p>a</p><ul><li><p>b</p></li></ul></li><li><p>c</p></li></ul>",
            EditorHtml.fromMarkup("- a\n    - b\n- c"),
        )
    }

    @Test
    fun `a different list type at a nested level opens a sibling list`() {
        assertEquals(
            "<ul><li><p>a</p><ul><li><p>b</p></li></ul><ol><li><p>c</p></li></ol></li></ul>",
            EditorHtml.fromMarkup("- a\n    - b\n    1. c"),
        )
    }

    @Test
    fun `a level gap is closed up to the next reachable level`() {
        assertEquals(
            "<ul><li><p>a</p><ul><li><p>b</p></li></ul></li></ul>",
            EditorHtml.fromMarkup("- a\n        - b"),
        )
    }

    @Test
    fun `a numbered line right after a bullet line starts a new list`() {
        assertEquals(
            "<ul><li><p>a</p></li></ul><ol><li><p>b</p></li></ol>",
            EditorHtml.fromMarkup("- a\n1. b"),
        )
    }

    @Test
    fun `a paragraph between two list runs keeps them apart`() {
        assertEquals(
            "<ul><li><p>a</p></li></ul><p>between</p><ul><li><p>b</p></li></ul>",
            EditorHtml.fromMarkup("- a\nbetween\n- b"),
        )
    }

    // ── round trip against FreeTextMarkup.fromHtml, seeded canonical generator ──────────────────

    private val words = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "invoice", "betrag", "kunde", "posten")
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
    // every shape EditorHtml's run-nesting reconstruction has to get right, mirroring the same
    // generator FreeTextMarkupFromHtmlTest uses for FreeTextMarkup's own toHtml/fromHtml pair.
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

    // Canonical: every numbered item reads "1. " (the only number fromHtml can ever recover,
    // neither one carries a per-item count) and no line carries stray whitespace.
    private fun randomListLine(
        random: Random,
        level: Int,
    ): String {
        val marker = if (random.nextBoolean()) "1. " else "- "
        return " ".repeat(FreeTextMarkup.INDENT_SPACES * level) + marker + randomTextLine(random)
    }

    private fun randomMarkup(random: Random): String {
        val lineCount = 1 + random.nextInt(6)
        var previousLevel = -1
        return (0 until lineCount).joinToString("\n") {
            when (random.nextInt(3)) {
                0 -> {
                    previousLevel = -1
                    ""
                }
                1 -> {
                    // The editor only nests one step below the previous item, so canonical markup does too.
                    val level = random.nextInt(minOf(previousLevel + 1, FreeTextMarkup.MAX_LEVEL) + 1)
                    previousLevel = level
                    randomListLine(random, level)
                }
                else -> {
                    previousLevel = -1
                    randomTextLine(random)
                }
            }
        }
    }

    @Test
    fun `fromHtml(fromMarkup(m)) equals m for 200 generated canonical markup texts`() {
        val random = Random(42)
        repeat(200) {
            val markup = FreeTextMarkup.renumber(randomMarkup(random))
            val html = EditorHtml.fromMarkup(markup)
            assertEquals(markup, FreeTextMarkup.fromHtml(html), "for ${markup.replace("\n", "\\n")}, html was $html")
        }
    }
}
