package de.littletrotter.domain.reference

import java.time.LocalDate

/**
 * Context for [NumberRange] to form a number without knowing master-data types.
 *
 * @param sellerId For [CounterScope.PER_SELLER] and [sellerPrefix] mapping.
 * @param sellerPrefix Resolved from [PrefixSource.SELLER_PROFILE].
 * @param dataFieldValue Resolved from [PrefixSource.FROM_DATA] (e.g., first 3 chars of customer name).
 */
data class NumberContext(
    val date: LocalDate,
    val sellerId: Long? = null,
    val sellerPrefix: String? = null,
    val dataFieldValue: String? = null,
    /** Document being allocated to. Used only for GAPLESS journal entries; unused for UNIQUE_ONLY. */
    val entityType: String? = null,
    val entityId: Long? = null,
)

/**
 * Result of [de.littletrotter.application.reference.NumberRangeService.allocate].
 * Distinct type (not just String) to carry [sequence] separately and distinguish from peek (preview vs. consume).
 */
data class AllocatedNumber(
    val prefix: String?,
    val sequence: Long,
    val value: String,
    val periodKey: String,
)

/** No active number range configured for subject. Caller must check `NumberRangeService.hasActiveRangeFor` first; this is programmer error. */
class NoActiveNumberRangeException(subject: NumberRangeSubject) :
    RuntimeException("Kein aktiver Nummernkreis für '$subject' konfiguriert.")

/**
 * Period already sealed. Second seal would create ambiguous chain heads.
 * Carries original [chainHead] and [sealEntryHash] for UI to show both instead of just rejecting.
 */
class PeriodAlreadySealedException(
    val periodKey: String,
    val sealEntryHash: String,
    val chainHead: String,
) : RuntimeException("Die Periode '$periodKey' ist bereits versiegelt (Kettenkopf $chainHead).")

/** Range with no journal (UNIQUE_ONLY) cannot seal; nothing to seal and seal would look like proof. */
class NotSealableException(rangeCode: String, continuity: Continuity) :
    RuntimeException("Nummernkreis '$rangeCode' ist $continuity und führt kein Journal — ein Periodenabschluss wäre wirkungslos.")

/** User-entered number already allocated. Not auto-replaced like unchanged suggestions. */
class NumberCollisionException(val attemptedValue: String) :
    RuntimeException("Die Nummer '$attemptedValue' ist bereits vergeben.")

/**
 * Result of [de.littletrotter.application.reference.NumberRangeService.syncCounterTo] — the
 * "synchronize number counters to existing data" admin maintenance action (L4 follow-up,
 * 2026-09-02+). [oldValue]/[newValue] are `null` only when the range was never allocated from and
 * no persisted row parsed a suffix either — nothing to report. [changed] is `false` when the
 * counter already covered (or exceeded) the highest persisted number — "bereits synchron" in the
 * UI; the counter itself is never lowered.
 */
data class CounterSyncResult(
    val subject: NumberRangeSubject,
    val oldValue: Long?,
    val newValue: Long?,
    val changed: Boolean,
)

/**
 * Sync is only meaningful for UNIQUE_ONLY ranges: GAPLESS (invoice) allocates the counter in the
 * same transaction as the document itself, so persisted rows and the counter can never drift
 * apart the way bulk-loaded UNIQUE_ONLY rows do — there is nothing to catch up.
 */
class NumberRangeSyncNotSupportedException(val subject: NumberRangeSubject) :
    RuntimeException("Nummernkreis-Synchronisierung ist für '$subject' (GAPLESS) nicht sinnvoll — der Zähler kann dort nie hinter die Daten zurückfallen.")

/**
 * Sync recovers the highest issued number purely from the persisted number strings — it has no
 * record of *when* or *for which Anbieter* each one was allocated, so it can only reconstruct the
 * scope/period key a GLOBAL, never-resetting range uses today (a single counter row). PER_SELLER
 * or YEARLY configurations would need external bookkeeping this feature does not have.
 */
class NumberRangeSyncUnsupportedConfigurationException(rangeCode: String) :
    RuntimeException("Nummernkreis '$rangeCode' nutzt Scope-/Perioden-Einstellungen, die die Synchronisierung nicht unterstützt (nur GLOBAL ohne Rücksetzung).")
