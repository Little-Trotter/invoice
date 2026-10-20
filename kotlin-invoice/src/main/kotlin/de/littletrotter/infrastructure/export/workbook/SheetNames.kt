package de.littletrotter.infrastructure.export.workbook

/**
 * Excel sheet-name rules the writer enforces itself instead of trusting a caller: a 31-character
 * cap and a small set of forbidden characters. The four sheet names this program uses are short
 * and safe today, but the writer sanitises rather than assuming that stays true.
 */
internal object SheetNames {

    private const val MAX_LENGTH = 31
    private val FORBIDDEN_CHARACTERS = charArrayOf(':', '\\', '/', '?', '*', '[', ']')

    fun sanitize(name: String): String {
        val withoutForbiddenCharacters = name.map { if (it in FORBIDDEN_CHARACTERS) '_' else it }.joinToString("")
        return withoutForbiddenCharacters.take(MAX_LENGTH)
    }
}
