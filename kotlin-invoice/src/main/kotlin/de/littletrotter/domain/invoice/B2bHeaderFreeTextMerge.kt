package de.littletrotter.domain.invoice

/** One profile value that has no dedicated standard invoice property. */
data class UnmappedB2bHeaderValue(val code: String, val value: String?)

data class B2bHeaderFreeTextMergeResult(val freeText: String?, val importedCodes: Set<String>)

/**
 * Copies unmapped B2B header values into editable invoice free text once, each as its own line
 * holding the bare value (no field name, no colon — the same text a free-text chip inserts).
 * Persisting the imported codes separately is intentional: if an office manager edits or removes
 * the generated line, a later profile save must preserve that manual decision instead of
 * recreating it.
 */
fun mergeUnmappedB2bHeaderValues(
    freeText: String?,
    alreadyImportedCodes: Set<String>,
    values: List<UnmappedB2bHeaderValue>,
): B2bHeaderFreeTextMergeResult {
    val additions = values.mapNotNull { entry ->
        val value = entry.value?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (entry.code in alreadyImportedCodes) return@mapNotNull null
        entry to value
    }
    if (additions.isEmpty()) return B2bHeaderFreeTextMergeResult(freeText, alreadyImportedCodes)

    val base = freeText?.trimEnd().orEmpty()
    val appended = additions.joinToString("\n") { it.second }
    return B2bHeaderFreeTextMergeResult(
        freeText = if (base.isEmpty()) appended else "$base\n$appended",
        importedCodes = alreadyImportedCodes + additions.map { it.first.code },
    )
}
