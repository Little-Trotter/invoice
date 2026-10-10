package de.littletrotter.application.reference

import de.littletrotter.application.shared.ShortLivedSettingsCache
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.reference.ValidationSetting
import de.littletrotter.domain.repository.ValidationSettingRepository
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * T9 — the one place that answers "is this check active?".
 *
 * **One** implementation, no `@Alternative`: whether a check is functionally active is decided
 * exclusively by this reference-data table — a user should have that in their own hands.
 *
 * **Fail-open.** If the row is missing (new check, seed migration not yet run) or the database is
 * currently unreachable, the check counts as enabled. The opposite would be worse: an
 * infrastructure problem would silently disable a check that previously always ran, with nobody
 * noticing from the result.
 *
 * **Short-lived cache.** [isEnabled] is called both when building a view and per job; without a
 * cache that would be one database access per form field. The hold time is short enough that a
 * flipped switch takes effect without a restart, and long enough that a single page build does
 * not read it repeatedly. [invalidate] resets it immediately so a user sees the effect of their
 * own click right away instead of waiting for expiry.
 */
@ApplicationScoped
class ValidationSettingsGate @Inject constructor(
    private val repository: ValidationSettingRepository,
    private val time: TimeSource,
) {

    private val settingsCache = ShortLivedSettingsCache(initial = emptyMap<String, Boolean>())

    fun isEnabled(capability: ValidationCapabilityId): Boolean =
        currentSettings()[capability.name] ?: true

    /** All known checks with their state — the basis of the reference-data tab. A check without
     *  a row appears as enabled, consistent with [isEnabled]. */
    fun allSettings(): Map<ValidationCapabilityId, Boolean> {
        val settings = currentSettings()
        return ValidationCapabilityId.entries.associateWith { settings[it.name] ?: true }
    }

    fun setEnabled(capability: ValidationCapabilityId, enabled: Boolean) {
        val existing = repository.findById(capability) ?: ValidationSetting.of(capability)
        existing.enabled = enabled
        repository.save(existing)
        invalidate()
    }

    fun invalidate() {
        settingsCache.invalidate()
    }

    private fun currentSettings(): Map<String, Boolean> =
        settingsCache.current(time.instant()) { repository.findAll().associate { it.capabilityId to it.enabled } }
}
