package de.littletrotter.ui.format

import de.littletrotter.shared.receipt.DocumentSize
import java.text.NumberFormat
import java.util.Locale

/**
 * IR-3 — the one place a [DocumentSize] becomes user-visible text, mirroring [MoneyFormatter]'s
 * locale/`NumberFormat` idiom instead of a locale-blind `String.format`. [DocumentSize] is stored
 * in binary MiB internally but shown as decimal "MB" with one decimal place (arc42 glossary,
 * "Size unit convention", IR-5) — keeping that conversion here stops the budget hint and any other
 * caller from rounding it two different ways.
 */
object DocumentSizeFormatter {

    private fun numberFormat(locale: Locale) = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

    /** The bare number, no unit — for messages that place "MB" once after two such numbers. */
    fun formatMebibytes(size: DocumentSize, locale: Locale = Locale.GERMANY): String =
        numberFormat(locale).format(size.mebiBytes)

    fun format(size: DocumentSize, locale: Locale = Locale.GERMANY): String =
        "${formatMebibytes(size, locale)} MB"

    /**
     * Like [format], but a size below one MiB reads in KB: a typical offer or invoice PDF is a few
     * dozen KB and would otherwise show as "0,0 MB", which looks like a missing file.
     */
    fun formatCompact(size: DocumentSize, locale: Locale = Locale.GERMANY): String {
        if (size.bytes >= BYTES_PER_KIBIBYTE * KIBIBYTES_PER_MEBIBYTE) return format(size, locale)
        val kibiBytes = (size.bytes + BYTES_PER_KIBIBYTE - 1) / BYTES_PER_KIBIBYTE
        return "${NumberFormat.getIntegerInstance(locale).format(kibiBytes)} KB"
    }

    private const val BYTES_PER_KIBIBYTE = 1024L
    private const val KIBIBYTES_PER_MEBIBYTE = 1024L
}
