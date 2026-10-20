package de.littletrotter.ui.components.freetext

import com.vaadin.flow.component.UI
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * The editor's own toolbar/helper/chip vocabulary. A reusable component jar ships its own
 * wording instead of asking every host application to translate editor chrome that never differs
 * between call sites — the host still supplies the field label itself ([HtmlFreeTextEditor]'s
 * `labelText` constructor parameter), only this fixed set of button/heading/tooltip strings comes
 * from here.
 *
 * Backed by the `de.littletrotter.ui.components.i18n.freetext` bundle pair: `freetext.properties`
 * (no locale suffix) carries the German text, `freetext_en.properties` the English one — the same
 * split `de.littletrotter.shared.b2b.B2bTermTexts` uses, German as the un-suffixed base rather
 * than a `_de` file. [ResourceBundle.Control.getNoFallbackControl] is required for the same reason
 * `TranslationService`/`B2bTermTexts` need it: without it, a JVM whose *default* locale differs
 * from the requested one gets silently redirected through that default before ever reaching the
 * base bundle. Read per call rather than cached, so a session's live locale switch (English/German)
 * is honoured immediately instead of pinning to whatever locale was current at first use.
 */
internal object FreeTextTexts {
    private const val BUNDLE_BASENAME = "de.littletrotter.ui.components.i18n.freetext"
    private val noFallback: ResourceBundle.Control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    fun tr(key: String, vararg params: Any): String {
        val locale = UI.getCurrent()?.locale ?: Locale.GERMAN
        val bundle = ResourceBundle.getBundle(BUNDLE_BASENAME, locale, noFallback)
        val raw = bundle.getString(key)
        return if (params.isEmpty()) raw else MessageFormat.format(raw, *params)
    }
}
