package de.littletrotter.application.settings

import de.littletrotter.application.settings.port.inbound.SettingChangeInfo
import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.application.shared.ShortLivedSettingsCache
import de.littletrotter.domain.repository.SystemSettingRepository
import de.littletrotter.domain.settings.SystemSetting
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import de.littletrotter.application.settings.port.inbound.ManageWordingSettingsUseCase

/**
 * The one place that answers "which wording profile is active right now?", twin of
 * [FeatureSettingsGate] down to the cache timings (SPEC-SET-1 mechanics): a [SystemSetting] row
 * (key [KEY]) overrides the instance's own `littletrotter.wording.profile` env default live, with
 * no restart.
 *
 * **Fail-open.** A missing row (fresh install, nobody has ever switched it) or an unreachable
 * database falls back to [envDefault] — never an exception, so a database hiccup never blanks the
 * whole UI's vocabulary mid-render.
 *
 * **Unknown env value.** `littletrotter.wording.profile`/`LITTLETROTTER_WORDING_PROFILE` naming
 * anything other than a [WordingProfile] entry falls back to [WordingProfile.DEFAULT] with one
 * WARN — logged once per gate instance (a CDI application-scoped singleton), not once per lookup.
 *
 * **Short-lived cache.** [currentProfile] is read on every i18n lookup — without the cache that
 * would be one database read per rendered string; [invalidate] makes a flip visible in the acting
 * session immediately, exactly like [FeatureSettingsGate.setEnabled].
 */
@ApplicationScoped
class WordingSettingsGate @Inject constructor(
    private val repository: SystemSettingRepository,
    @param:ConfigProperty(name = "littletrotter.wording.profile", defaultValue = "HANDWERK")
    private val configuredDefault: String,
    private val time: TimeSource,
) : WordingProfileQueryPort, ManageWordingSettingsUseCase {

    /** [configuredDefault] parsed once, memoized: an unknown value degrades to
     *  [WordingProfile.DEFAULT] with one WARN (border case: "unknown env value -> default +
     *  WARN") — `by lazy` (not a plain function) is what makes the WARN fire exactly once per
     *  gate instance rather than on every cache-miss fallback. */
    private val envDefault: WordingProfile by lazy {
        WordingProfile.entries.firstOrNull { it.name.equals(configuredDefault, ignoreCase = true) }
            ?: run {
                log.warn(
                    "Unknown littletrotter.wording.profile value '$configuredDefault'; " +
                        "falling back to ${WordingProfile.DEFAULT}",
                )
                WordingProfile.DEFAULT
            }
    }

    private val settingsCache = ShortLivedSettingsCache(initial = envDefault)

    override fun currentProfile(): WordingProfile = settingsCache.current(time.instant()) { loadFromRepository() }

    /** The persisted row, for the settings view's "zuletzt geändert" line. `null` when the row
     *  does not exist (env default still in effect) — not served from the cache, a settings view
     *  is opened rarely. */
    override fun lastChanged(): SettingChangeInfo? = runCatching { repository.findById(KEY) }.getOrNull()?.toChangeInfo()

    override fun setProfile(profile: WordingProfile, actor: String) {
        val existing = repository.findById(KEY) ?: SystemSetting.of(KEY, profile.name)
        existing.changeValue(profile.name)
        existing.stampForPersist(actor)
        repository.save(existing)
        invalidate()
    }

    fun invalidate() {
        settingsCache.invalidate()
    }

    private fun loadFromRepository(): WordingProfile {
        val stored = repository.findById(KEY)?.value ?: return envDefault
        return WordingProfile.entries.firstOrNull { it.name == stored } ?: envDefault
    }

    companion object {
        const val KEY = "wording.profile"
        private val log = Logger.getLogger(WordingSettingsGate::class.java)
    }
}
