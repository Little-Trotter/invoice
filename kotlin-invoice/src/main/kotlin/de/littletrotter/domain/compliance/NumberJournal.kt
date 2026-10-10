package de.littletrotter.domain.compliance

import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Lives in `domain.compliance`, not `domain.reference` (ArchUnit rule: compliance may depend on
 * reference, not the reverse) — a journal entry answers an audit question, not a master-data one.
 * Only `Continuity.GAPLESS` ranges are journaled. Written exclusively from
 * [de.littletrotter.application.reference.NumberRangeService.allocate] in the same transaction
 * step as the counter increment, so a caller rollback reverts both together.
 */
/**
 * Values allowed in [NumberJournalEntry.entityType], kept as a constant: writer
 * ([de.littletrotter.application.invoice.InvoiceNumberService]) and reader
 * ([de.littletrotter.application.reference.NumberJournalReportService]) must agree on the exact
 * same string, or the reconciliation silently compares against an empty set and reports clean.
 */
object NumberJournalEntities {
    const val INVOICE = "Invoice"
}

enum class NumberJournalEvent {
    ALLOCATED,

    /** Recorded so the audit report treats a range replacement as a supersession, not a gap. */
    RANGE_SUPERSEDED,

    PERIOD_SEALED,
}

/**
 * [seq] is the entry's own DB-assigned ordering (`BIGSERIAL`), not the allocated invoice number
 * — see [numberSequence] for that. [prevHash]/[entryHash] form the chain (see
 * [NumberJournalChain]).
 */
data class NumberJournalEntry(
    val seq: Long? = null,
    val numberRangeId: Long,
    val event: NumberJournalEvent,
    val periodKey: String,
    val numberPrefix: String? = null,
    val numberSequence: Long? = null,
    val number: String? = null,
    val entityType: String? = null,
    val entityId: Long? = null,
    val reason: String? = null,
    val actor: String,
    val occurredAt: OffsetDateTime,
    val prevHash: String,
    val entryHash: String,
    // Set only when event == PERIOD_SEALED. The chain head itself is not a separate field: it's
    // this entry's [prevHash]. These four are just the sealed summary alongside it.
    val sealedStartValue: Long? = null,
    val sealedCount: Long? = null,
    val sealedFirstNumber: String? = null,
    val sealedLastNumber: String? = null,
)

object NumberJournalChain {

    /** ASCII "Unit Separator" (0x1F) — not expected to occur in any field. */
    private const val FIELD_SEP = "\u001F"

    /** First printable ASCII code point (0x20, space) — anything below is a C0 control
     *  character, see [assertCanonicalizable]'s own KDoc. */
    private const val ASCII_PRINTABLE_START = 0x20

    /** ASCII NUL (0x00): a `null` field must hash differently from an empty-string field, or two
     *  distinct entries could collide. */
    private const val NULL_MARKER = "\u0000"

    /** Anchor includes the start value, so a start value changed later visibly breaks the chain
     *  instead of silently taking effect. */
    fun genesisHash(rangeCode: String, startValue: Long): String =
        sha256(listOf(rangeCode, startValue.toString()).joinToString(FIELD_SEP))

    /**
     * The entry's own [NumberJournalEntry.seq], [NumberJournalEntry.prevHash] and
     * [NumberJournalEntry.entryHash] are deliberately not read: [prevHash]/[seq] are passed
     * explicitly (the writer hashes before the row carries them), and the entry hash cannot cover
     * itself. The field order of the payload is part of the persisted chain — never reorder it.
     */
    fun computeEntryHash(prevHash: String, seq: Long, entry: NumberJournalEntry): String {
        val payload = listOf(
            prevHash,
            seq.toString(),
            entry.event.name,
            entry.periodKey,
            entry.numberPrefix ?: NULL_MARKER,
            entry.numberSequence?.toString() ?: NULL_MARKER,
            entry.number ?: NULL_MARKER,
            entry.entityType ?: NULL_MARKER,
            entry.entityId?.toString() ?: NULL_MARKER,
            entry.reason ?: NULL_MARKER,
            entry.actor,
            entry.occurredAt.toInstant().toString(), // UTC instant, independent of the server's time zone
            entry.sealedStartValue?.toString() ?: NULL_MARKER,
            entry.sealedCount?.toString() ?: NULL_MARKER,
            entry.sealedFirstNumber ?: NULL_MARKER,
            entry.sealedLastNumber ?: NULL_MARKER,
        ).joinToString(FIELD_SEP)
        return sha256(payload)
    }

