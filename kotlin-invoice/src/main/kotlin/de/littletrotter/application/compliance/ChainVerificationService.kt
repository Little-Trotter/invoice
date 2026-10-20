package de.littletrotter.application.compliance

import de.littletrotter.domain.message.SystemMessageContent
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.domain.compliance.InvoiceAuditChainVerification
import de.littletrotter.domain.compliance.InvoiceAuditJournalChain
import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalRangeState
import de.littletrotter.domain.compliance.verifyIncremental
import de.littletrotter.domain.compliance.verifyInvoiceAuditChainBatch
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.NumberJournalRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The nightly job (`beratung-manipulationsschutz.md` M6) that recomputes BOTH hash chains
 * (`number_journal`, `invoice_audit_journal`) fully from scratch and compares the recomputation
 * against the stored `entry_hash` values, catching a chain that was rewritten on the host itself
 * (see `konzept-aenderungsjournal.md`'s "Ehrliche Grenze" section — this check plus the
 * backup anchor are what makes such a rewrite detectable at all, not preventable: a host-local
 * superuser can still rewrite table, trigger and chain together).
 *
 * Both journals have unbounded retention (J3, PO 2026-09-03) — this service streams
 * [NumberJournalRepository.streamAllBatched]/[InvoiceAuditJournalRepository.streamBatch] in
 * [BATCH_SIZE]-row pages via a `seq` cursor rather than ever loading a full table into memory, the
 * same reason those two repository methods exist instead of reusing [NumberJournalRepository
 * .findAll] (single-range only) or [InvoiceAuditJournalRepository.streamAll] (whole table, one
 * call — fine for dev-scale, not for this).
 *
 * The actual recomputation is pure/DB-free ([verifyIncremental], [verifyInvoiceAuditChainBatch]) —
 * this class only drives the batches and records the outcome, which is what keeps the
 * recomputation logic itself unit-testable without a database (see the corresponding domain
 * tests).
 *
 * Triggered daily by [de.littletrotter.infrastructure.persistence.ChainVerificationScheduler],
 * same worker-only scheduling gate as every other `@Scheduled` job in this module.
 */
