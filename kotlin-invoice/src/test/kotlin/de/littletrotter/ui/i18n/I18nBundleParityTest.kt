package de.littletrotter.ui.i18n

import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.shared.wording.Term
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * German locale by default, English as backup (PO decision 2026-09-02): `i18n/messages.properties` (the ResourceBundle base, English) and `i18n/messages_de.properties` (German) must never drift apart — every key that exists in one MUST exist in the other, so a locale switch (or [UiTranslations]' component-less fallback) never silently loses a string. This guard fails loudly, with a readable diff of exactly which keys are missing on which side, rather than a runtime "!{key}!" placeholder discovered much later by a user or a screenshot test.
 *
 * The files are read as plain [Properties], not via [java.util.ResourceBundle], on purpose: a
 * `ResourceBundle` for the German locale chains to the English root as its *parent*, so
 * `ResourceBundle.keySet()` would report the union of both files' keys even if one of them were
 * missing half its entries — exactly the drift this test exists to catch.
 */
class I18nBundleParityTest {

    private fun loadProperties(resourceName: String): Properties {
        val properties = Properties()
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Bundle resource '$resourceName' not found on the classpath"
        }
        stream.use { properties.load(it) }
        return properties
    }

    private val english by lazy { loadProperties("i18n/messages.properties") }
    private val german by lazy { loadProperties("i18n/messages_de.properties") }

    @Test
    fun `english and german bundles carry exactly the same key set`() {
        val englishKeys = english.stringPropertyNames()
        val germanKeys = german.stringPropertyNames()

        val missingInGerman = (englishKeys - germanKeys).sorted()
        val missingInEnglish = (germanKeys - englishKeys).sorted()

        val diff = buildString {
            if (missingInGerman.isNotEmpty()) {
                appendLine("Keys present in messages.properties (EN) but missing from messages_de.properties (DE):")
                missingInGerman.forEach { appendLine("  - $it") }
            }
            if (missingInEnglish.isNotEmpty()) {
                appendLine("Keys present in messages_de.properties (DE) but missing from messages.properties (EN):")
                missingInEnglish.forEach { appendLine("  - $it") }
            }
        }

        assertTrue(missingInGerman.isEmpty() && missingInEnglish.isEmpty(), diff)
    }

    /** The "Änderungsjournal"/"Change journal" tab was renamed to
     *  "Änderungsprotokoll"/"Change log" (journal.view.title and the two tab labels); this pins
     *  that no value anywhere in either bundle still carries the old name. */
    @Test
    fun `the renamed journal view's old name is not used as a value anywhere`() {
        val staleGerman = german.stringPropertyNames().filter { german.getProperty(it) == "Änderungsjournal" }
        val staleEnglish = english.stringPropertyNames().filter { english.getProperty(it) == "Change journal" }

        assertTrue(staleGerman.isEmpty(), "messages_de.properties still has a value 'Änderungsjournal' at: $staleGerman")
        assertTrue(staleEnglish.isEmpty(), "messages.properties still has a value 'Change journal' at: $staleEnglish")
    }

    /** B2 (b2-remove-guided-wizard-rail) B7 — the outer step rail's eyebrow label and the
     *  hint-banner's now-gone "zur Führung" action link must not reappear as bundle keys. */
    @Test
    fun `the removed guided-rail i18n keys are absent from both bundles`() {
        val removedKeys = listOf(
            "invoiceDetail.entry.guided.sections",
            "invoiceDetail.entry.einvoice.guidedHint.action",
        )
        val staleEnglish = removedKeys.filter { english.containsKey(it) }
        val staleGerman = removedKeys.filter { german.containsKey(it) }

        assertTrue(staleEnglish.isEmpty(), "messages.properties still declares removed key(s): $staleEnglish")
        assertTrue(staleGerman.isEmpty(), "messages_de.properties still declares removed key(s): $staleGerman")
    }

    @Test
    fun `neither bundle is suspiciously empty`() {
        // A regression where one file is accidentally truncated (or replaced with an empty
        // placeholder) would otherwise pass the parity check above trivially — two empty sets are
        // still "the same key set". This is the sanity floor that catches that case.
        assertTrue(english.stringPropertyNames().size > 1000, "messages.properties has suspiciously few keys: ${english.size}")
        assertTrue(german.stringPropertyNames().size > 1000, "messages_de.properties has suspiciously few keys: ${german.size}")
    }

    /** A garbled placeholder — `{0` without a closing `}`, a stray brace — that
     *  [java.text.MessageFormat] would choke on at render time, where a developer would not
     *  notice it.
     *
     *  Asked of MessageFormat itself rather than counted with a regex: the count of `{n` against
     *  `{n}` occurrences also rejects every *valid* pattern whose argument carries a format type,
     *  `{0,choice,0#none|1#one|1<{0} many}` being the case this project actually uses for
     *  singular/plural. Constructing the pattern is the same parse the runtime performs, so this
     *  accepts exactly what [de.littletrotter.ui.i18n.UiTranslations] can render and rejects
     *  exactly what it cannot.
     *
     *  Only values that carry an argument at all are checked: a value without one is handed back
     *  verbatim (see `UiTranslations.resolveAgainst`) and never reaches MessageFormat, so a lone
     *  brace in such a value is plain text, not a broken pattern.
     *
     *  A `{term:...}` marker is stripped first: at runtime it is resolved by
     *  [de.littletrotter.shared.wording.TermSubstitution] BEFORE `MessageFormat` ever sees the
     *  value (see that class's own KDoc on why — `MessageFormat` itself would reject
     *  `term:product.singular` as an invalid, non-numeric argument index), so checking the raw,
     *  unsubstituted value here would flag every legitimate `{term:...} ... {0}` combination as
     *  broken. */
    private fun brokenPlaceholderKeys(properties: Properties): List<String> {
        val argumentPattern = Regex("\\{\\d+")
        val termMarkerPattern = Regex("\\{term:[a-zA-Z.]+}")
        return properties.stringPropertyNames().filter { key ->
            val value = termMarkerPattern.replace(properties.getProperty(key), "")
            argumentPattern.containsMatchIn(value) &&
                runCatching { MessageFormat(value, TranslationService.GERMAN) }.isFailure
        }.sorted()
    }

    @Test
    fun `no bundle value has a broken numbered placeholder`() {
        val brokenEnglish = brokenPlaceholderKeys(english)
        val brokenGerman = brokenPlaceholderKeys(german)

        assertTrue(
            brokenEnglish.isEmpty(),
            "messages.properties has broken {n} placeholders in: $brokenEnglish",
        )
        assertTrue(
            brokenGerman.isEmpty(),
            "messages_de.properties has broken {n} placeholders in: $brokenGerman",
        )
    }

    /** A lone, unescaped `'` in a value that also carries a `{n}` placeholder is a real bug, not
     *  a style nit: such values are run through [java.text.MessageFormat.format] whenever a
     *  caller supplies parameters (see [UiTranslations.resolveAgainst]/[TranslationService]), and
     *  MessageFormat treats an odd number of single quotes as opening an unterminated literal
     *  quote that runs to the end of the pattern — silently dropping the apostrophe from the
     *  rendered text (`"seller's"` renders as `"sellers"`). A correctly escaped apostrophe is
     *  doubled (`''`), which is intentionally NOT flagged here. */
    private fun strayApostropheKeys(properties: Properties): List<String> {
        val placeholderPattern = Regex("\\{\\d+}")
        return properties.stringPropertyNames().filter { key ->
            val value = properties.getProperty(key)
            if (!placeholderPattern.containsMatchIn(value)) return@filter false
            // Doubled quotes are the correct escaping and must not be counted; count only quotes
            // that are not part of a '' pair.
            val withoutEscapedPairs = value.replace("''", "")
            withoutEscapedPairs.contains('\'')
        }.sorted()
    }

    @Test
    fun `no placeholder-bearing value has an unescaped apostrophe`() {
        val strayEnglish = strayApostropheKeys(english)
        val strayGerman = strayApostropheKeys(german)

        assertTrue(
            strayEnglish.isEmpty(),
            "messages.properties has an unescaped ' next to a {n} placeholder (double it to '') in: $strayEnglish",
        )
        assertTrue(
            strayGerman.isEmpty(),
            "messages_de.properties has an unescaped ' next to a {n} placeholder (double it to '') in: $strayGerman",
        )
    }

    @Test
    fun `bundle files live where the ResourceBundle fallback chain expects them`() {
        // Cheap guard against a future accidental rename/move: both files must sit directly under
        // i18n/ on the module's resource root for `ResourceBundle.getBundle("i18n.messages", ...)`
        // to find them at all.
        val root = File("src/main/resources/i18n")
        assertTrue(File(root, "messages.properties").isFile, "i18n/messages.properties is missing")
        assertTrue(File(root, "messages_de.properties").isFile, "i18n/messages_de.properties is missing")
    }

    /**
     * Resolution, not just parity: a GERMAN request yields the German value and an ENGLISH
     * request the English one — EVEN while the JVM default locale is German. Plain
     * `ResourceBundle.getBundle` falls back to the DEFAULT locale's bundle before the base
     * bundle, which would silently serve German to English requests; [TranslationService]
     * guards that with `getNoFallbackControl`, and this test pins it.
     */
    @org.junit.jupiter.api.Test
    fun `an english request resolves the english base bundle, never the german default fallback`() {
        val service = TranslationService(WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE))
        val previousDefault = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            org.junit.jupiter.api.Assertions.assertEquals(
                "Neue Rechnung erstellen",
                service.getTranslation("invoice.dialog.title", TranslationService.GERMAN),
            )
            org.junit.jupiter.api.Assertions.assertEquals(
                "Create new invoice",
                service.getTranslation("invoice.dialog.title", TranslationService.ENGLISH),
            )
        } finally {
            java.util.Locale.setDefault(previousDefault)
        }
    }

    /** Border case: "both profiles missing a term key -> parity test red at build time,
     *  never at runtime". Scans every `{term:...}` marker actually used in either bundle and
     *  resolves it against both [WordingProfile]s and both bundle locales, via the exact same
     *  [TermResolver] the running app uses — a term referenced by a bundle value but missing from
     *  `i18n/wording-terms*.properties` (kotlin-shared) for one profile/locale combination fails
     *  this test instead of silently rendering `!{term:...}!` in production. */
    @Test
    fun `every term marker used in either bundle resolves for both wording profiles and both locales`() {
        val markerPattern = Regex("\\{term:([a-zA-Z.]+)}")
        val usedNames = (english.stringPropertyNames() + german.stringPropertyNames())
            .flatMap { key -> markerPattern.findAll(english.getProperty(key, "") + german.getProperty(key, "")).map { it.groupValues[1] } }
            .toSet()

        assertTrue(usedNames.isNotEmpty(), "no bundle value uses a {term:...} marker — RK-7's own rewrite is expected to add some")

        val gaps = mutableListOf<String>()
        for (name in usedNames.sorted()) {
            val term = Term.fromPlaceholder(name)
            if (term == null) {
                gaps += "'$name' is not a known Term placeholder"
                continue
            }
            for (profile in WordingProfile.entries) {
                for (locale in listOf(Locale.GERMAN, Locale.ENGLISH)) {
                    if (TermResolver.resolve(profile, locale, term).isNullOrBlank()) {
                        gaps += "$profile/${locale.language}/$name has no curated text in wording-terms*.properties"
                    }
                }
            }
        }

        assertTrue(gaps.isEmpty(), "term gaps found:\n" + gaps.joinToString("\n"))
    }
}
