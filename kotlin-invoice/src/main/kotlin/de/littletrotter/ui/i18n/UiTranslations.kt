package de.littletrotter.ui.i18n

import com.vaadin.flow.component.Component
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.inject.spi.CDI
import java.text.MessageFormat
import java.util.Enumeration
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import org.jboss.logging.Logger

object UiTranslations {
    private const val BUNDLE_BASENAME = "i18n.messages"

    private val log = Logger.getLogger(UiTranslations::class.java)

    /** Keys already reported by [resolveAgainst] as missing from the fallback bundle — a missing
     *  key must not break rendering, but re-logging it on every render of the same view would
     *  flood the log without adding information, so each key is warned about once per JVM run. */
    private val loggedMissingKeys = ConcurrentHashMap.newKeySet<String>()

    private val fallbackBundle: ResourceBundle by lazy {
        bundleOrPlaceholder(loadBundleOrNull(BUNDLE_BASENAME, TranslationService.GERMAN))
    }

    /** [bundle] itself, or — when the whole bundle is missing from the classpath (packaging/
     *  deployment defect, not a single typo'd key) — the [placeholderBundle], after an error log:
     *  every translation then degrades to "!{key}!" for the rest of the JVM's life, which is
     *  worth an error, not silent degradation. */
    internal fun bundleOrPlaceholder(bundle: ResourceBundle?): ResourceBundle = bundle ?: run {
        log.error("Fallback resource bundle '$BUNDLE_BASENAME' could not be loaded; all translations will render as placeholders")
        placeholderBundle()
    }

    /** The bundle for [baseName]/[locale], or null when no bundle exists on the classpath. */
    internal fun loadBundleOrNull(baseName: String, locale: java.util.Locale, control: ResourceBundle.Control? = null): ResourceBundle? =
        try {
            if (control == null) ResourceBundle.getBundle(baseName, locale) else ResourceBundle.getBundle(baseName, locale, control)
        } catch (_: MissingResourceException) {
            null
        }

    /** The `!{key}!` placeholder every unresolved/unknown key renders as — the single definition
     *  site every caller below compares/returns against, so the literal itself is never repeated. */
    internal fun placeholderFor(key: String): String = "!{$key}!"

    /** Renders every key as its "!{key}!" placeholder; the last resort when no bundle loads. */
    internal fun placeholderBundle(): ResourceBundle = object : ResourceBundle() {
        override fun handleGetObject(key: String): Any = placeholderFor(key)
        override fun getKeys(): Enumeration<String> = java.util.Collections.emptyEnumeration()
    }

    fun tr(component: Component?, key: String, vararg params: Any): String {
        val translated = component?.getTranslation(key, *params) ?: ""
        if (translated.isNotBlank() && !translated.startsWith("!")) {
            return translated
        }
        return fallback(key, *params)
    }

    fun tr(key: String, vararg params: Any): String = fallback(key, *params)

    /**
     * Resolves a translation for an explicit supported locale even when the component that owns
     * the label is not attached to a UI yet (dialogs build their fields before Dialog.open).
     * The no-fallback control is intentional: an English request must never silently select the
     * German bundle through the JVM default locale.
     */
    fun trOrNullForLocale(locale: java.util.Locale, key: String, vararg params: Any): String? {
        // BUNDLE_BASENAME's root bundle (messages.properties) always ships with this application,
        // so this per-locale lookup never actually returns null (Java's own candidate-locale chain
        // always ends at root) — [fallbackBundle]'s own lazy init already carries the one genuine
        // "whole bundle missing from the classpath" fallback for this exact resource, so a second,
        // redundant fallback here would only ever guard a scenario [fallbackBundle] itself already
        // failed at construction time (`!!` documents that shared invariant, not a new risk).
        val bundle = loadBundleOrNull(
            BUNDLE_BASENAME,
            locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )!!
        return resolveAgainst(bundle, key, *params, termLocale = locale).takeUnless { it == placeholderFor(key) }
    }

    /**
     * Same resolution as [tr], but returns `null` instead of the `"!{key}!"` placeholder when
     * [key] is unknown to the bundle. [tr]'s placeholder is fine as a last-resort label on its
     * own, but a caller that wants to fall back to a DIFFERENT string when the key doesn't
     * resolve — e.g. [de.littletrotter.ui.view.invoice.buildValidationReportMarkdown] falling
     * back to a finding's already-rendered raw text — needs to tell "resolved" apart from
     * "unknown key", which the placeholder string alone conflates.
     */
    fun trOrNull(component: Component?, key: String, vararg params: Any): String? =
        tr(component, key, *params).takeUnless { it == placeholderFor(key) }

    private fun fallback(key: String, vararg params: Any): String = resolveAgainst(fallbackBundle, key, *params)

    /** The bundle-lookup rule [fallback] applies: blank and missing keys render as the `!{key}!`
     *  placeholder, parameters go through [MessageFormat]. A `{term:...}` marker is
     *  resolved first (never a valid MessageFormat argument index, see [TermSubstitution]'s KDoc),
     *  against [currentProfile] and this fallback bundle's own GERMAN locale. */
    private fun resolveAgainst(
        bundle: ResourceBundle,
        key: String,
        vararg params: Any,
        termLocale: java.util.Locale = TranslationService.GERMAN,
    ): String {
        return try {
            val raw = bundle.getString(key)
            if (raw.isBlank()) return placeholderFor(key)
            val substituted = TermSubstitution.apply(raw) { term -> TermResolver.resolve(currentProfile(), termLocale, term) }
            if (params.isEmpty()) substituted else MessageFormat.format(substituted, *params)
        } catch (_: MissingResourceException) {
            // A missing key must not break rendering, so this stays a placeholder — but log it
            // once per key (not per render) so an untranslated string is discoverable instead of
            // silently shipping "!{key}!" to users forever.
            if (loggedMissingKeys.add(key)) {
                log.warn("Missing translation key '$key' in bundle '$BUNDLE_BASENAME'")
            }
            placeholderFor(key)
        }
    }

    /**
     * [UiTranslations] is a plain Kotlin `object`, outside CDI, so [WordingSettingsGate] is
     * looked up on demand via [CDI.current], the same bridge [de.littletrotter.ui.notification
     * .UiNotifications.persistError] already uses for [de.littletrotter.application.message
     * .MessageCenterService]. In a plain unit test (no Quarkus/Arc container running) that lookup
     * itself throws — caught here and degraded to [WordingProfile.DEFAULT], exactly the fail-open
     * contract [WordingSettingsGate] itself applies to a database outage.
     */
    private fun currentProfile(): WordingProfile = try {
        CDI.current().select(WordingSettingsGate::class.java).get().currentProfile()
    } catch (ignored: IllegalStateException) {
        WordingProfile.DEFAULT
    }
}
