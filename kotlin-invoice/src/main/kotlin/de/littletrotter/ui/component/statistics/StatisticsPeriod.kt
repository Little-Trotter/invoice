package de.littletrotter.ui.component.statistics

/**
 * The statistics page's Jahr/Monat/Quartal period selector as logic, not label text.
 * [labelKey] drives the combo's on-screen label through `tr()`; the statistics components and
 * their presenters pass the enum itself instead of comparing display strings.
 */
enum class StatisticsPeriod(val labelKey: String) {
    YEAR("statistics.period.year"),
    MONTH("statistics.period.month"),
    QUARTER("statistics.period.quarter"),
}
