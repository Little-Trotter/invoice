package de.littletrotter.shared

/** Meal deduction flags for a per-diem day. */
data class PerDiemMealDeductions(
    val breakfast: Boolean,
    val lunch: Boolean,
    val dinner: Boolean,
)

/** Translated labels used to render the description shown for one per-diem day. */
data class PerDiemDescriptionLabels(
    val arrivalDeparture: String,
    val fullDay: String,
    val breakfastDeduction: String,
    val lunchDeduction: String,
    val dinnerDeduction: String,
)

/** Trims names, ignores unnamed entries and retains first-seen order for display purposes. */
fun distinctPerDiemParticipants(participants: Iterable<String?>): List<String> =
    participants.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()

/**
 * One per-diem day's own content for [formatPerDiemDescription] — everything that comes from the
 * day itself, as opposed to [PerDiemDescriptionLabels]' surrounding i18n texts. The trip
 * overview and its workbook export (`TripPerDiemOverview`, `PerDiemSheetWriter`) each already
 * assemble exactly this set from one day plus the overview-wide "more than one participant" flag.
 */
data class PerDiemDayDescriptionInput(
    val description: String?,
    val isArrivalDeparture: Boolean,
    val deductions: PerDiemMealDeductions,
    val participant: String?,
    val includeParticipant: Boolean,
)

/**
 * Builds the description shared by the trip overview and its workbook export. The participant is
 * included only when the surrounding overview contains more than one named participant.
 */
fun formatPerDiemDescription(
    day: PerDiemDayDescriptionInput,
    labels: PerDiemDescriptionLabels,
): String {
    val deductionTexts = buildList {
        if (day.deductions.breakfast) add(labels.breakfastDeduction)
        if (day.deductions.lunch) add(labels.lunchDeduction)
        if (day.deductions.dinner) add(labels.dinnerDeduction)
    }.joinToString(" ")
    return listOfNotNull(
        day.description?.trim()?.takeIf { it.isNotEmpty() },
        if (day.isArrivalDeparture) labels.arrivalDeparture else labels.fullDay,
        deductionTexts.takeIf { it.isNotEmpty() },
        day.participant?.trim()?.takeIf { day.includeParticipant && it.isNotEmpty() },
    ).joinToString(" · ")
}
