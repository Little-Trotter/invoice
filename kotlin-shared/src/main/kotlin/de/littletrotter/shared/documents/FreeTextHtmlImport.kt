package de.littletrotter.shared.documents

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/**
 * [FreeTextMarkup.fromHtml]'s implementation, split out from [FreeTextMarkup] so that file stays
 * under detekt's file/class-size gate; nothing here is part of the public API.
 *
 * Two passes over the DOM, both driven by the same jsoup allowlist:
 * 1. [internalizeOwnMarkup] rewrites this module's own `toHtml` output (`<div class="lt-ft-line
 *    lt-ft-lN">`, `<span class="lt-ft-mark">`) into the allowlist's own p/ul/ol/li vocabulary
 *    *before* sanitising, so an editor preview that is fed back through the sanitiser survives
 *    the round trip exactly like the editor's own p/ul/ol/li markup would. Without this pass,
 *    a plain unwrap (the fate of every other div/span) would merge every source line into one.
 * 2. [SAFELIST] then does the actual security sanitisation via `Jsoup.clean`: every tag other
 *    than `p, br, strong, b, em, i, u, s, del, strike, ul, ol, li` is unwrapped (its text kept,
 *    matching jsoup's default `Safelist.none()` behaviour) or, for `script`/`style`, dropped
 *    together with its text; every attribute is discarded. The one exception is the private
 *    indent class [internalizeOwnMarkup] puts on `p`/`li`, which is read once and never emitted.
 *
 * The editor delivers plain tags only: list depth comes from `ul`/`ol` nesting (deeper than the
 * third level is flattened onto it), and the `p` the editor wraps around list item text is
 * unwrapped so it does not add blank lines.
 */
internal object FreeTextHtmlImport {
    private const val TAG_P = "p"
    private const val TAG_BR = "br"
    private const val TAG_UL = "ul"
    private const val TAG_OL = "ol"
    private const val TAG_LI = "li"
    private const val CLASS_ATTR = "class"
    private const val LT_FT_LINE_CLASS = "lt-ft-line"

    private val ALLOWED_TAGS =
        arrayOf(TAG_P, TAG_BR, "strong", "b", "em", "i", "u", "s", "del", "strike", TAG_UL, TAG_OL, TAG_LI)

    private val SAFELIST: Safelist = Safelist.none().addTags(*ALLOWED_TAGS).addAttributes(TAG_LI, CLASS_ATTR).addAttributes(TAG_P, CLASS_ATTR)

    private val LEVEL_CLASS = Regex("lt-ft-l(\\d+)")
    private val INDENT_CLASS = Regex("lt-ft-indent-(\\d+)")
    private val WHITESPACE_RUN = Regex("\\s+")

    private fun isListTag(tagName: String): Boolean = tagName == TAG_UL || tagName == TAG_OL

    fun import(html: String): String {
        val raw = Jsoup.parseBodyFragment(html).body()
        internalizeOwnMarkup(raw)
        val cleaned = Jsoup.clean(raw.html(), SAFELIST)
        val body = Jsoup.parseBodyFragment(cleaned).body()
        return FreeTextMarkup.renumber(renderDocument(body).joinToString("\n"))
    }

    // --- Top level: paragraphs, lists, and bare inline content an editor left outside a <p>. ---

    private fun renderDocument(body: Element): List<String> {
        val lines = mutableListOf<String>()
        val looseShim = Element("div")
        for (child in body.childNodes().toList()) {
            when (child) {
                is Element -> when {
                    child.tagName() == TAG_P -> {
                        flushLoose(looseShim, lines)
                        lines.addAll(indentFirst(renderLines(child).map { it.trim() }, levelOfParagraph(child)))
                    }
                    isListTag(child.tagName()) -> {
                        flushLoose(looseShim, lines)
                        renderList(child, lines)
                    }
                    else -> looseShim.appendChild(child.clone())
                }
                is TextNode -> if (!child.text().isBlank()) looseShim.appendChild(child.clone())
                else -> looseShim.appendChild(child.clone())
            }
        }
        flushLoose(looseShim, lines)
        return lines
    }

    private fun levelOfParagraph(p: Element): Int {
        val indent = INDENT_CLASS.find(p.className())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return indent.coerceAtMost(FreeTextMarkup.MAX_LEVEL)
    }

