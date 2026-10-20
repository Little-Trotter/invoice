package de.littletrotter.shared.documents

/**
 * The formats the invoice free-text editor may offer. The toolbar builds its format buttons from
 * [entries], and every entry is rendered by [FreeTextMarkup.toLines], so an editor
 * function without a print conversion cannot exist. The inline scanner takes its delimiters from
 * these [marker] values, so toolbar and parser cannot disagree on the syntax. The list formats
 * have no inline marker: they are line prefixes.
 */
enum class FreeTextFormat(val marker: String?) {
    BOLD("**"),
    ITALIC("*"),
    UNDERLINE("++"),
    STRIKE("~~"),
    BULLET_LIST(null),
    NUMBERED_LIST(null),
}

/** A recognised list line: leading [indentSpaces], bullet or numbered, and the text after the prefix. */
data class FreeTextListLine(
    val indentSpaces: Int,
    val numbered: Boolean,
    val content: String,
)

/** One formatted span of a free-text line: the text and every inline format open around it. */
data class FreeTextRun(
    val text: String,
    val formats: Set<FreeTextFormat>,
)

/**
 * One source line of the free text as a layout engine sees it: list indentation [level] (0 for a
 * plain line), the bullet glyph or typed number as [mark] (null for a plain line) and the inline
 * [runs]. An empty line has no runs.
 */
data class FreeTextLine(
    val level: Int,
    val mark: String?,
    val runs: List<FreeTextRun>,
)

/**
 * T2 — the one definition of the free-text storage syntax (`invoice.free_text`): `**bold**`,
 * `*italic*`, `***both***`, `++underline++`, `~~strike~~`, `- bullet` / `1. numbered` lines
 * (4 spaces of indentation per level, three levels) and `\n` line breaks. A plain line may carry
 * the same 4-spaces-per-level indentation (an indented paragraph, no mark); fewer than 4 leading
 * spaces are ordinary text. Everything else is literal text.
 *
 * Numbered items are counted, not read: consecutive numbered lines on one level form a run that
 * counts on from the first line's typed number, so `1.`/`1.`/`1.` and `1.`/`2.`/`3.` both print
 * 1., 2., 3. — the rich-text editor stores every item as `1. ` because HTML lists carry no
 * per-item number. A plain line, a bullet on the same level or any line on a shallower level ends
 * the run; deeper lines (a sub-list) do not.
 *
 * [toPlain] (BT-22 note), [toHtml] (editor preview)
 * and [toLines] (structured runs for the Typst template's `data.json`) share one parse — line
 * classification plus a recursive left-to-right inline scanner — and differ only in the sink
 * that emits tags, glyphs and escapes. Because spans are found by recursion and never by
 * sequential regex passes, the emitted tags are well-nested by construction.
 */
object FreeTextMarkup {
    /** The editor's `maxLength`; the print/DB path is proven at this size. */
    const val MAX_LENGTH = 4000

    /** Source indentation per list level. */
    const val INDENT_SPACES = 4

    /** Deepest list level (levels 0, 1, 2). */
    const val MAX_LEVEL = 2

    private const val BULLET_MARKER = "- "
    private val BULLET_GLYPHS = listOf("•", "–", "›")
    private val LIST_LINE = Regex("^( *)(- |\\d{1,3}\\. )(.*)$")

    private class Delimiter(val marker: String, val formats: List<FreeTextFormat>)

    // Longest marker first (stable): `***`, `**`, `++`, `~~`, `*`.
    private val DELIMITERS: List<Delimiter> = buildDelimiters()

    private fun buildDelimiters(): List<Delimiter> {
        val boldItalic =
            Delimiter(
                FreeTextFormat.BOLD.marker.orEmpty() + FreeTextFormat.ITALIC.marker.orEmpty(),
                listOf(FreeTextFormat.BOLD, FreeTextFormat.ITALIC),
            )
        val single = FreeTextFormat.entries.mapNotNull { f -> f.marker?.let { Delimiter(it, listOf(f)) } }
        return (listOf(boldItalic) + single).sortedByDescending { it.marker.length }
    }

    /** What one rendering emits for literal text and for a formatted span, and how pieces join. */
    private interface Sink<T> {
        fun literal(text: String, active: Set<FreeTextFormat>): T

        fun span(formats: List<FreeTextFormat>, inner: T): T

        fun concat(parts: List<T>): T
    }

