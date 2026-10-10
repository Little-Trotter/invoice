package de.littletrotter.xpdf.impl

import de.littletrotter.shared.Currency
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/**
 * The two prepared label bundles (`invoice-labels_{de,en}.properties`), loaded once with
 * an explicit UTF-8 reader — the platform default is not guaranteed to be UTF-8,
 * and "Bitte kontaktieren Sie uns für unsere Bankverbindung." has umlauts. A missing key is a
 * defect, not a fallback to the other language — [label] throws rather than silently mixing
 * languages on one document.
 */
internal object Labels {
    private val bundles: Map<String, Map<String, String>> = mapOf(
        "de" to load("invoice-labels_de.properties"),
        "en" to load("invoice-labels_en.properties"),
    )

    private fun load(fileName: String): Map<String, String> {
        val stream = Labels.javaClass.getResourceAsStream("/reports/$fileName")
            ?: error("Label bundle $fileName not found on classpath")
        val props = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { props.load(it) }
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    fun label(key: String, language: String): String =
        bundles[language]?.get(key)
            ?: error("Missing label '$key' for language '$language' — both invoice-labels_*.properties must carry every key.")

    /**
     * The print-label loader's wording-profile overload: [profile] travels in the job
     * payload as `InvoiceDto.wordingProfile` and is resolved here the same way the UI/`/api/i18n`
     * resolve it (via [TermSubstitution]/[TermResolver]). No `invoice-labels_*.properties` value
     * carries a `{term:...}` marker yet — the printed invoice shows the position's own free-text
     * title, never a fixed category noun — so this is a no-op for every call today; it exists so a
     * future print label can opt in without a second lookup mechanism, exactly like [label]'s own
     * two-arg form stays the right choice for every legal/registry/footer text that never varies
     * by wording profile.
     */
    fun label(key: String, language: String, profile: WordingProfile): String =
        TermSubstitution.apply(label(key, language)) { term -> TermResolver.resolve(profile, localeFor(language), term) }

    private fun localeFor(language: String): Locale = if (language == "de") Locale.GERMAN else Locale.ENGLISH
}

// ── Small formatting helpers shared by InvoiceReportModelBuilder and InvoiceReportRowBuilders.kt.
//    Builder-private: only these two files call them. ──

internal fun isPresent(value: Any?): Boolean =
    value != null && (value !is String || value.isNotBlank())

internal fun orEmpty(value: Any?): String = value as? String ?: ""

internal fun amountUnit(value: Any?, unit: String): String {
    val s = orEmpty(value)
    return if (s.isBlank()) "" else "$s $unit"
}

internal fun amount(value: Any?, unit: String): String = amountUnit(value, unit)

/** Printed PDF currency unit: EUR uses the compact, unambiguous symbol; others keep ISO codes. */
internal fun printedCurrencyUnit(currencyCode: String): String =
    if (currencyCode == Currency.EUR.code) "€" else currencyCode
