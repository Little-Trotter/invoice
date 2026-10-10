package de.littletrotter.ui.view.invoice

import de.littletrotter.ui.component.blankToNull
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * F2 — per-rule correction hints, resolved from `i18n/messages_de.properties`
 * (`validation.rule.<ruleId>.hint`). Deliberately requests [Locale.GERMAN] regardless of the UI
 * locale (German locale by default, English as backup —
 * these hints are not routed through the per-locale [de.littletrotter.ui.i18n.TranslationService]
 * lookup at all.
 *
 * Deliberately bypasses [de.littletrotter.ui.i18n.UiTranslations.tr]: that resolver returns a
 * `"!{key}!"` placeholder for a missing key, which is exactly wrong here — a rule without a
 * maintained hint is the normal case (the catalog only covers the rule IDs seen in this
 * project's own fixtures), not a defect to surface as broken-looking text.
 */
internal object ValidationHints {
    private const val BUNDLE_BASENAME = "i18n.messages"

    // Extracted so a unit test can exercise the missing-bundle branch directly with a
    // basename that has no backing properties file; the real basename above always resolves on
    // this project's own classpath, so that branch was otherwise unreachable in a test.
    internal fun loadBundle(basename: String, locale: Locale): ResourceBundle? =
        try {
            ResourceBundle.getBundle(basename, locale)
        } catch (_: MissingResourceException) {
            null
        }

    private val bundle: ResourceBundle? by lazy { loadBundle(BUNDLE_BASENAME, Locale.GERMAN) }

    /** Resolves the correction hint for [ruleId], or `null` when none is maintained. Never
     *  throws, never returns a placeholder. */
    fun resolve(ruleId: String?): String? = resolve(ruleId, bundle)

    // The bundle parameter is a testable seam: [bundle] above is a process-wide lazy
    // singleton that always resolves successfully on this project's classpath, so the `bundle
    // == null` short-circuit below could not otherwise be driven from a unit test.
    internal fun resolve(ruleId: String?, bundle: ResourceBundle?): String? {
        if (ruleId.isNullOrBlank()) return null
        val b = bundle ?: return null
        val key = "validation.rule.$ruleId.hint"
        return try {
            b.getString(key).blankToNull()
        } catch (_: MissingResourceException) {
            null
        }
    }
}

internal fun resolveValidationHint(ruleId: String?): String? = ValidationHints.resolve(ruleId)