@ApplicationScoped
class ChainVerificationService @Inject constructor(
    private val numberJournalRepository: NumberJournalRepository,
    private val numberRangeRepository: NumberRangeRepository,
    private val invoiceAuditJournalRepository: InvoiceAuditJournalRepository,
    private val maintenanceService: DatabaseMaintenanceService,
    private val messageCenterService: MessageCenterService,
) {

    private val log = Logger.getLogger(javaClass)

    /** Runs both chain checks, records one [DatabaseMaintenanceEvent] either way, and — only on a
     *  break — records an ERROR system message. Returns the outcome for callers that want it
     *  (the scheduler just logs; a future admin-tools invocation could use the return value
     *  directly). */
    fun verifyAllChains(): ChainVerificationOutcome {
        val numberJournalResult = verifyNumberJournal()
        val invoiceAuditResult = verifyInvoiceAuditJournal()
        val outcome = ChainVerificationOutcome(numberJournalResult, invoiceAuditResult)
        recordOutcome(outcome)
        return outcome
    }

    private fun verifyNumberJournal(): NumberJournalVerificationResult {
        var afterSeq = 0L
        var state: Map<Long, NumberJournalRangeState> = emptyMap()
        var checked = 0L
        // Resolved lazily per range (see verifyIncremental's genesisFor) and cached for the rest of
        // this run — a range seen in an earlier batch never needs a second lookup.
        val genesisCache = mutableMapOf<Long, String?>()
        val genesisFor: (Long) -> String? = { rangeId ->
            genesisCache.getOrPut(rangeId) {
                numberRangeRepository.findById(rangeId)?.let { NumberJournalChain.genesisHash(it.code, it.startValue) }
            }
        }

        var hasMore = true
        while (hasMore) {
            val batch = numberJournalRepository.streamAllBatched(afterSeq, BATCH_SIZE)
            if (batch.isEmpty()) {
                hasMore = false
            } else {
                val step = verifyIncremental(state, batch, genesisFor)
                state = step.nextState
                checked += step.checked
                afterSeq = batch.last().seq ?: afterSeq
                hasMore = batch.size >= BATCH_SIZE
            }
        }

        val breaks = state.filterValues { it is NumberJournalRangeState.Broken }
        return NumberJournalVerificationResult(checked, breaks)
    }

    private fun verifyInvoiceAuditJournal(): InvoiceAuditVerificationResult {
        var afterSeq = 0L
        var prevHash = InvoiceAuditJournalChain.genesisHash()
        var checked = 0L
        var verification: InvoiceAuditChainVerification = InvoiceAuditChainVerification.Valid

        var hasMore = true
        while (hasMore && verification is InvoiceAuditChainVerification.Valid) {
            val batch = invoiceAuditJournalRepository.streamBatch(afterSeq, BATCH_SIZE)
            if (batch.isEmpty()) {
                hasMore = false
            } else {
                val step = verifyInvoiceAuditChainBatch(prevHash, batch)
                prevHash = step.nextPrevHash
                checked += step.checked
                verification = step.verification
                afterSeq = batch.last().seq
                hasMore = batch.size >= BATCH_SIZE
            }
        }

        return InvoiceAuditVerificationResult(checked, verification)
    }

    private fun recordOutcome(outcome: ChainVerificationOutcome) {
        val details = buildString {
            append("number_journal: ${outcome.numberJournal.checked} Einträge geprüft")
            if (outcome.numberJournal.breaks.isNotEmpty()) {
                append(", Kettenbruch in Nummernkreis(en): ")
                append(outcome.numberJournal.breaks.entries.joinToString("; ") { (rangeId, state) ->
                    val broken = state as NumberJournalRangeState.Broken
                    "Kreis $rangeId bei seq ${broken.atSeq} (${broken.reason})"
                })
            } else {
                append(", Kette intakt")
            }
            append(". invoice_audit_journal: ${outcome.invoiceAudit.checked} Einträge geprüft")
            when (val v = outcome.invoiceAudit.verification) {
                is InvoiceAuditChainVerification.Valid -> append(", Kette intakt")
                is InvoiceAuditChainVerification.Broken -> append(", Kettenbruch bei seq ${v.atSeq} (${v.reason})")
            }
            append(".")
        }

        if (outcome.hasBreak) {
            log.error("RS-12 chain verification found a break: $details")
        } else {
            log.info("RS-12 chain verification: $details")
        }

        maintenanceService.recordMaintenanceEvent(
            DatabaseMaintenanceEvent(
                type = DatabaseMaintenanceType.CHAIN_VERIFICATION,
                status = if (outcome.hasBreak) DatabaseMaintenanceStatus.FAILED else DatabaseMaintenanceStatus.SUCCESS,
                details = details,
            ),
        )

        if (outcome.hasBreak) {
            messageCenterService.record(
                SystemMessageContent(
                    severity = SystemMessageSeverity.ERROR,
                    source = SystemMessageSource.SYSTEM,
                    messageKey = "message.center.chainVerification.broken",
                    resolvedText = "Nächtliche Kettenprüfung: Manipulationsverdacht — $details",
                ),
            )
        }
    }

    private companion object {
        /** Rows per streamed page — small enough to keep any one query's result set cheap, large
         *  enough that a multi-million-row journal doesn't need millions of round-trips. Not
         *  externalised as a `@ConfigProperty`: nothing about it is deployment-specific, unlike e.g.
         *  [MessageCenterService]'s retention days. */
        const val BATCH_SIZE = 1000
    }
}

/** One range's chain break, or the outcome of a completed sweep of `number_journal` — see
 *  [ChainVerificationService.verifyNumberJournal]. [breaks] is empty on a clean run. */
data class NumberJournalVerificationResult(
    val checked: Long,
    val breaks: Map<Long, NumberJournalRangeState>,
)

/** Outcome of a completed sweep of `invoice_audit_journal` — see
 *  [ChainVerificationService.verifyInvoiceAuditJournal]. */
data class InvoiceAuditVerificationResult(
    val checked: Long,
    val verification: InvoiceAuditChainVerification,
)

data class ChainVerificationOutcome(
    val numberJournal: NumberJournalVerificationResult,
    val invoiceAudit: InvoiceAuditVerificationResult,
) {
    val hasBreak: Boolean
        get() = numberJournal.breaks.isNotEmpty() || invoiceAudit.verification is InvoiceAuditChainVerification.Broken
}