    /**
     * Rejects fields containing a control character instead of sanitizing them: such a
     * character would defeat the `0x1F`/`0x00` field separation and could make two different
     * entries hash identically. Not theoretical — `actor` comes from an auth header, `reason` is
     * free text.
     */
    fun assertCanonicalizable(entry: NumberJournalEntry) {
        val fields = mapOf(
            "periodKey" to entry.periodKey,
            "numberPrefix" to entry.numberPrefix,
            "number" to entry.number,
            "entityType" to entry.entityType,
            "reason" to entry.reason,
            "actor" to entry.actor,
            "sealedFirstNumber" to entry.sealedFirstNumber,
            "sealedLastNumber" to entry.sealedLastNumber,
        )
        fields.forEach { (name, value) ->
            val offending = value?.firstOrNull { it.code < ASCII_PRINTABLE_START } ?: return@forEach
            throw IllegalArgumentException(
                "Journalfeld '$name' enthält das Steuerzeichen U+%04X. Steuerzeichen sind dort nicht zulässig, ".format(offending.code) +
                    "weil sie die Trennung der Felder im Hash aufheben und zwei verschiedene Einträge gleich aussehen lassen könnten.",
            )
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

sealed interface ChainVerification {
    data object Valid : ChainVerification
    data class Broken(val atSeq: Long, val reason: String) : ChainVerification
}

/** Requires [entries] sorted ascending by [NumberJournalEntry.seq]. */
fun verifyChain(entries: List<NumberJournalEntry>, genesisHash: String): ChainVerification {
    var expectedPrev = genesisHash
    for (entry in entries) {
        val broken = brokenLinkFor(entry, expectedPrev)
        if (broken != null) return broken
        expectedPrev = entry.entryHash
    }
    return ChainVerification.Valid
}

private fun brokenLinkFor(entry: NumberJournalEntry, expectedPrev: String): ChainVerification.Broken? {
    val seq = entry.seq ?: return ChainVerification.Broken(-1, "Eintrag ohne seq kann nicht geprüft werden")
    val recomputed = NumberJournalChain.computeEntryHash(entry.prevHash, seq, entry)
    return when {
        entry.prevHash != expectedPrev -> ChainVerification.Broken(seq, "prevHash weicht vom vorherigen entryHash ab")
        recomputed != entry.entryHash -> ChainVerification.Broken(seq, "entryHash passt nicht zum Inhalt des Eintrags")
        else -> null
    }
}

/**
 * Per-range chain state while [verifyIncremental] streams `number_journal` in
 * seq-ordered batches spanning every range at once (see
 * [de.littletrotter.application.compliance.ChainVerificationService]). Keyed by
 * [NumberJournalEntry.numberRangeId] in the caller's state map; distinct from [ChainVerification],
 * which [verifyChain] returns for a single already-fully-loaded range (the audit-report screen's
 * use case, not the nightly whole-table sweep this exists for).
 */
sealed interface NumberJournalRangeState {
    /** The range's chain has been clean through [prevHash] (its most recently verified
     *  `entry_hash`) — the next entry seen for this range must chain from here. */
    data class Ongoing(val prevHash: String) : NumberJournalRangeState

    /** A break was found for this range at [atSeq]; further entries of the same range are no
     *  longer checked (they would only cascade into more findings built on an already-wrong
     *  hash) — other ranges in the same stream are unaffected and keep being verified. */
    data class Broken(val atSeq: Long, val reason: String) : NumberJournalRangeState
}

/** Result of feeding one batch through [verifyIncremental]: the updated per-range state (carry
 *  it into the next batch) and how many rows this call actually checked (a range already
 *  [NumberJournalRangeState.Broken] is skipped, not re-checked, so [checked] can be lower than
 *  `entries.size`). */
data class NumberJournalChainStepResult(
    val nextState: Map<Long, NumberJournalRangeState>,
    val checked: Long,
)

/**
 * Pure, DB-free incremental step of the nightly whole-table chain check: verifies one
 * seq-ordered batch of [entries] (any mix of ranges, as [de.littletrotter.domain.repository
 * .NumberJournalRepository.streamAllBatched] returns them) against the running per-range
 * [state], without ever holding the whole (unbounded, J3) table in memory — the caller
 * ([de.littletrotter.application.compliance.ChainVerificationService]) drives the batches and
 * threads [state] from one call into the next.
 *
 * [genesisFor] resolves a range's genesis hash the first time that range is seen (its
 * `NumberRange.code`/`startValue`, via [NumberJournalChain.genesisHash]) — injected rather than
 * looked up here so this function stays pure and unit-testable without a repository. Returning
 * `null` (the range row no longer exists) is itself recorded as a break: the journal is meant to
 * outlive the range that created it in spirit (same reasoning as
 * `invoice_audit_journal.invoice_id` having no FK/cascade — see that column's migration comment),
 * so a chain whose range vanished must be flagged, not silently skipped.
 */
fun verifyIncremental(
    state: Map<Long, NumberJournalRangeState>,
    entries: List<NumberJournalEntry>,
    genesisFor: (Long) -> String?,
): NumberJournalChainStepResult {
    val next = state.toMutableMap()
    var checked = 0L
    // already-Broken ranges are skipped, not re-checked (see this function's own KDoc) — the rest
    // is a plain apply-and-count, the branching (seq/genesis/prevHash/entryHash) lives in
    // nextRangeState so this loop has no jump statements (LoopWithTooManyJumpStatements).
    for (entry in entries) {
        val current = next[entry.numberRangeId]
        if (current !is NumberJournalRangeState.Broken) {
            checked++
            next[entry.numberRangeId] = nextRangeState(entry, current, genesisFor)
        }
    }
    return NumberJournalChainStepResult(next, checked)
}

private fun nextRangeState(
    entry: NumberJournalEntry,
    current: NumberJournalRangeState?,
    genesisFor: (Long) -> String?,
): NumberJournalRangeState {
    val rangeId = entry.numberRangeId
    val seq = entry.seq ?: return NumberJournalRangeState.Broken(-1, "Entry has no seq — cannot verify")
    val prevHash = resolvePrevHash(current, rangeId, genesisFor)
        ?: return NumberJournalRangeState.Broken(seq, "Number range $rangeId no longer exists — cannot resolve its genesis hash")
    val recomputed = NumberJournalChain.computeEntryHash(entry.prevHash, seq, entry)
    return when {
        entry.prevHash != prevHash -> NumberJournalRangeState.Broken(seq, "prevHash does not match the previous entry_hash")
        recomputed != entry.entryHash -> NumberJournalRangeState.Broken(seq, "entry_hash does not match the entry's own content")
        else -> NumberJournalRangeState.Ongoing(entry.entryHash)
    }
}

private fun resolvePrevHash(current: NumberJournalRangeState?, rangeId: Long, genesisFor: (Long) -> String?): String? =
    when (current) {
        is NumberJournalRangeState.Ongoing -> current.prevHash
        null -> genesisFor(rangeId)
        is NumberJournalRangeState.Broken -> null // unreachable (caller filters Broken ranges out), keeps `when` exhaustive
    }