    /** The string renderings: per-character escaping plus open/close tags per format. */
    private class TagSink(
        val escape: (Char) -> String,
        val open: (FreeTextFormat) -> String,
        val close: (FreeTextFormat) -> String,
    ) : Sink<String> {
        override fun literal(text: String, active: Set<FreeTextFormat>): String =
            text.filter { it != GUARD }.map(escape).joinToString("")

        override fun span(formats: List<FreeTextFormat>, inner: String): String =
            formats.joinToString("") { open(it) } + inner + formats.asReversed().joinToString("") { close(it) }

        override fun concat(parts: List<String>): String = parts.joinToString("")
    }

    /** Structured rendering: runs carry their active formats, adjacent equal runs merge. */
    private object RunSink : Sink<List<FreeTextRun>> {
        override fun literal(text: String, active: Set<FreeTextFormat>): List<FreeTextRun> =
            listOf(FreeTextRun(text.filter { it != GUARD }, active))

        override fun span(formats: List<FreeTextFormat>, inner: List<FreeTextRun>): List<FreeTextRun> = inner

        override fun concat(parts: List<List<FreeTextRun>>): List<FreeTextRun> {
            val merged = mutableListOf<FreeTextRun>()
            parts.flatten().forEach { run ->
                val last = merged.lastOrNull()
                if (last != null && last.formats == run.formats) {
                    merged[merged.lastIndex] = last.copy(text = last.text + run.text)
                } else {
                    merged += run
                }
            }
            return merged
        }
    }

