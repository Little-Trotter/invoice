package de.littletrotter.application.settings

import de.littletrotter.application.settings.port.inbound.SettingChangeInfo
import de.littletrotter.application.shared.ShortLivedSettingsCache
import de.littletrotter.domain.repository.FeatureSettingRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.settings.FeatureSetting
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.settings.port.inbound.FeatureSettingsQueryPort
import de.littletrotter.application.settings.port.inbound.ManageFeatureSettingsUseCase

/**
 * SFS — the one place that answers "is this feature switched on?".
 *
 * The runtime mirror of [de.littletrotter.application.reference.ValidationSettingsGate] and
 * deliberately carrying the same contract down to the cache timings, because the question both
 * answer is the same kind of question: a user-facing offer that can be off without anybody having
 * to restart anything.
 *
 * **Fail-open.** A missing row (fresh install before the V5 seed was even wanted) or an
 * unreachable database means the feature counts as its [FeatureFlag.defaultEnabled] — `true` for
 * most of today's catalog, so an infrastructure problem never silently hides a whole area of the
 * UI with no user action to explain it. [FeatureFlag.EXTENDED_PROFILE] is the deliberate exception
 * (defaults `false`, PO decision 2026-09-28): fail-open there means failing to the safe, always
 * KoSIT-validatable EN16931 profile, not toward the unvalidatable one.
 *
 * **Short-lived cache.** [isEnabled] runs on every drawer build and on every item-toolbar render;
 * without a cache that would be one read per button. A flip is visible everywhere within the TTL
 * and [invalidate] makes it visible in the acting session immediately.
 *
 * Note the deliberate split with [de.littletrotter.application.mail.MailAvailability]: whether the
 * mail component is *installed* is a deployment fact; whether it may *offer sending* is a feature
 * decision. Both must be true for the user to see anything mail-sendy, and the AND happens at the
 * consumer (MailDispatchQuote) so neither class has to know the other.
 */
@ApplicationScoped
class FeatureSettingsGate @Inject constructor(
    private val repository: FeatureSettingRepository,
    private val time: TimeSource,
) : FeatureSettingsQueryPort, ManageFeatureSettingsUseCase {

    private val settingsCache = ShortLivedSettingsCache(initial = emptyMap<String, Boolean>())

    override fun isEnabled(flag: FeatureFlag): Boolean =
        currentSettings()[flag.key] ?: flag.defaultEnabled

    /** All catalog flags with their state — the basis of the Einstellungen view. */
    fun allStates(): Map<FeatureFlag, Boolean> {
        val settings = currentSettings()
        return FeatureFlag.entries.associateWith { settings[it.key] ?: it.defaultEnabled }
    }

    /** The persisted row for [flag], for the view's "zuletzt geändert"-line. `null` when the row
     *  does not exist (fail-open default still in effect) — deliberately not served from the
     *  cache, the cache only holds booleans and a settings view is opened rarely. */
    override fun lastChanged(flag: FeatureFlag): SettingChangeInfo? =
        runCatching { repository.findById(flag) }.getOrNull()?.toChangeInfo()

    override fun setEnabled(flag: FeatureFlag, enabled: Boolean, actor: String) {
        val existing = repository.findById(flag) ?: FeatureSetting.of(flag, enabled)
        existing.switchTo(enabled)
        existing.stampForPersist(actor)
        repository.save(existing)
        invalidate()
    }

    fun invalidate() {
        settingsCache.invalidate()
    }

    private fun currentSettings(): Map<String, Boolean> =
        settingsCache.current(time.instant()) { repository.findAll().associate { it.key to it.enabled } }
}
