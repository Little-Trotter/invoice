package de.littletrotter.ui.components.freetext

import de.littletrotter.shared.documents.FreeTextFormat
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.shared.documents.FreeTextRun

/**
 * The server side of [HtmlFreeTextEditor]'s push to the browser: turns stored markup into the
 * exact HTML the editor's own `getHTML()` yields for the same document — `<p>` paragraphs, nested
 * `<ul>`/`<ol>` whose items wrap their text in `<p>`, no attributes or classes — so pushing a
 * value never looks different from what typing it would have, and so [FreeTextMarkup.fromHtml],
 * written against that same shape, is the only parser either direction ever needs.
 */
internal object EditorHtml {
    // STRIKE emits <s>, the tag the editor itself produces for struck text.
    private val INLINE_TAGS =
        mapOf(
            FreeTextFormat.BOLD to "strong",
            FreeTextFormat.ITALIC to "em",
            FreeTextFormat.UNDERLINE to "u",
            FreeTextFormat.STRIKE to "s",
        )

    fun fromMarkup(markup: String): String {
        val writer = ListWriter()
        for (line in FreeTextMarkup.toLines(markup)) {
            val mark = line.mark
            if (mark == null) {
                writer.closeAll()
                writer.html.append("<p>").append(renderRuns(line.runs)).append("</p>")
            } else {
                writer.item(if (mark.firstOrNull()?.isDigit() == true) "ol" else "ul", line.level)
                writer.html.append("<p>").append(renderRuns(line.runs)).append("</p>")
            }
        }
        writer.closeAll()
        return writer.html.toString()
    }

    /** Tracks the open lists, outermost first; the innermost item is always still open. */
    private class ListWriter {
        val html = StringBuilder()
        private val open = ArrayDeque<String>()

        private fun closeInnermost() {
            html.append("</li></").append(open.removeLast()).append('>')
        }

        fun closeAll() {
            while (open.isNotEmpty()) closeInnermost()
        }

        /** Opens the `<li>` of a new item; its paragraph is appended by the caller. */
        fun item(
            tag: String,
            requestedLevel: Int,
        ) {
            // A level can only nest one step below the item before it; the editor cannot hold a gap.
            val level = requestedLevel.coerceIn(0, open.size)
            while (open.size > level + 1) closeInnermost()
            if (open.size == level + 1) {
                if (open.last() == tag) html.append("</li>") else closeInnermost()
            }
            if (open.size == level) {
                html.append('<').append(tag).append('>')
                open.addLast(tag)
            }
            html.append("<li>")
        }
    }

    /**
     * Reconstructs properly nested inline tags from a flat run list. [FreeTextRun.formats] are
     * well-nested by construction (`FreeTextMarkup`'s own KDoc) and iterate outer-to-inner (a
     * `Set` built by repeated `+`, which the standard library keeps insertion-ordered), so a stack
     * of currently open tags — closed down to, then reopened from, the common prefix shared with
     * the next run — reproduces the original tree. Wrapping each run in its own independent
     * element instead would lose that shared ancestry: `FreeTextMarkup.fromHtml` reads a marker
     * once per element it encounters, so three independent `<strong>` elements for what was one
     * bold span with an italic word in the middle would come back as three separate bold runs,
     * not the original one.
     */
    private fun renderRuns(runs: List<FreeTextRun>): String {
        val out = StringBuilder()
        var open: List<FreeTextFormat> = emptyList()
        for (run in runs) {
            val target = run.formats.toList()
            var common = 0
            while (common < open.size && common < target.size && open[common] == target[common]) common++
            for (i in open.size - 1 downTo common) out.append("</${INLINE_TAGS.getValue(open[i])}>")
            for (i in common until target.size) out.append("<${INLINE_TAGS.getValue(target[i])}>")
            open = target
            out.append(escape(run.text))
        }
        for (i in open.size - 1 downTo 0) out.append("</${INLINE_TAGS.getValue(open[i])}>")
        return out.toString()
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