    // An empty paragraph stays empty: whitespace alone would not survive the next trim anyway.
    private fun indentFirst(
        rendered: List<String>,
        level: Int,
    ): List<String> {
        val first = rendered.firstOrNull()
        if (level == 0 || first.isNullOrEmpty()) return rendered
        return listOf(" ".repeat(FreeTextMarkup.INDENT_SPACES * level) + first) + rendered.drop(1)
    }

    private fun flushLoose(
        looseShim: Element,
        lines: MutableList<String>,
    ) {
        if (looseShim.childNodeSize() > 0) {
            lines.addAll(renderLines(looseShim).map { it.trim() })
            looseShim.empty()
        }
    }

    // --- This module's own dialect: rewritten into p/ul/ol/li before the allowlist ever runs. ---

    private fun internalizeOwnMarkup(root: Element) {
        for (ftRoot in root.getElementsByClass("lt-ft")) {
            rewriteLtFtLines(ftRoot)
        }
    }

    /** The `<ul>`/`<ol>` a run of consecutive same-type `lt-ft-lN` lines is being grouped into. */
    private class ListRun(val group: Element?, val numbered: Boolean?)

    private val NO_RUN = ListRun(null, null)

    private fun rewriteLtFtLines(ftRoot: Element) {
        var run = NO_RUN
        for (lineDiv in ftRoot.children().toList()) {
            run = rewriteLtFtLine(lineDiv, run)
        }
    }

    private fun rewriteLtFtLine(
        lineDiv: Element,
        run: ListRun,
    ): ListRun {
        if (!lineDiv.hasClass(LT_FT_LINE_CLASS)) return NO_RUN
        val level = levelOf(lineDiv)
        if (level == null || lineDiv.getElementsByClass("lt-ft-mark").isEmpty()) {
            lineDiv.tagName(TAG_P)
            lineDiv.removeAttr(CLASS_ATTR)
            if (level != null && level > 0) lineDiv.attr(CLASS_ATTR, "lt-ft-indent-$level")
            return NO_RUN
        }
        val numbered = markIsNumbered(lineDiv)
        lineDiv.tagName(TAG_LI)
        lineDiv.removeAttr(CLASS_ATTR)
        if (level > 0) lineDiv.attr(CLASS_ATTR, "lt-ft-indent-$level")
        val group =
            if (run.group == null || run.numbered != numbered) {
                Element(if (numbered) TAG_OL else TAG_UL).also { lineDiv.before(it) }
            } else {
                run.group
            }
        lineDiv.remove()
        group.appendChild(lineDiv)
        return ListRun(group, numbered)
    }

    private fun levelOf(lineDiv: Element): Int? {
        val match = lineDiv.classNames().firstNotNullOfOrNull { LEVEL_CLASS.matchEntire(it) } ?: return null
        return match.groupValues[1].toInt().coerceAtMost(FreeTextMarkup.MAX_LEVEL)
    }

    private fun markIsNumbered(lineDiv: Element): Boolean {
        val mark = lineDiv.getElementsByClass("lt-ft-mark").firstOrNull()
        val numbered = mark?.text()?.firstOrNull()?.isDigit() == true
        mark?.remove()
        return numbered
    }

    // --- Lists: DOM nesting (`<ul><li><ul>…`) maps onto the same level arithmetic
    // `FreeTextMarkup.toHtml`'s own line classifier uses. ---

    private fun renderList(
        listEl: Element,
        out: MutableList<String>,
    ) {
        for (li in listEl.children()) {
            if (li.tagName() == TAG_LI) renderListItem(li, out)
        }
    }

    private fun renderListItem(
        li: Element,
        out: MutableList<String>,
    ) {
        val ownContent = Element("div")
        val nestedLists = mutableListOf<Element>()
        val wrapsParagraphs = li.children().any { it.tagName() == TAG_P }
        for (node in li.childNodes().toList()) {
            when {
                // Pretty-printed indentation between the item's block children is not content.
                wrapsParagraphs && node is TextNode && node.isBlank -> Unit
                node is Element && isListTag(node.tagName()) -> nestedLists.add(node)
                // The editor wraps item text in a <p>; a further <p> in the same item is a soft line.
                node is Element && node.tagName() == TAG_P -> appendUnwrappedParagraph(ownContent, node)
                else -> ownContent.appendChild(node.clone())
            }
        }
        val indent = " ".repeat(FreeTextMarkup.INDENT_SPACES * liLevel(li))
        // Every numbered item is written as `1. `; `FreeTextMarkup.renumber` counts them afterwards.
        val marker = if (liNumbered(li)) "1. " else "- "
        renderLines(ownContent).map { it.trim() }.forEachIndexed { index, text ->
            out.add(if (index == 0) indent + marker + text else text)
        }
        nestedLists.forEach { renderList(it, out) }
    }

