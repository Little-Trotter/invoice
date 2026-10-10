package de.littletrotter.xpdf.impl

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * The two prepared label bundles of the offer document (`quote-labels_{de,en}.properties`),
 * loaded once with an explicit UTF-8 reader. Wording that offers share with invoices (table
 * headers, totals, footer) comes from [Labels]; only offer-specific wording lives here. A missing
 * key is a defect, so [label] throws instead of falling back to the other language.
 */
internal object QuoteLabels {
    private val bundles: Map<String, Map<String, String>> = mapOf(
        "de" to load("quote-labels_de.properties"),
        "en" to load("quote-labels_en.properties"),
    )

    private fun load(fileName: String): Map<String, String> {
        val stream = QuoteLabels.javaClass.getResourceAsStream("/reports/$fileName")
            ?: error("Label bundle $fileName not found on classpath")
        val props = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { props.load(it) }
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    fun label(key: String, language: String): String =
        bundles[language]?.get(key)
            ?: error("Missing label '$key' for language '$language' — both quote-labels_*.properties must carry every key.")

    /** The keys a bundle carries; lets a test prove both languages stay in step. */
    fun keys(language: String): Set<String> = bundles.getValue(language).keys
}
