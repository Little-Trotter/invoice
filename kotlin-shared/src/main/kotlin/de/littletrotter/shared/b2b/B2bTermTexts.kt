package de.littletrotter.shared.b2b

import java.util.Locale
import java.util.ResourceBundle

/**
 * Resolves a [B2bTerm]'s display name / spec definition text, moved here (out of
 * kotlin-invoice's `messages.properties`) so any kotlin-shared-dependent module — validation
 * reports, logs, datagen, e2e assertions, not only the Vaadin UI — can render a term itself
 * instead of shipping a bare BT code and punting the German name to "resolves UI-side".
 *
 * Backed by the `i18n.b2b-terms` bundle pair (`b2b-terms.properties` = German base bundle,
 * `b2b-terms_en.properties` = English). Both are loaded with
 * [ResourceBundle.Control.getNoFallbackControl] for the same reason `TranslationService` uses it:
 * a JVM whose *default* locale is English must not silently resolve a GERMAN request to the `_en`
 * bundle. [englishBundle] keeps its normal parent chain (`b2b-terms_en` → base) — dropping only
 * the default-locale detour, not the specific-locale one — which [definitionOrNull] relies on: an
 * ENGLISH request for a term without a curated English definition still returns the German one.
 */
object B2bTermTexts {
    private const val BUNDLE_BASENAME = "i18n.b2b-terms"

    private val noDefaultLocaleFallback: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private val germanBundle: ResourceBundle by lazy {
        ResourceBundle.getBundle(BUNDLE_BASENAME, Locale.GERMAN, noDefaultLocaleFallback)
    }

    private val englishBundle: ResourceBundle by lazy {
        ResourceBundle.getBundle(BUNDLE_BASENAME, Locale.ENGLISH, noDefaultLocaleFallback)
    }

    /**
     * [term]'s short display name for [locale] (GERMAN → the base bundle, anything else → the
     * English one). A German request that is blank/missing in the base bundle — most of the 782
     * generated terms have no curated German name — falls back to the English bundle (all
     * 782 names exist there); still missing (should be unreachable given that guarantee) falls
     * back to [B2bTerm.officialCode]. Never returns a `!{key}!`-style placeholder.
     */
    fun name(term: B2bTerm, locale: Locale): String =
        resolveName(term, bundleFor(locale), { englishBundle }.takeIf { locale == Locale.GERMAN })

    /**
     * [name]'s bundle walk, split out (same testability pattern as [assertB2bTermBindingInvariant])
     * so the last-resort [B2bTerm.officialCode] branch — unreachable through [name] itself, since
     * every term has an English name — stays unit-testable with deliberately empty bundles.
     * [englishFallback] is a supplier, not a bundle, so a lookup already satisfied by [primary]
     * never forces the English bundle's lazy load.
     */
    internal fun resolveName(
        term: B2bTerm,
        primary: ResourceBundle,
        englishFallback: (() -> ResourceBundle)?,
    ): String {
        stringOrNull(primary, term.nameKey)?.let { return it }
        englishFallback?.let { fallback -> stringOrNull(fallback(), term.nameKey)?.let { return it } }
        return term.officialCode
    }

    /**
     * [term]'s spec definition text for [locale], or `null` when blank/missing — the signal
     * `B2bFieldEditorPanel.updateSpecInfoblock` needs to hide the infoblock entirely rather than
     * show a placeholder. No *cross-language* fallback of its own: a GERMAN request only ever
     * consults the base bundle and is `null` for the ~747 uncurated terms. An ENGLISH request
     * consults [englishBundle], whose parent chain (see class KDoc) means it still returns the
     * German definition where one exists and no English one was curated.
     */
    fun definitionOrNull(term: B2bTerm, locale: Locale): String? =
        stringOrNull(bundleFor(locale), term.specDefinitionKey)

    private fun bundleFor(locale: Locale): ResourceBundle = if (locale == Locale.GERMAN) germanBundle else englishBundle

    /** [ResourceBundle.containsKey] already walks the parent chain, so a plain guard (rather than
     *  catching [java.util.MissingResourceException]) is both simpler and cheaper here. */
    private fun stringOrNull(bundle: ResourceBundle, key: String): String? =
        if (bundle.containsKey(key)) bundle.getString(key).takeUnless { it.isBlank() } else null
}
