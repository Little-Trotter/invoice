package de.littletrotter.shared

/**
 * Extracts the guideline URN
 * (`rsm:ExchangedDocumentContext/ram:GuidelineSpecifiedDocumentContextParameter/ram:ID`) from a
 * generated CII XML document. This is the authoritative statement of the exact e-invoice
 * standard/version/profile actually produced; the UI must never parse XML itself (layer rule
 * Z3 / ArchUnit), so the worker extracts it once, right after
 * Mustang's XML generation (de.littletrotter.xpdf.impl.XmlExportServiceImpl.generate).
 *
 * A plain regex over the first element is enough — it appears once, near the top of the document
 * — rather than a full StAX parse; this must never throw: absent/malformed input just yields
 * `null` via the safe-call/[takeIf] chain below. No defensive
 * try/catch wrapper is needed here: `String(bytes, Charsets.UTF_8)` never throws (the JDK's Charset
 * decoder replaces malformed/unmappable sequences rather than raising), and [Regex.find] over
 * this fixed, non-backtracking pattern never throws for well-formed [Regex] usage either — the
 * catch blocks were provably unreachable, not just untested.
 */
private val GUIDELINE_URN_PATTERN = Regex(
    """<[\w.-]*:?GuidelineSpecifiedDocumentContextParameter[^>]*>\s*<[\w.-]*:?ID[^>]*>([^<]+)</[\w.-]*:?ID>""",
)

fun extractGuidelineUrn(xmlBytes: ByteArray): String? = extractGuidelineUrn(String(xmlBytes, Charsets.UTF_8))

fun extractGuidelineUrn(xml: String): String? =
    GUIDELINE_URN_PATTERN.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
