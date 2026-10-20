package de.littletrotter.application.invoice

/**
 * Minimal `resultRefs` JSON key extraction for [PrintJobOverviewQueryServiceImpl]'s row
 * projection ([de.littletrotter.application.dto.PrintJobOverviewRow]'s `pdfObjectKey`/
 * `xmlObjectKey` fields). Deliberately a small, self-contained duplicate of
 * [de.littletrotter.ui.view.invoice.parseGenerationJobResultRef]/[de.littletrotter.ui.view.invoice
 * .parseGenerationJobObjectKey] rather than a shared import: those live in the `ui` package, and
 * the application layer must not depend on it (`ArchitectureRulesTest`). The escape hatch
 * ("selecting the raw resultRefs string and parsing it in the service layer is acceptable")
 * sanctions exactly this — regex-only, no JSON library, matching the UI-side helpers' own
 * approach and their exact extraction pattern (same key/value regex).
 */
internal fun extractResultRef(resultRefs: String?, vararg keys: String): String? {
    if (resultRefs.isNullOrBlank()) return null
    return keys.firstNotNullOfOrNull { key ->
        resultRefPattern(key).find(resultRefs)?.groupValues?.get(1)
    }
}

/** The job's stored PDF object key (`resultRefs["objectKey"]`), or `null` when absent. */
internal fun derivePdfObjectKey(resultRefs: String?): String? = extractResultRef(resultRefs, "objectKey")

/**
 * The job's e-invoice XML object key: `resultRefs["zugferdObjectKey"]`, or `null` when it is
 * absent/blank — de.littletrotter.xpdf.JobConsumer.publishCompletedResult in kotlin-xpdf is the
 * only producer, and this is the only field it ever carries the XML key under (empty for the one
 * Factur-X outcome with no XML at all).
 *
 * N12 (coordinator review, 2026-09-11) removed BOTH the dead `xmlObjectKey`/`xrechnungObjectKey`/
 * `xml` fallback key names (the worker never wrote any of them) AND the `.pdf` -> `.xml`
 * derivation fallback: that derivation was reachable (the empty-`zugferdObjectKey` Factur-X
 * rejection case), but wrong for it — that case has no XML at all, and the row overload of
 * [de.littletrotter.ui.view.invoice.buildDownloadAction] never probes the store before rendering
 * an XML button for [de.littletrotter.application.dto.PrintJobOverviewRow.xmlObjectKey], so a
 * derived key there would have claimed an XML artifact exists when it never was written. An
 * absent key must mean "no XML button", not "guess one from the PDF key".
 */
internal fun deriveXmlObjectKey(resultRefs: String?): String? = extractResultRef(resultRefs, "zugferdObjectKey")

/** Compiled once per key: the extraction runs per job row in lists, and the key set is a handful
 *  of constants, so the cache stays tiny. */
private val RESULT_REF_PATTERNS = java.util.concurrent.ConcurrentHashMap<String, Regex>()

private fun resultRefPattern(key: String): Regex =
    RESULT_REF_PATTERNS.computeIfAbsent(key) { Regex("\"${Regex.escape(it)}\"\\s*:\\s*\"([^\"]+)\"") }
