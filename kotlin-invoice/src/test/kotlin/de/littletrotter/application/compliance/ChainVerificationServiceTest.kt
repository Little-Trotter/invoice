package de.littletrotter.application.compliance

import org.mockito.kotlin.argThat
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.compliance.InvoiceAuditChainVerification
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalChain
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.compliance.NumberJournalChain
import de.littletrotter.domain.compliance.NumberJournalEntry
import de.littletrotter.domain.compliance.NumberJournalEvent
import de.littletrotter.domain.compliance.NumberJournalRangeState
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.NumberJournalRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * [ChainVerificationService] against mocked repositories/services (mockito-kotlin, same
 * pattern as [de.littletrotter.application.reference.NumberJournalReportServiceTest]): the
 * recomputation itself is already covered DB-free by [de.littletrotter.domain.compliance
 * .InvoiceAuditJournalChainTest]/[de.littletrotter.domain.compliance
 * .NumberJournalIncrementalVerificationTest] — this class covers the orchestration around it
 * (batching until empty, recording [DatabaseMaintenanceEvent], and only-on-break system message).
 */
class ChainVerificationServiceTest {

    private val numberJournalRepository = mock<NumberJournalRepository>()
    private val numberRangeRepository = mock<NumberRangeRepository>()
    private val invoiceAuditJournalRepository = mock<InvoiceAuditJournalRepository>()
    private val maintenanceService = mock<DatabaseMaintenanceService>()
    private val messageCenterService = mock<MessageCenterService>()

    private lateinit var service: ChainVerificationService

    private val now = OffsetDateTime.parse("2026-09-04T10:00:00Z")

    /** Mirrors the private `ChainVerificationService.BATCH_SIZE` (currently 1000) — the paging
     *  tests below need a batch that is exactly full (so the service's own page loop runs a
     *  second iteration) versus one short of full (so it stops). Keep this in sync if that
     *  constant ever changes. */
    private val batchSize = 1000

    @BeforeEach
    fun setUp() {
        service = ChainVerificationService(
            numberJournalRepository, numberRangeRepository, invoiceAuditJournalRepository,
            maintenanceService, messageCenterService,
        )
        // Default: both journals empty — streamAllBatched/streamBatch return nothing on the
        // first call unless a test overrides it.
        whenever(numberJournalRepository.streamAllBatched(any(), any())).thenReturn(emptyList())
        whenever(invoiceAuditJournalRepository.streamBatch(any(), any())).thenReturn(emptyList())
    }

    private fun numberJournalEntry(seq: Long, rangeId: Long, prevHash: String): NumberJournalEntry {
        val unhashed = NumberJournalEntry(
            seq = seq, numberRangeId = rangeId, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
            numberPrefix = "P", numberSequence = seq, number = "P-2026-0000$seq", entityType = "Invoice",
            entityId = 1L, reason = null, actor = "system", occurredAt = now,
            prevHash = prevHash, entryHash = "",
        )
        return unhashed.copy(entryHash = NumberJournalChain.computeEntryHash(prevHash, seq, unhashed))
    }

    private fun invoiceAuditEntry(seq: Long, prevHash: String): InvoiceAuditJournalEntry {
        val base = InvoiceAuditJournalEntry(
            seq = seq, businessTx = seq, invoiceId = 1L, invoiceNumber = "RE-2026-0001", actor = "system", actorName = null,
            occurredAt = now, event = InvoiceAuditEvent.INVOICE_CREATED, action = InvoiceAuditAction.UNSPECIFIED, detail = "{}",
            documentSha256 = null, prevHash = prevHash, entryHash = "",
        )
        return base.copy(entryHash = InvoiceAuditJournalChain.computeEntryHash(prevHash, base))
    }

    private fun range(id: Long, code: String = "P") = NumberRange().apply {
        this.id = id
        this.code = code
        this.startValue = 1L
    }

    /** Builds [count] chained `number_journal` entries for one range, continuing from
     *  [startPrevHash] at [startSeq] — used to build pages that must link across a batch
     *  boundary. */
    private fun numberJournalChain(count: Int, rangeId: Long, startSeq: Long, startPrevHash: String): List<NumberJournalEntry> {
        val entries = mutableListOf<NumberJournalEntry>()
        var prevHash = startPrevHash
        for (i in 0 until count) {
            val entry = numberJournalEntry(startSeq + i, rangeId, prevHash)
            entries += entry
            prevHash = entry.entryHash
        }
        return entries
    }

    /** Builds [count] chained `invoice_audit_journal` entries, continuing from [startPrevHash] at
     *  [startSeq] — used to build pages that must link across a batch boundary. */
    private fun invoiceAuditChain(count: Int, startSeq: Long, startPrevHash: String): List<InvoiceAuditJournalEntry> {
        val entries = mutableListOf<InvoiceAuditJournalEntry>()
        var prevHash = startPrevHash
        for (i in 0 until count) {
            val entry = invoiceAuditEntry(startSeq + i, prevHash)
            entries += entry
            prevHash = entry.entryHash
        }
        return entries
    }

