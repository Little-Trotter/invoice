package de.littletrotter.ui.i18n

import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.Locale

/**
 * Serves the resolved i18n message bundle as flat JSON, so the (separately repo'd) Playwright E2E
 * suite can assert against the app's actual rendered strings instead of a hand-copied fixture that
 * would silently drift from `i18n/messages*.properties`. The endpoint carries no permit rule of
 * its own: the suite runs against dev/test-profile stacks (already fully permitted there), and in
 * production it sits behind the same login gate as every other app route.
 *
 * [I18nBundleMerger] reads the raw bundle files directly (never through
 * [TranslationService]), so it carries no term substitution of its own; this resource applies it
 * itself, the same way [UiTranslations] and [TranslationService] do, so `/api/i18n` reflects the
 * active [de.littletrotter.shared.wording.WordingProfile] — the one endpoint the Playwright suite
 * asserts against — without a second copy of the merge logic learning about wording at all.
 */
@Path("/api/i18n")
class I18nBundleResource @Inject constructor(
    private val wordingGate: WordingSettingsGate,
) {

    @GET
    @Path("/{locale}")
    @Produces(MediaType.APPLICATION_JSON)
    fun bundle(
        @PathParam("locale") locale: String,
    ): Map<String, String> {
        val profile = wordingGate.currentProfile()
        val resolvedLocale = if (locale == "de") Locale.GERMAN else Locale.ENGLISH
        return I18nBundleMerger.resolve(locale).mapValues { (_, value) ->
            TermSubstitution.apply(value) { term -> TermResolver.resolve(profile, resolvedLocale, term) }
        }
    }
}
