package de.littletrotter.domain.compliance

import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Mirrors the SMALLINT event codes
 * `capture_invoice_audit_event()` writes into `invoice_audit_journal.event`
 * (`V1__baseline_schema.sql`, section 13, `chk_invoice_audit_journal_event`). Same
 * ordinal-enum-as-SMALLINT convention as every other SQL-side enum CHECK in that file
 * (see its own file-level comment on enum-CHECK styles) — the entry order below IS the contract,
 * never reorder it; only ever append.
 *
 * One CREATED/UPDATED/DELETED triple per captured table, followed by explicit assignment events
 * for receipt links. The order is an append-only database contract.
 */
enum class InvoiceAuditEvent {
    INVOICE_CREATED,
    INVOICE_UPDATED,
    INVOICE_DELETED,
    INVOICE_ITEM_CREATED,
    INVOICE_ITEM_UPDATED,
    INVOICE_ITEM_DELETED,
    INVOICE_DOCUMENT_CREATED,
    INVOICE_DOCUMENT_UPDATED,
    INVOICE_DOCUMENT_DELETED,
    TRIP_EXPENSE_CREATED,
    TRIP_EXPENSE_UPDATED,
    TRIP_EXPENSE_DELETED,
    INVOICE_RECEIPT_ASSIGNED,
    INVOICE_RECEIPT_UNASSIGNED,
    ITEM_RECEIPT_ASSIGNED,
    ITEM_RECEIPT_UNASSIGNED,
    ;

    /** i18n key for this event's user-facing label — `journal.event.<NAME>`. Lives on the enum
     *  itself (same idiom as [de.littletrotter.domain.invoice.InvoiceStatus.labelKey]) rather than
     *  in a single adapter, so every reader of this journal — the audit views and the
     *  `Journal` export sheet alike — always resolves the exact same key and therefore always says
     *  the same word for the same event, without one adapter depending on the other. */
    val labelKey: String get() = "journal.event.$name"

    companion object {
        /** Inverse of [Enum.ordinal] — for reading `invoice_audit_journal.event` back out of a
         *  native query row. Throws rather than silently defaulting: an out-of-range ordinal here
         *  means the Kotlin enum and the SQL CHECK/trigger have drifted apart, exactly the drift
         *  the baseline file's own enum-CHECK convention says must never happen. */
        fun fromOrdinal(ordinal: Int): InvoiceAuditEvent =
            entries.getOrNull(ordinal)
                ?: throw IllegalArgumentException("Unknown invoice_audit_journal.event ordinal $ordinal")
    }
}

/**
 * One row of `invoice_audit_journal`, read-only (the table is written exclusively by the
 * `capture_invoice_audit_event()` DB trigger, never by application code — see
 * [de.littletrotter.domain.repository.InvoiceAuditJournalRepository]).
 *
 * [detail] is kept as the raw JSONB text rather than parsed here: [entryHash]'s recomputation
 * (the chain check) must hash the exact bytes the trigger hashed, and the audit view renders it
 * for display — neither wants this class to own a canonical re-serialization that could drift
 * from the trigger's own `to_jsonb(...)::text` output.
 */
data class InvoiceAuditJournalEntry(
    val seq: Long,
    /** `txid_current()` of the transaction that wrote this row: every row of one business
     *  action (e.g. `addItem`'s header + item rows) shares the same value, letting the UI group
     *  row-level events back into one auditor-facing line. Part of [entryHash]'s input, same as
     *  [action]. */
    val businessTx: Long,
    val invoiceId: Long,
    val invoiceNumber: String?,
    val actor: String,
    val actorName: String?,
    val occurredAt: OffsetDateTime,
    val event: InvoiceAuditEvent,
    /** The business action the application was performing (`app.audit_action` GUC,
     *  outermost-wins), or [InvoiceAuditAction.UNSPECIFIED] when the write reached this table
     *  outside the application (no GUC set — see this class's own KDoc on [actor]'s
     *  `session_user` fallback for the same signal). */
    val action: InvoiceAuditAction,
    val detail: String,
    val documentSha256: String?,
    val prevHash: String,
    val entryHash: String,
)

/**
 * The same `entry_hash`/`prev_hash` computation
 * `capture_invoice_audit_event()` runs in `V1__baseline_schema.sql` (section 13), reimplemented in
 * Kotlin so the nightly verification job can recompute the chain independently of the trigger that
 * wrote it. Field order, separator (Postgres `CHR(31)`, the ASCII "Unit Separator") and the
 * `CHR(1)` (ASCII SOH) null sentinel (chosen there over `CHR(0)`/NUL because Postgres rejects a
 * NUL byte even inside `DIGEST()` — see that function's own comment) must stay byte-for-byte
 * identical to the SQL side, or every row would report a false break the moment this runs.
 *
 * [InvoiceAuditJournalEntry.detail] is fed through unchanged (never re-serialized here) for the
 * same reason its own KDoc gives: it already holds the exact `to_jsonb(...)::text` bytes the
 * trigger hashed.
 */