    // ─── clean run ────────────────────────────────────────────────────────────────────

    @Test
    fun `RS-12 - a clean run with both journals empty records a SUCCESS event and never sends a message`() {
        val outcome = service.verifyAllChains()

        assertFalse(outcome.hasBreak)
        val captor = argumentCaptor<DatabaseMaintenanceEvent>()
        verify(maintenanceService).recordMaintenanceEvent(captor.capture())
        assertEquals(DatabaseMaintenanceType.CHAIN_VERIFICATION, captor.firstValue.type)
        assertEquals(DatabaseMaintenanceStatus.SUCCESS, captor.firstValue.status)
        verify(messageCenterService, never()).record(any(), anyOrNull())
    }

    @Test
    fun `RS-12 - a clean run with populated journals reports every row checked`() {
        val genesis = NumberJournalChain.genesisHash("P", 1L)
        val nj1 = numberJournalEntry(1, 1L, genesis)
        val nj2 = numberJournalEntry(2, 1L, nj1.entryHash)
        whenever(numberJournalRepository.streamAllBatched(eq(0L), any())).thenReturn(listOf(nj1, nj2))
        whenever(numberJournalRepository.streamAllBatched(eq(2L), any())).thenReturn(emptyList())
        whenever(numberRangeRepository.findById(1L)).thenReturn(range(1L))

        val iacGenesis = InvoiceAuditJournalChain.genesisHash()
        val iac1 = invoiceAuditEntry(1, iacGenesis)
        whenever(invoiceAuditJournalRepository.streamBatch(eq(0L), any())).thenReturn(listOf(iac1))
        whenever(invoiceAuditJournalRepository.streamBatch(eq(1L), any())).thenReturn(emptyList())

        val outcome = service.verifyAllChains()

        assertFalse(outcome.hasBreak)
        assertEquals(2L, outcome.numberJournal.checked)
        assertEquals(1L, outcome.invoiceAudit.checked)
        assertEquals(InvoiceAuditChainVerification.Valid, outcome.invoiceAudit.verification)
    }

    // ─── breaks ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RS-12 - a number_journal break records a FAILED event and an ERROR system message`() {
        val genesis = NumberJournalChain.genesisHash("P", 1L)
        val nj1 = numberJournalEntry(1, 1L, genesis)
        val tampered = numberJournalEntry(2, 1L, "wrong-prev-hash")
        whenever(numberJournalRepository.streamAllBatched(eq(0L), any())).thenReturn(listOf(nj1, tampered))
        whenever(numberJournalRepository.streamAllBatched(eq(2L), any())).thenReturn(emptyList())
        whenever(numberRangeRepository.findById(1L)).thenReturn(range(1L))

        val outcome = service.verifyAllChains()

