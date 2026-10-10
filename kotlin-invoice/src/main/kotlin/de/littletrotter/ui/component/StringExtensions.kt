package de.littletrotter.ui.component

/**
 * The display value for an optional text field: the text value if present and
 * non-blank, or [placeholder] when absent or blank.
 */
internal fun String?.orPlaceholder(placeholder: String = "-"): String =
    this?.takeIf { it.isNotBlank() } ?: placeholder

/**
 * UI-2b — the value, or `null` when it is absent or blank: the form a query layer, a component's
 * nullable `value`, or an optional lookup expects for "nothing given", as opposed to
 * [orPlaceholder]'s "something to display".
 */
internal fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
