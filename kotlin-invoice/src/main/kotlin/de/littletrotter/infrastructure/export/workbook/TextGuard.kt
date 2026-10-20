package de.littletrotter.infrastructure.export.workbook

/**
 * Guards a text value against everything OOXML rejects that a Postgres `text` column happily
 * stores: control characters other than tab/newline, unpaired UTF-16 surrogates, and strings
 * longer than a cell can hold.
 */
internal object TextGuard {

    /** OOXML's hard cap on a cell's text length. */
    const val MAX_LENGTH = 32_767

    private const val TRUNCATION_SUFFIX = " …[gekürzt]"
    private const val LOWEST_PRINTABLE_CODE = 0x20
    private const val REPLACEMENT_CHARACTER = '�'

    fun sanitize(value: String): String = truncate(replaceLoneSurrogates(stripControlCharacters(value)))

    private fun stripControlCharacters(value: String): String =
        value.filter { it == '\t' || it == '\n' || it.code >= LOWEST_PRINTABLE_CODE }

    private fun replaceLoneSurrogates(value: String): String {
        if (value.none { Character.isSurrogate(it) }) return value
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val startsValidPair = Character.isHighSurrogate(current) &&
                index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1])
            when {
                startsValidPair -> {
                    result.append(current).append(value[index + 1])
                    index += 2
                }
                Character.isSurrogate(current) -> {
                    result.append(REPLACEMENT_CHARACTER)
                    index++
                }
                else -> {
                    result.append(current)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_LENGTH) return value
        val keep = MAX_LENGTH - TRUNCATION_SUFFIX.length
        return value.substring(0, keep) + TRUNCATION_SUFFIX
    }
}
