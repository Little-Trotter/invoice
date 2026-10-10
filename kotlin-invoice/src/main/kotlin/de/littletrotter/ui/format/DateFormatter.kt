package de.littletrotter.ui.format

import de.littletrotter.shared.GermanTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * R4 — the German `dd.MM.yyyy` / `dd.MM.yyyy HH:mm` conventions every date-bearing UI screen
 * already followed, but each screen built its own [DateTimeFormatter] to do it; one screen fell
 * out of step and rendered `yyyy-MM-dd HH:mm` (ISO order) to a German user. Mirrors
 * [MoneyFormatter]'s shape: a `locale`-defaulted `format` overload per input type, `null` handled
 * once instead of at every call site.
 *
 * An [OffsetDateTime] is converted to [GermanTime.ZONE] wall time before formatting,
 * regardless of the offset it was stored or received with — the same rule
 * [de.littletrotter.ui.view.journal.JournalValueFormatter] and
 * [de.littletrotter.application.invoice.GenerationJobService] already followed by hand. A
 * [LocalDateTime] carries no offset and is formatted as written: every call site that produces one
 * (e.g. `LocalDateTime.now(GermanTime.ZONE)`) already captured it in German wall time, so a second
 * conversion here would be a no-op at best and wrong if that assumption ever changes silently.
 */
object DateFormatter {

    private const val DATE_PATTERN = "dd.MM.yyyy"
    private const val DATE_TIME_PATTERN = "dd.MM.yyyy HH:mm"

    /** `ccc` is the stand-alone day-of-week pattern letter, so the abbreviation comes straight
     *  from locale (e.g. "Di" for German, "Tue" for English) rather than a hand-rolled table —
     *  the format-context `EEE` letter would render a trailing period in German ("Di."). */
    private const val DATE_WEEKDAY_PATTERN = "ccc, dd.MM.yyyy"

    /** The open-ended valid-from/valid-to marker every reference-data grid already used for a
     *  missing date; kept as the shared default so a caller with a different local convention
     *  (a receipt's own "-") stays free to elvis its own placeholder around a non-null call instead. */
    private const val PLACEHOLDER = "—"

    /** [date] alone, e.g. "07.09.2026". `null` renders as [PLACEHOLDER]. */
    fun format(date: LocalDate?, locale: Locale = Locale.GERMANY): String =
        date?.format(DateTimeFormatter.ofPattern(DATE_PATTERN, locale)) ?: PLACEHOLDER

    /** [date] with its weekday abbreviation, e.g. "Di, 07.09.2026" — makes a grid of dates read
     *  as a calendar shape (weekends, gaps) rather than a bare list. `null` renders as
     *  [PLACEHOLDER]. */
    fun formatWithWeekday(date: LocalDate?, locale: Locale = Locale.GERMANY): String =
        date?.format(DateTimeFormatter.ofPattern(DATE_WEEKDAY_PATTERN, locale)) ?: PLACEHOLDER

    /** [dateTime] as written, e.g. "07.09.2026 14:30" — no zone conversion, see this object's
     *  own KDoc on why a [LocalDateTime] never needs one. `null` renders as [PLACEHOLDER]. */
    fun format(dateTime: LocalDateTime?, locale: Locale = Locale.GERMANY): String =
        dateTime?.format(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN, locale)) ?: PLACEHOLDER

    /** [dateTime] converted to [GermanTime.ZONE] wall time before formatting. `null` renders as
     *  [PLACEHOLDER]. */
    fun format(dateTime: OffsetDateTime?, locale: Locale = Locale.GERMANY): String =
        dateTime?.let {
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN, locale).withZone(GermanTime.ZONE).format(it)
        } ?: PLACEHOLDER
}