    private val htmlSink =
        TagSink(
            escape = { c ->
                when (c) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    else -> c.toString()
                }
            },
            open = { tag(it, "<") },
            close = { tag(it, "</") },
        )

    private val plainSink = TagSink(escape = { it.toString() }, open = { "" }, close = { "" })

    /**
     * Invisible guard character used by [literal]. The scanner never reads a `*` with a guard on both
     * sides as a delimiter, and every sink drops the guard, so it never shows up in any rendering.
     */
    private const val GUARD = '\u200B'

    private val MARKER_CHARS = setOf('*', '+', '~')
    private val LIST_LINE_START = Regex("(?m)^(?= *(?:- |\\d{1,3}\\. ))")

    /**
     * [text] cut to at most [limit] characters. A cut that would leave a guarded marker without its
     * closing guard drops that marker too, so a truncated [literal] value can never end in a `*`
     * that closes a format opened earlier in the line.
     */
    fun truncate(text: String, limit: Int): String {
        val cut = text.take(limit)
        val dangling = cut.length < text.length && text[cut.length] == GUARD && cut.lastOrNull() in MARKER_CHARS
        return if (dangling) cut.dropLast(1).removeSuffix(GUARD.toString()) else cut
    }

    /**
     * [text] made safe to embed in markup: every rendering shows exactly [text] — no bold, italic,
     * underline, strike or list line — whatever surrounds it. The syntax has no escape character, so
     * the text is instead given invisible [GUARD] characters: one on each side of every marker
     * character (this breaks `**`, `***`, `++`, `~~` and, because the scanner skips a `*` that has a
     * guard on both sides, so also a lone `*`), and one in front of any line
     * that would read as a list item. All renderings drop the guard again, so [toPlain] of the result
     * equals [text].
     */
    fun literal(text: String): String {
        val guarded = buildString {
            text.forEach { c ->
                if (c in MARKER_CHARS) append(GUARD).append(c).append(GUARD) else append(c)
            }
        }
        return LIST_LINE_START.replace(guarded, GUARD.toString())
    }

    private fun tag(
        format: FreeTextFormat,
        prefix: String,
    ): String =
        when (format) {
            FreeTextFormat.BOLD -> "${prefix}b>"
            FreeTextFormat.ITALIC -> "${prefix}i>"
            FreeTextFormat.UNDERLINE -> "${prefix}u>"
            FreeTextFormat.STRIKE -> "${prefix}s>"
            FreeTextFormat.BULLET_LIST, FreeTextFormat.NUMBERED_LIST -> ""
        }

    /** Markers stripped, list prefixes and the indentation of list and plain lines kept as typed — the BT-22 note text. */
    fun toPlain(source: String): String =
        classify(source).joinToString("\n") { line ->
            line.indent + line.marker.orEmpty() + inline(line.content, emptySet(), plainSink)
        }

    /**
     * [source] with every numbered item carrying the number every rendering shows for it, so the
     * stored text reads the same way it prints (DB, export, mail body). Line breaks are normalised
     * to `\n`; nothing else changes. Idempotent.
     */
    fun renumber(source: String): String =
        classify(source).joinToString("\n") { line ->
            line.indent + line.marker.orEmpty() + line.content
        }

    /** One `<div class="lt-ft">` root; only `class` attributes are ever emitted. */
    fun toHtml(source: String): String {
        if (source.isEmpty()) return "<div class=\"lt-ft\"></div>"
        val lines =
            classify(source).joinToString("") { line ->
                val classes =
                    when {
                        line.marker != null -> "lt-ft-line lt-ft-l${line.level}"
                        line.level > 0 -> "lt-ft-line lt-ft-l${line.level} lt-ft-plain"
                        else -> "lt-ft-line"
                    }
                val mark = if (line.marker != null) "<span class=\"lt-ft-mark\">${markOf(line.marker, line.level)}</span>" else ""
                val text = if (line.marker == null && line.content.isEmpty()) "<br>" else inline(line.content, emptySet(), htmlSink)
                "<div class=\"$classes\">$mark$text</div>"
            }
        return "<div class=\"lt-ft\">$lines</div>"
    }

    /**
     * The lines and formatted runs a layout engine without a tag parser lays out itself — the
     * Typst template reads exactly this shape from `data.json`. Same line classification and
     * inline scan as the string renderings, so they never disagree on what a marker means.
     */
    fun toLines(source: String): List<FreeTextLine> =
        classify(source).map { line ->
            if (line.marker == null) {
                FreeTextLine(level = line.level, mark = null, runs = inline(line.content, emptySet(), RunSink))
            } else {
                FreeTextLine(line.level, markOf(line.marker, line.level), inline(line.content, emptySet(), RunSink))
            }
        }

    /**
     * The editor's paste/import path: turns HTML from a rich-text editor (or `toHtml`'s own
     * preview markup, fed back in) into the storage syntax, so pasted content is stored the same
     * way typed content is. [FreeTextHtmlImport] does the work; sanitising untrusted HTML is a
     * jsoup allowlist, not a hand-rolled parser.
     *
     * Allowlist: `p`, `br`, `strong`/`b`, `em`/`i`, `u`, `s`/`del`/`strike`, `ul`/`ol`/`li`
     * (nesting up to [MAX_LEVEL] + 1 = 3 levels; a 4th level and deeper is flattened onto the
     * deepest one, the same clamp [toHtml]'s own line classifier applies; the `p` an editor puts
     * around list item text is unwrapped). Every attribute is discarded, with one private
     * exception: a class on `li` and `p` is read — never emitted — to carry the indent of this
     * module's own preview markup (`lt-ft-line`/`lt-ft-mark`) through the sanitiser, then dropped.
     * Every
     * other tag (`script`, `style`, `img`, `table`, `a`, `span`, `div`, headings, comments, …) is
     * either unwrapped with its text kept, or, for `script`/`style`, dropped together with its
     * text — jsoup's ordinary `Safelist` behaviour, not special-cased here. An `a`'s link text
     * survives; its `href` does not, because `href` is an attribute like any other.
     *
     * Whitespace: runs of whitespace inside a text node collapse to one space; each resulting
     * markup line is trimmed, matching how `p`/`br`/`li` map onto this module's `\n`-separated
     * lines.
     *
     * Round trip: `toHtml(fromHtml(toHtml(m))) == toHtml(m)` for markup without leading or
     * trailing whitespace on any line (no editor produces such whitespace, so this covers every
     * realistic `m`); and `fromHtml(toHtml(m)) == m` in addition when `m` is canonical, meaning:
     * `m == renumber(m)` — HTML lists carry no per-item number, so `fromHtml` cannot recover
     * one a caller typed and instead stores the counted number ([renumber]) for every numbered
     * item. Non-canonical input (an out-of-sequence number, or trailing spaces on a line) is
     * normalised away rather than rejected.
     */
    fun fromHtml(html: String): String = FreeTextHtmlImport.import(html)

    /** The one list-line recogniser, shared with the editor's outline edits; null for a plain line. */
    fun parseListLine(line: String): FreeTextListLine? {
        val (indent, typedMarker, content) = LIST_LINE.matchEntire(line)?.destructured ?: return null
        return FreeTextListLine(indent.length, typedMarker != BULLET_MARKER, content)
    }

    private fun normalize(source: String): String = source.replace("\r\n", "\n").replace('\r', '\n')

    private fun levelOf(leadingSpaces: String): Int = minOf(leadingSpaces.length / INDENT_SPACES, MAX_LEVEL)

    /**
     * One source line after classification: [indent] as written, clamped list [level], the list
     * [marker] in storage form with its counted number (`- `, `2. `; null for a plain line) and the
     * [content] after the prefix (the whole line for a plain one).
     */
    private class SourceLine(
        val indent: String,
        val level: Int,
        val marker: String?,
        val content: String,
    )

    // The one line classification every rendering shares; see the numbering rule in the class KDoc.
    private fun classify(source: String): List<SourceLine> {
        val openRuns = arrayOfNulls<Int>(MAX_LEVEL + 1)
        return normalize(source).split('\n').map { line ->
            val list = LIST_LINE.matchEntire(line)
            if (list == null) {
                openRuns.fill(null)
                val spaces = line.takeWhile { it == ' ' }
                val level = levelOf(spaces)
                val indent = " ".repeat(INDENT_SPACES * level)
                SourceLine(indent = indent, level = level, marker = null, content = line.removePrefix(indent))
            } else {
                val (indent, typedMarker, content) = list.destructured
                val level = levelOf(indent)
                for (deeper in level + 1..MAX_LEVEL) openRuns[deeper] = null
                val marker =
                    if (typedMarker == BULLET_MARKER) {
                        openRuns[level] = null
                        typedMarker
                    } else {
                        val number = openRuns[level]?.plus(1) ?: typedMarker.substringBefore('.').toInt()
                        openRuns[level] = number
                        "$number. "
                    }
                SourceLine(indent, level, marker, content)
            }
        }
    }

    // The glyph of a bullet item by level, the counted number for a numbered item.
    private fun markOf(
        marker: String,
        level: Int,
    ): String = if (marker == BULLET_MARKER) BULLET_GLYPHS[level] else marker.trimEnd()

    /**
     * Recursive inline scanner. [active] are the formats already open around this span; a
     * delimiter that would nest a format in itself is literal text.
     */
    private fun <T> inline(
        text: String,
        active: Set<FreeTextFormat>,
        sink: Sink<T>,
    ): T {
        val parts = mutableListOf<T>()
        var i = 0
        while (i < text.length) {
            val step = nextInlineStep(text, i, active, sink)
            parts += step.rendered
            i = step.nextIndex
        }
        return sink.concat(parts)
    }

    /** One rendered span of [inline]'s scan and the index it resumes at — computed without loop
     *  jump statements, so the scanning `while` in [inline] stays a plain advance-and-append. */
    private data class InlineStep<T>(val rendered: T, val nextIndex: Int)

    private fun <T> nextInlineStep(
        text: String,
        i: Int,
        active: Set<FreeTextFormat>,
        sink: Sink<T>,
    ): InlineStep<T> {
        val delimiter = DELIMITERS.firstOrNull { text.startsWith(it.marker, i) }
        if (delimiter == null || isGuardedStar(text, i, delimiter.marker)) {
            return InlineStep(sink.literal(text[i].toString(), active), i + 1)
        }
        val length = delimiter.marker.length
        val close =
            if (delimiter.formats.any { it in active } || !validOpener(text, i + length)) {
                -1
            } else {
                findCloser(text, i + length + 1, delimiter.marker)
            }
        if (close < 0) {
            return InlineStep(sink.literal(delimiter.marker, active), i + length)
        }
        val innerActive = active + delimiter.formats
        val inner = inline(text.substring(i + length, close), innerActive, sink)
        return InlineStep(sink.span(delimiter.formats, inner), close + length)
    }

    private fun validOpener(
        text: String,
        afterOpener: Int,
    ): Boolean = afterOpener < text.length && !text[afterOpener].isWhitespace()

    private fun findCloser(
        text: String,
        from: Int,
        marker: String,
    ): Int {
        var start = from
        while (true) {
            val at = text.indexOf(marker, start)
            if (at < 0) return -1
            val single = marker == FreeTextFormat.ITALIC.marker
            val partOfRun = single && (text[at - 1] == '*' || text.getOrNull(at + 1) == '*')
            if (!text[at - 1].isWhitespace() && !partOfRun && !isGuardedStar(text, at, marker)) return at
            start = at + 1
        }
    }

    // A single `*` with a guard on both sides is one that [literal] protected: plain text, never a
    // delimiter. A real marker next to guarded text has a guard on one side only, so it still works.
    private fun isGuardedStar(text: String, at: Int, marker: String): Boolean =
        marker == FreeTextFormat.ITALIC.marker && text.getOrNull(at - 1) == GUARD && text.getOrNull(at + 1) == GUARD
}
