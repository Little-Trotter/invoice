package de.littletrotter.ui.view.mail

import de.littletrotter.shared.documents.FreeTextMarkup

/**
 * `{{name}}` tokens inside the text a user edits in the compose view.
 *
 * The user may type a token or insert one from a chip; it is replaced by the draft's own value
 * before the text is previewed or sent, so a mail never leaves with a raw token in it.
 */
internal object MailPlaceholderText {

    private val TOKEN = Regex("\\{\\{(\\w+)}}")

    /** Optional section `{{#name}}…{{/name}}`, kept only when `name` has a non-blank value; no nesting. */
    private val SECTION = Regex("\\{\\{#(\\w+)}}(.*?)\\{\\{/\\1}}", RegexOption.DOT_MATCHES_ALL)

    /**
     * A marker alone on its line (spaces and tabs allowed) disappears with the whole line including
     * its line break, so authors can put markers on their own lines without leaving stray blank lines.
     */
    private val STANDALONE_MARKER_LINE = Regex("^[ \\t]*(\\{\\{[#/]\\w+}})[ \\t]*\\r?\\n", RegexOption.MULTILINE)
    private val SECTION_MARKER = Regex("\\{\\{[#/](\\w+)}}")

    /** Sections first, so a dropped section's tokens are never looked at and a kept one is filled like the rest. */
    private fun applySections(text: String, values: Map<String, String>): String =
        SECTION.replace(STANDALONE_MARKER_LINE.replace(text, "$1")) { match -> if (values[match.groupValues[1]].isNullOrBlank()) "" else match.groupValues[2] }

    /** [text] with every token that has a value in [values] replaced; unknown tokens stay as typed. */
    fun resolve(text: String, values: Map<String, String>): String =
        TOKEN.replace(applySections(text, values)) { match -> values[match.groupValues[1]] ?: match.value }

    /**
     * Like [resolve] for the body, which is formatting markup: each value is neutralized so a value
     * such as `**x**` or `- x` shows literally instead of becoming bold text or a list item.
     */
    fun resolveBody(text: String, values: Map<String, String>): String =
        TOKEN.replace(applySections(text, values)) { match -> values[match.groupValues[1]]?.let(FreeTextMarkup::literal) ?: match.value }

    /** Names of tokens in [text] that [values] cannot fill, each once, in order of appearance. */
    fun unresolved(text: String, values: Map<String, String>): List<String> =
        TOKEN.findAll(applySections(text, values)).map { it.groupValues[1] }.filter { it !in values }.distinct().toList()

    /** Names used as section markers (`{{#name}}`, `{{/name}}`) in [text], in order of appearance. */
    fun sectionNames(text: String): List<String> = SECTION_MARKER.findAll(text).map { it.groupValues[1] }.toList()

    /** The token a chip inserts for placeholder [key]. */
    fun token(key: String): String = "{{$key}}"
}
