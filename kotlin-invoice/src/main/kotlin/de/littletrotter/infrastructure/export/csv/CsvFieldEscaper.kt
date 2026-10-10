package de.littletrotter.infrastructure.export.csv

/**
 * RFC 4180 quoting for one already-rendered CSV field. Quotes a value when it contains the field
 * delimiter (`;`, chosen deliberately — German Excel opens a semicolon-separated file by
 * double-click, a comma-separated one lands in a single column), a double quote, or a line break
 * (CR or LF); an embedded `"` doubles, per the same grammar.
 *
 * A field with none of those stays unquoted — quoting everything would still round-trip correctly,
 * but every plain value in the file would gain a needless pair of quotes.
 */
internal object CsvFieldEscaper {

    private const val DELIMITER = ';'
    private const val QUOTE = '"'
    private const val QUOTE_STRING = "\""
    private const val ESCAPED_QUOTE = "\"\""

    fun escape(value: String): String =
        if (needsQuoting(value)) QUOTE_STRING + value.replace(QUOTE_STRING, ESCAPED_QUOTE) + QUOTE_STRING else value

    private fun needsQuoting(value: String): Boolean =
        value.any { it == DELIMITER || it == QUOTE || it == '\r' || it == '\n' }
}
