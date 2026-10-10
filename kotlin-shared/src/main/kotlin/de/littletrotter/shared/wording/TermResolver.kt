package de.littletrotter.shared.wording

import java.util.Locale
import java.util.ResourceBundle

/**
 * Resolves a [Term]'s text for a [WordingProfile]/[Locale] pair, backed by the
 * `i18n.wording-terms` bundle pair (`wording-terms.properties` = German base bundle,
 * `wording-terms_en.properties` = English) — the same split [de.littletrotter.shared.b2b
 * .B2bTermTexts] already established in this module, so any kotlin-shared-dependent module
 * (kotlin-invoice's UI/export layer, kotlin-xpdf's print layer) resolves a term the same way
 * without a second lookup mechanism.
 *
 * Both bundles are loaded with [ResourceBundle.Control.getNoFallbackControl] for the reason
 * `TranslationService` documents for its own bundle: a JVM whose *default* locale is English must
 * not silently resolve a GERMAN request to the base (German) bundle by way of the default-locale
 * detour — only the exact requested locale's own bundle is consulted.
 */
object TermResolver {
    private const val BUNDLE_BASENAME = "i18n.wording-terms"

    private val noDefaultLocaleFallback: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private val germanBundle: ResourceBundle by lazy {
        ResourceBundle.getBundle(BUNDLE_BASENAME, Locale.GERMAN, noDefaultLocaleFallback)
    }

    private val englishBundle: ResourceBundle by lazy {
        ResourceBundle.getBundle(BUNDLE_BASENAME, Locale.ENGLISH, noDefaultLocaleFallback)
    }

    /** `null` only if [profile]/[term] has no curated text at all in [locale]'s bundle — the
     *  parity test (`I18nBundleParityTest`) keeps that unreachable for every term this app's
     *  bundles actually reference. */
    fun resolve(profile: WordingProfile, locale: Locale, term: Term): String? {
        val bundle = if (locale == Locale.GERMAN) germanBundle else englishBundle
        val key = "term.${profile.name.lowercase(Locale.ROOT)}.${term.placeholder}"
        return if (bundle.containsKey(key)) bundle.getString(key).takeUnless { it.isBlank() } else null
    }
}