object InvoiceAuditJournalChain {

    /** ASCII "Unit Separator" (`CHR(31)`) — same choice and reasoning as
     *  [NumberJournalChain]'s own `FIELD_SEP`. */
    private const val FIELD_SEP = "\u001F"

    /** ASCII SOH (`CHR(1)`) — mirrors the trigger's own null-value sentinel exactly (see this
     *  object's KDoc for why `CHR(0)`/NUL isn't usable here, unlike [NumberJournalChain]'s NUL
     *  marker). */
    private const val NULL_MARKER = "\u0001"

    fun genesisHash(): String = sha256("invoice_audit_journal-genesis")

    fun computeEntryHash(prevHash: String, entry: InvoiceAuditJournalEntry): String {
        val payload = listOf(
            prevHash,
            entry.seq.toString(),
            entry.invoiceId.toString(),
            entry.invoiceNumber ?: NULL_MARKER,
            entry.actor,
            entry.actorName ?: NULL_MARKER,
            entry.event.ordinal.toString(),
            entry.detail,
            entry.documentSha256 ?: NULL_MARKER,
            // Appended after document_sha256, same field order/separator as the trigger
            // (V1__baseline_schema.sql section 13): an action label an auditor relies on must be
            // as tamper-evident as every other field.
            entry.action.ordinal.toString(),
            entry.businessTx.toString(),
        ).joinToString(FIELD_SEP)
        return sha256(payload)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Mirrors [de.littletrotter.domain.compliance.ChainVerification] (the `number_journal` shape),
 *  but for the single, table-wide `invoice_audit_journal` chain — there is only ever one chain
 *  here, not one per range, so no range id is carried. */
sealed interface InvoiceAuditChainVerification {
    data object Valid : InvoiceAuditChainVerification
    data class Broken(val atSeq: Long, val reason: String) : InvoiceAuditChainVerification
}

/** Result of feeding one seq-ordered batch through [verifyInvoiceAuditChainBatch]: [nextPrevHash]
 *  is the running chain head to pass into the next batch (unchanged once [verification] has
 *  turned [InvoiceAuditChainVerification.Broken] — no point extending a hash that no longer means
 *  anything), and [checked] is how many rows this call actually verified. */
data class InvoiceAuditChainStepResult(
    val nextPrevHash: String,
    val verification: InvoiceAuditChainVerification,
    val checked: Long,
)

/**
 * Pure, DB-free incremental step of the nightly chain check for `invoice_audit_journal`:
 * verifies one seq-ordered batch of [entries] against the running [prevHash], without ever holding
 * the whole (unbounded, J3) table in memory. The caller
 * ([de.littletrotter.application.compliance.ChainVerificationService]) drives the batches (via
 * [de.littletrotter.domain.repository.InvoiceAuditJournalRepository.streamBatch]) and threads the
 * result's [InvoiceAuditChainStepResult.nextPrevHash] from one call into the next — starting from
 * [InvoiceAuditJournalChain.genesisHash] for the very first batch, same as
 * `capture_invoice_audit_event()` falls back to the genesis hash when the table is empty.
 *
 * Stops verifying (and stops advancing [InvoiceAuditChainStepResult.nextPrevHash]) at the first
 * break within a call — matches [de.littletrotter.domain.compliance.verifyChain]'s single-chain
 * semantics: once one entry is wrong, every later `entry_hash` in this same chain is built on top
 * of it and reporting each of those separately would only be noise around the one real finding.
 */
fun verifyInvoiceAuditChainBatch(prevHash: String, entries: List<InvoiceAuditJournalEntry>): InvoiceAuditChainStepResult {
    var expectedPrev = prevHash
    var checked = 0L
    for (entry in entries) {
        if (entry.prevHash != expectedPrev) {
            return InvoiceAuditChainStepResult(
                expectedPrev,
                InvoiceAuditChainVerification.Broken(entry.seq, "prevHash does not match the previous entry_hash"),
                checked,
            )
        }
        val recomputed = InvoiceAuditJournalChain.computeEntryHash(entry.prevHash, entry)
        if (recomputed != entry.entryHash) {
            return InvoiceAuditChainStepResult(
                expectedPrev,
                InvoiceAuditChainVerification.Broken(entry.seq, "entry_hash does not match the entry's own content"),
                checked,
            )
        }
        checked++
        expectedPrev = entry.entryHash
    }
    return InvoiceAuditChainStepResult(expectedPrev, InvoiceAuditChainVerification.Valid, checked)
}
