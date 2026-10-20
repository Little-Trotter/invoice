package de.littletrotter.ui.i18n

import com.vaadin.flow.i18n.I18NProvider
import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
@Unremovable
class TranslationService @Inject constructor(
    private val wordingGate: WordingProfileQueryPort,
) : I18NProvider {

    companion object {
        val GERMAN: Locale = Locale.GERMAN
        val ENGLISH: Locale = Locale.ENGLISH
        private const val BUNDLE_BASENAME = "i18n.messages"
        private val log = Logger.getLogger(TranslationService::class.java)
    }

    // AP-EXC-1: a missing key is expected (typo, not-yet-translated string) and must not break
    // rendering — the "!{key}!" fallback below stays. But it must not be silent either, and with
    // getTranslation called on every render, logging unconditionally would spam the log for the
    // same missing key on every repaint. Each key is therefore warned about once per JVM lifetime.
    private val warnedMissingKeys = ConcurrentHashMap.newKeySet<String>()

    // German locale by default, English as backup (PO decision 2026-09-02,
    // : messages_de.properties carries the German values,
    // messages.properties (the ResourceBundle base/root) is now a complete English translation —
    // so the bundle actually resolved depends on the requested [locale] and must not be fixed at
    // construction time any more. ResourceBundle.getBundle does its own per-locale caching, so no
    // hand-rolled cache is needed here.
    override fun getProvidedLocales(): List<Locale> = listOf(GERMAN, ENGLISH)

    override fun getTranslation(key: String, locale: Locale, vararg params: Any): String {
        // getNoFallbackControl is load-bearing: plain getBundle falls back to the DEFAULT locale's
        // bundle before the base bundle, and DefaultLocaleInitializer pins the default to GERMANY —
        // an ENGLISH request would silently resolve messages_de instead of the English base.
        val bundle = ResourceBundle.getBundle(
            BUNDLE_BASENAME,
            locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )
        return try {
            val raw = bundle.getString(key)
            // Term substitution runs BEFORE MessageFormat: a {term:...} marker is not a
            // valid MessageFormat argument index and would otherwise throw (see
            // TermSubstitution's own KDoc).
            val substituted = TermSubstitution.apply(raw) { term -> TermResolver.resolve(wordingGate.currentProfile(), locale, term) }
            if (params.isEmpty()) substituted else MessageFormat.format(substituted, *params)
        } catch (e: MissingResourceException) {
            if (warnedMissingKeys.add(key)) {
                log.warn("Missing i18n key '$key' (bundle $BUNDLE_BASENAME), rendering as !{$key}!: ${e.message}", e)
            }
            "!{$key}!"
        }
    }
}
