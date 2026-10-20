package de.littletrotter.shared

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The zone every **displayed** wall-clock time in the application uses.
 *
 * Little Trotter is a German e-invoicing product: shown times are always the German local time,
 * DST-aware, regardless of the host/container timezone the process happens to run in. This is a
 * product property, not a deployment setting — a container without a `TZ` override still has to
 * show `Europe/Berlin` wall time to the user.
 *
 * **Not** for persistence: stored timestamps stay `OffsetDateTime`/`Instant`, which carry their
 * own offset and remain correct points in time independent of this constant. [ZONE] is only for
 * converting such an instant to a human-readable string, or for the rare zone-less
 * `LocalDateTime.now()` call that is itself display (or a value derived from a display moment).
 */
object GermanTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /**
     * The German calendar day a stored moment falls on. Between midnight and 01:00 (winter) or
     * 02:00 (summer) the UTC day is still the previous one, so a business day — offer date, day of
     * sending, upload date — is always read here and never from the moment's own offset.
     */
    fun dayOf(at: OffsetDateTime): LocalDate = at.atZoneSameInstant(ZONE).toLocalDate()

    /** The German calendar day [at] falls on; see the [OffsetDateTime] overload. */
    fun dayOf(at: Instant): LocalDate = at.atZone(ZONE).toLocalDate()
}
