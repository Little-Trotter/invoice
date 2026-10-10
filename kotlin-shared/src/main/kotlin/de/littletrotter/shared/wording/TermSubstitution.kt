package de.littletrotter.shared.wording

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Resolves every `{term:…}` marker in a bundle value against resolveTerm, the one
 * indirection the wording-profile mechanism adds on top of the existing i18n lookup (see
 * the wording-profile spec's own "Mechanism" section). Callers MUST apply this before any
 * [java.text.MessageFormat] pass over the same value: `MessageFormat` parses `{…}` itself and
 * rejects a non-numeric argument index such as `term:trip.singular` outright, so a value carrying
 * both a term marker and a `{0}` argument only works if the term marker is gone by the time
 * `MessageFormat.format` runs.
 *
 * An unresolvable marker (an unknown placeholder name, or resolveTerm returning `null` for a
 * legitimately missing entry) degrades to a `!{term:name}!` placeholder — never an exception —
 * exactly like a missing i18n key elsewhere in this app, and is logged once per distinct name for
 * the run's lifetime rather than once per render.
 */
object TermSubstitution {
    private val MARKER = Regex("\\{term:([a-zA-Z.]+)}")
    private val log = LoggerFactory.getLogger(TermSubstitution::class.java)
    private val loggedUnknown = ConcurrentHashMap.newKeySet<String>()

    fun apply(value: String, resolveTerm: (Term) -> String?): String {
        if (!value.contains("{term:")) return value
        return MARKER.replace(value) { match ->
            val name = match.groupValues[1]
            Term.fromPlaceholder(name)?.let(resolveTerm) ?: run {
                if (loggedUnknown.add(name)) {
                    log.warn("Unresolved term placeholder '{}' - rendering as !{{term:{}}}!", name, name)
                }
                "!{term:$name}!"
            }
        }
    }
}