        assertTrue(outcome.hasBreak)
        assertTrue(outcome.numberJournal.breaks.containsKey(1L))
        val eventCaptor = argumentCaptor<DatabaseMaintenanceEvent>()
        verify(maintenanceService).recordMaintenanceEvent(eventCaptor.capture())
        assertEquals(DatabaseMaintenanceStatus.FAILED, eventCaptor.firstValue.status)
        verify(messageCenterService).record(
            argThat {
                severity == SystemMessageSeverity.ERROR
            },
            recipientUserId = anyOrNull(),
        )
    }

    @Test
    fun `RS-12 - an invoice_audit_journal break records a FAILED event`() {
        val iacGenesis = InvoiceAuditJournalChain.genesisHash()
        val bogus = invoiceAuditEntry(1, "wrong-prev-hash")
        whenever(invoiceAuditJournalRepository.streamBatch(eq(0L), any())).thenReturn(listOf(bogus))

        val outcome = service.verifyAllChains()

        assertTrue(outcome.hasBreak)
        assertTrue(outcome.invoiceAudit.verification is InvoiceAuditChainVerification.Broken)
        val eventCaptor = argumentCaptor<DatabaseMaintenanceEvent>()
        verify(maintenanceService).recordMaintenanceEvent(eventCaptor.capture())
        assertEquals(DatabaseMaintenanceStatus.FAILED, eventCaptor.firstValue.status)
    }

    @Test
    fun `RS-12 - an unresolvable number range is reported as a break`() {
        val orphan = numberJournalEntry(1, 42L, "irrelevant")
        whenever(numberJournalRepository.streamAllBatched(eq(0L), any())).thenReturn(listOf(orphan))
        whenever(numberJournalRepository.streamAllBatched(eq(1L), any())).thenReturn(emptyList())
        whenever(numberRangeRepository.findById(42L)).thenReturn(null)

        val outcome = service.verifyAllChains()

        assertTrue(outcome.hasBreak)
        val state = outcome.numberJournal.breaks[42L]
        assertTrue(state is NumberJournalRangeState.Broken)
    }

    // ─── paging across a batch boundary ──────────────────────────────────────────────

    @Test
    fun `RS-12 - a full number_journal page is followed by a second page and both are checked`() {
        val genesis = NumberJournalChain.genesisHash("P", 1L)
        val firstPage = numberJournalChain(batchSize, 1L, 1L, genesis)
        val secondPage = numberJournalChain(1, 1L, batchSize + 1L, firstPage.last().entryHash)
        whenever(numberJournalRepository.streamAllBatched(eq(0L), any())).thenReturn(firstPage)
        whenever(numberJournalRepository.streamAllBatched(eq(batchSize.toLong()), any())).thenReturn(secondPage)
        whenever(numberRangeRepository.findById(1L)).thenReturn(range(1L))

        val outcome = service.verifyAllChains()

        assertFalse(outcome.hasBreak)
        assertEquals(batchSize + 1L, outcome.numberJournal.checked)
        val afterSeqCaptor = argumentCaptor<Long>()
        verify(numberJournalRepository, times(2)).streamAllBatched(afterSeqCaptor.capture(), any())
        assertEquals(listOf(0L, batchSize.toLong()), afterSeqCaptor.allValues)
    }

    @Test
    fun `RS-12 - a full invoice_audit_journal page is followed by a second page and both are checked`() {
        val iacGenesis = InvoiceAuditJournalChain.genesisHash()
        val firstPage = invoiceAuditChain(batchSize, 1L, iacGenesis)
        val secondPage = invoiceAuditChain(1, batchSize + 1L, firstPage.last().entryHash)
        whenever(invoiceAuditJournalRepository.streamBatch(eq(0L), any())).thenReturn(firstPage)
        whenever(invoiceAuditJournalRepository.streamBatch(eq(batchSize.toLong()), any())).thenReturn(secondPage)

        val outcome = service.verifyAllChains()

        assertFalse(outcome.hasBreak)
        assertEquals(batchSize + 1L, outcome.invoiceAudit.checked)
        assertEquals(InvoiceAuditChainVerification.Valid, outcome.invoiceAudit.verification)
        val afterSeqCaptor = argumentCaptor<Long>()
        verify(invoiceAuditJournalRepository, times(2)).streamBatch(afterSeqCaptor.capture(), any())
        assertEquals(listOf(0L, batchSize.toLong()), afterSeqCaptor.allValues)
    }

    // ─── edge cases ───────────────────────────────────────────────────────────────────

    @Test
    fun `RS-12 - a number_journal batch whose last entry has no seq does not advance the cursor`() {
        val genesis = NumberJournalChain.genesisHash("P", 1L)
        // One short of a full page, then a final entry that mimics a row read back with a null
        // `seq` column — the batch is still exactly BATCH_SIZE long, so the paging loop would
        // normally fetch a further page.
        val leading = numberJournalChain(batchSize - 1, 1L, 1L, genesis)
        val noSeqEntry = numberJournalEntry(batchSize.toLong(), 1L, leading.last().entryHash).copy(seq = null)
        val fullPageWithNullSeqTail = leading + noSeqEntry
        // Same eq(0L) matcher fires on both invocations only if the cursor genuinely never moved
        // past its previous value — that is exactly the behaviour under test.
        whenever(numberJournalRepository.streamAllBatched(eq(0L), any()))
            .thenReturn(fullPageWithNullSeqTail, emptyList())
        whenever(numberRangeRepository.findById(1L)).thenReturn(range(1L))

        val outcome = service.verifyAllChains()

        assertEquals(batchSize.toLong(), outcome.numberJournal.checked)
        assertTrue(outcome.numberJournal.breaks.containsKey(1L))
        val afterSeqCaptor = argumentCaptor<Long>()
        verify(numberJournalRepository, times(2)).streamAllBatched(afterSeqCaptor.capture(), any())
        assertEquals(listOf(0L, 0L), afterSeqCaptor.allValues)
    }

    @Test
    fun `RS-12 - a broken invoice_audit_journal chain stops paging even on a full batch`() {
        val iacGenesis = InvoiceAuditJournalChain.genesisHash()
        val good = invoiceAuditEntry(1, iacGenesis)
        val bad = invoiceAuditEntry(2, "wrong-prev-hash")
        // Padding to a full BATCH_SIZE page: verifyInvoiceAuditChainBatch returns as soon as it
        // hits `bad`, so these are never read — they only make the page size == BATCH_SIZE, so a
        // second page would be fetched by size alone (line 110) if the break didn't already stop
        // the outer loop (line 102) first.
        val filler = invoiceAuditEntry(3, "irrelevant-never-verified")
        val fullBrokenPage = listOf(good, bad) + List(batchSize - 2) { filler }
        whenever(invoiceAuditJournalRepository.streamBatch(eq(0L), any())).thenReturn(fullBrokenPage)

        val outcome = service.verifyAllChains()

        assertEquals(1L, outcome.invoiceAudit.checked)
        assertTrue(outcome.invoiceAudit.verification is InvoiceAuditChainVerification.Broken)
        verify(invoiceAuditJournalRepository, times(1)).streamBatch(any(), any())
    }
}
