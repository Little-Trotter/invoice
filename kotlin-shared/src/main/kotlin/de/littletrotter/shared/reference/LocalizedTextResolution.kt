package de.littletrotter.shared.reference

/**
 * The single resolution rule for a text that may exist in more than one language.
 *
 * Lives in `kotlin-shared` rather than `kotlin-invoice` so `kotlin-xpdf` can call it too, without
 * a dependency inversion onto the invoice module. [de.littletrotter.application.invoice
 * .InvoiceDtoMapper] calls it exactly where `unitLabel` already follows the frozen document
 * language.
 *
 * Resolution order: exact match on [documentLanguage] → [fallbackLanguage] → `null`. Never
 * composed from two languages, never a partial match on a region subtag (`en-GB` does not match
 * `en`) — [texts] keys and [documentLanguage]/[fallbackLanguage] are compared verbatim.
 *
 * A blank stored value cannot occur once a localized text group is persisted (the `localized_text`
 * table's `CHECK` rejects it), but this function trims and treats a blank entry as absent anyway
 * — defensive, and it makes the function total for callers holding unpersisted data (e.g. a group
 * under construction in the UI).
 */
fun resolveLocalizedText(texts: Map<String, String>, documentLanguage: String, fallbackLanguage: String): String? =
    texts[documentLanguage]?.trim()?.ifEmpty { null }
        ?: texts[fallbackLanguage]?.trim()?.ifEmpty { null }
