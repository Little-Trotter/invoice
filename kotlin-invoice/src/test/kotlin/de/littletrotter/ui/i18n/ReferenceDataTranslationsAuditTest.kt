package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.ResourceBundle

class ReferenceDataTranslationsAuditTest {

    /**
     * Recognized key-usage forms in view sources:
     * 1. translation calls: `tr("key")`, `getTranslation("key")`, `UiTranslations.tr("key")`
     * 2. key-provider template methods of the referencedata CRUD base:
     *    `override fun get...Key(): String = "key"` / `fun get...Placeholder(): String = "key"`
     * 3. shared helper from TranslationProvider interface: calls to `tr("key")` recognized
     *    by the existing `\btr\(` pattern; the interface provides the shared implementation
     * 4. keys extracted into a `const val` (S1192 literal extraction)
     */
    private val usagePatterns = listOf(
        Regex("(?:getTranslation|UiTranslations\\.tr|\\btr)\\(\\s*(?:this\\s*,\\s*)?\\\"([^\\\"]+)\\\""),
        Regex("fun\\s+get\\w+(?:Key|Placeholder)\\(\\)\\s*(?::\\s*String\\s*)?=\\s*\\\"([^\\\"]+)\\\""),
        Regex("const\\s+val\\s+\\w+\\s*=\\s*\\\"([^\\\"]+)\\\""),
    )

    private fun capturedReferenceDataKeys(): Set<String> {
        // RDS-1 — widened from `ui/view` to all of `ui`: shared components (e.g. `CrudBadges`,
        // under `ui/component`) now also call `tr("referenceData....")` literally, not just the
        // reference-data views themselves.
        val viewRoot = File("src/main/kotlin/de/littletrotter/ui")
        return viewRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                usagePatterns.asSequence().flatMap { pattern ->
                    pattern.findAll(text).map { it.groupValues[1] }
                }
            }
            .filter { it.startsWith("referenceData.") }
            .toSet()
    }

    /** Literal keys, checked verbatim against the bundle. */
    private fun literalKeys(): Set<String> = capturedReferenceDataKeys().filterNot { it.contains('$') }.toSet()

    /** `NumberRangesTab.enumLabel` builds its keys via interpolation
     *  (`referenceData.numberRange.$group.${value.name}`); the audit cannot resolve those
     *  verbatim, but everything before the first `$` is a genuine shared prefix: every bundle
     *  key under it counts as used, and at least one such key must exist per prefix so a typo
     *  in the template itself still fails the audit. */
    private fun dynamicKeyPrefixes(): Set<String> =
        capturedReferenceDataKeys().filter { it.contains('$') }.map { it.substringBefore('$') }.toSet()

    @Test
    fun `all reference data translation keys used in views exist in german bundle`() {
        val bundle = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
        val usedKeys = literalKeys()

        val missingKeys = usedKeys.filterNot { bundle.containsKey(it) }

        assertTrue(missingKeys.isEmpty(), "Missing German translations for keys: $missingKeys")

        // also spot-check that at least one key is evaluated
        assertTrue(usedKeys.isNotEmpty(), "No referenceData keys found in UI view sources")

        val bundleKeys = bundle.keySet()
        for (prefix in dynamicKeyPrefixes()) {
            assertTrue(
                bundleKeys.any { it.startsWith(prefix) },
                "No German translation exists under the dynamic key prefix '$prefix'",
            )
        }
    }

    @Test
    fun `no unused reference data keys in german bundle`() {
        val bundle = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
        val usedKeys = literalKeys()
        val prefixes = dynamicKeyPrefixes()

        val bundleKeys = bundle.keySet().filter { it.startsWith("referenceData.") }.toSet()

        val unused = (bundleKeys - usedKeys).filterNot { key -> prefixes.any { key.startsWith(it) } }

        assertTrue(unused.isEmpty(), "Unused referenceData keys in german bundle: $unused")
    }

    // The `B2bTerm.nameKey`/`specDefinitionKey` bundle-existence audits that used to live
    // here moved with the bundles themselves: kotlin-invoice's `messages.properties` no longer
    // carries the `b2b.term.*`/`b2b.catalog.*` block, so this class (a `referenceData.*`-key audit
    // over kotlin-invoice's own bundle) is no longer the right place to check them. That coverage
    // now lives in kotlin-shared's `B2bTermTextsTest`, next to the `i18n.b2b-terms` bundles it
    // actually audits.
}