    private fun appendUnwrappedParagraph(
        target: Element,
        paragraph: Element,
    ) {
        if (paragraph.childNodeSize() == 0) return
        if (target.childNodeSize() > 0) target.appendElement(TAG_BR)
        paragraph.childNodes().forEach { target.appendChild(it.clone()) }
    }

    private fun liLevel(li: Element): Int {
        var ancestorDepth = 0
        var parent = li.parent()
        while (parent != null) {
            if (isListTag(parent.tagName())) ancestorDepth++
            parent = parent.parent()
        }
        val domLevel = (ancestorDepth - 1).coerceAtLeast(0)
        val ownIndent = INDENT_CLASS.find(li.className())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (domLevel + ownIndent).coerceAtMost(FreeTextMarkup.MAX_LEVEL)
    }

    // `renderList` only ever calls this for an `li` it took from `listEl.children()`, so the
    // parent is always that same `ul`/`ol`.
    private fun liNumbered(li: Element): Boolean = li.parent()?.tagName() == TAG_OL

    // --- Inline content: text runs, `<br>` line breaks and the four formatting tags, mirroring
    // `FreeTextMarkup`'s own inline recursion so nested tags come back out in the same order. ---

    private fun renderLines(container: Node): List<String> {
        if (isFillerBreak(container)) return listOf("")
        val result = mutableListOf(StringBuilder())
        for (node in container.childNodes()) {
            appendNode(result, node)
        }
        return result.map { it.toString() }
    }

    // jsoup pretty-prints a cleaned `<p><br></p>` back to `<p>\n <br>\n</p>`: the two whitespace
    // text nodes are formatting artifacts, not content, so they must not defeat the "this line
    // is just the empty-line filler `<br>` `toHtml` emits" check below.
    private fun isFillerBreak(container: Node): Boolean {
        val meaningful = container.childNodes().filterNot { it is TextNode && it.text().isBlank() }
        val only = meaningful.singleOrNull() ?: return false
        return only is Element && only.tagName() == TAG_BR
    }

    private fun appendNode(
        result: MutableList<StringBuilder>,
        node: Node,
    ) {
        when (node) {
            // Text in HTML is literal by definition: only tags carry formatting, so marker characters
            // and list-like prefixes the user typed or a value contained must not become markup.
            is TextNode -> result.last().append(FreeTextMarkup.literal(collapseWhitespace(node.text())))
            is Element ->
                when (node.tagName()) {
                    TAG_BR -> result.add(StringBuilder())
                    "strong", "b" -> appendFormatted(result, node, FreeTextFormat.BOLD)
                    "em", "i" -> appendFormatted(result, node, FreeTextFormat.ITALIC)
                    "u" -> appendFormatted(result, node, FreeTextFormat.UNDERLINE)
                    "s", "del", "strike" -> appendFormatted(result, node, FreeTextFormat.STRIKE)
                    else -> node.childNodes().forEach { appendNode(result, it) }
                }
            else -> Unit
        }
    }

    private fun appendFormatted(
        result: MutableList<StringBuilder>,
        el: Element,
        format: FreeTextFormat,
    ) {
        val marker = format.marker.orEmpty()
        renderLines(el).forEachIndexed { index, text ->
            if (index > 0) result.add(StringBuilder())
            // Markers hug the text: an opener followed or a closer preceded by whitespace is not markup.
            val core = text.trim()
            if (core.isEmpty()) {
                result.last().append(text)
            } else {
                val lead = text.takeWhile { it.isWhitespace() }
                val trail = text.takeLastWhile { it.isWhitespace() }
                result.last().append(lead).append(marker).append(core).append(marker).append(trail)
            }
        }
    }

    private fun collapseWhitespace(text: String): String = text.replace(WHITESPACE_RUN, " ")
}
