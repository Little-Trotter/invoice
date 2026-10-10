package de.littletrotter.application.shared

import java.time.Duration
import java.time.Instant

/**
 * SFS/T9 — the short-lived TTL cache shared by
 * [de.littletrotter.application.settings.FeatureSettingsGate],
 * [de.littletrotter.application.reference.ValidationSettingsGate] and
 * [de.littletrotter.application.settings.WordingSettingsGate]: all three answer a "what does the
 * settings table currently say?" question that is read on almost every request (drawer build,
 * per-field render, per validation job, every i18n lookup) but changes rarely, so a plain per-call
 * database read would be far too chatty. Extracted here because the first two gates carried the
 * identical cache/TTL/fail-open logic field for field; generic over [T] so the third (a single
 * [de.littletrotter.shared.wording.WordingProfile] value, not a `Map<String, Boolean>`) reuses the
 * exact same mechanics instead of bending its own shape to fit a map.
 *
 * **Fail-open on a failing [current] load.** The cache (and its timestamp) is left untouched, so
 * the stale value is returned and the very next call retries — an infrastructure outage must not
 * lock a wrong answer in for the whole TTL. [initial] is what a caller sees only if the very first
 * load ever attempted already failed.
 */
class ShortLivedSettingsCache<T>(initial: T, private val ttl: Duration = DEFAULT_TTL) {

    private companion object {
        val DEFAULT_TTL: Duration = Duration.ofSeconds(10)
    }

    @Volatile
    private var cache: T = initial

    @Volatile
    private var cachedAt: Instant = Instant.EPOCH

    /** The cached value while it is within [ttl]; otherwise reloads via [loader]. [now] comes
     *  from the caller's TimeSource — a plain helper class may not read the clock itself. */
    fun current(now: Instant, loader: () -> T): T {
        if (Duration.between(cachedAt, now) < ttl) return cache
        val loaded = runCatching(loader).getOrElse { return cache }
        cache = loaded
        cachedAt = now
        return loaded
    }

    /** Resets the cache immediately so the next [current] call re-reads the database — used after
     *  a write, so the acting session sees the effect of its own change right away. */
    fun invalidate() {
        cachedAt = Instant.EPOCH
    }
}
