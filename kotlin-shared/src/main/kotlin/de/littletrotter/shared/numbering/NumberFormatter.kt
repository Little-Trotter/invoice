package de.littletrotter.shared.numbering

import java.time.LocalDate

/**
 * Pure number formatting shared by the invoice and quote modules: "what does the number look like"
 * is testable without a counter or repository.
 */
object NumberFormatter {

    /** Prefix, date part, and zero-padded counter, joined with `-`. Empty parts (no prefix,
     *  `DatePart.NONE`) are omitted instead of leaving a blank segment. */
    fun compose(pattern: NumberPattern, date: LocalDate, sequence: Long): String {
        val parts = mutableListOf<String>()
        pattern.prefix?.takeIf { it.isNotBlank() }?.let { parts += it }
        parts += dateSegments(pattern.datePart, date)
        parts += sequence.toString().padStart(pattern.counterWidth, '0')
        return parts.joinToString("-")
    }

    private fun dateSegments(datePart: DatePart, date: LocalDate): List<String> = when (datePart) {
        DatePart.NONE -> emptyList()
        DatePart.YEAR -> listOf("%04d".format(date.year))
        DatePart.YEAR_MONTH -> listOf("%04d".format(date.year), "%02d".format(date.monthValue))
        DatePart.YEAR_MONTH_DAY -> listOf("%04d".format(date.year), "%02d".format(date.monthValue), "%02d".format(date.dayOfMonth))
    }

    /** `''` for [ResetCycle.NEVER], otherwise the year — the reset counts per year regardless of
     *  how fine-grained the number's own date part is. */
    fun periodKey(resetCycle: ResetCycle, date: LocalDate): String = when (resetCycle) {
        ResetCycle.NEVER -> ""
        ResetCycle.YEARLY -> date.year.toString()
    }
}
