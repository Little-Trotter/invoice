package de.littletrotter.shared.documents

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties

/**
 * The two prepared document-content bundles (`documents/document-texts_{de,en}
 * .properties`), loaded once with an explicit UTF-8 reader — modelled on kotlin-xpdf's
 * `Labels` object (same rationale: the platform default charset is not guaranteed to be
 * UTF-8). A missing key is a defect, not a fallback to the other language — [text]/[format]
 * throw rather than silently mixing languages on one document.
 *
 * Lives in `kotlin-shared` rather than `kotlin-invoice` because the content is partly
 * *persisted* (the cancellation reference is composed into `Invoice.freeText`, i.e. BT-22, at cancellation
 * time), and both `kotlin-invoice` (composes it) and `kotlin-xpdf` (prints it, unchanged, as
 * part of the free-text block) need to reach the same bundle without a dependency inversion
 * onto the invoice module — the same reasoning [de.littletrotter.shared.reference
 * .resolveLocalizedText] already documents for the seller-note case. See the bundle files'
 * own header comment for the three-plane boundary this bundle sits on: `i18n/messages*` is the
 * *user's* language, `reports/invoice-labels_*` (kotlin-xpdf) are the print image's *labels*,
 * this bundle is document *content*.
 */
object DocumentTexts {
    private val bundles: Map<String, Map<String, String>> = mapOf(
        "de" to load("document-texts_de.properties"),
        "en" to load("document-texts_en.properties"),
    )

    // Internal (not private) so a unit test can drive the missing-resource branch
    // directly with a bogus filename; the object's own eager init above always succeeds (both
    // real bundle files ship on this project's classpath), so that branch was otherwise
    // unreachable from a test.
    internal fun load(fileName: String): Map<String, String> {
        val stream = DocumentTexts.javaClass.getResourceAsStream("/documents/$fileName")
            ?: error("Document text bundle $fileName not found on classpath")
        val props = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { props.load(it) }
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    /** The raw bundle value for [key]/[language], unformatted. Throws when either the language
     *  or the key inside it is unknown — never falls back to the other language. */
    fun text(key: String, language: String): String =
        bundles[language]?.get(key)
            ?: throw IllegalStateException(
                "Missing document text '$key' for language '$language' — both " +
                    "document-texts_*.properties must carry every key.",
            )

    /** [text] with its `{0}`, `{1}`, … placeholders substituted via `java.text.MessageFormat`. */
    fun format(key: String, language: String, vararg args: Any?): String =
        MessageFormat.format(text(key, language), *args)
}

/**
 * The same two date shapes `InvoiceReportContextBuilder.formatIsoDate` (kotlin-xpdf)
 * uses for a rendered document (`dd.MM.yyyy` for German, `d MMM yyyy` for English), kept next
 * to [DocumentTexts] so a caller composing document content in one language never has to grow
 * a second date-pattern implementation. kotlin-xpdf's own formatter stays as-is (it formats an
 * ISO string read off the wire DTO, a different input shape) — this one is for callers that
 * already hold a [LocalDate], such as [DocumentTexts]'s cancellation-reference caller.
 */
object DocumentDates {
    private val german: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val english: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun format(date: LocalDate, language: String): String =
        date.format(if (language == "en") english else german)
}
