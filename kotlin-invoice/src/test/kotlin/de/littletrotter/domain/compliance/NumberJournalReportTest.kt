package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** [buildReport] — the business-level audit beyond the pure chain check. Covers a clean-sequence
 *  report and one with deviations. */
class NumberJournalReportTest {

    private val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
    private val now = OffsetDateTime.parse("2026-08-21T10:00:00Z")

    private fun chain(sequences: List<Long>): List<NumberJournalEntry> {
        var prev = genesis
        return sequences.map { seq ->
            val entrySeq = sequences.indexOf(seq).toLong() + 1
            val unhashed = NumberJournalEntry(
                seq = entrySeq, numberRangeId = 1L, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
                numberPrefix = "P", numberSequence = seq, number = "P-2026-%05d".format(seq),
                entityType = "Invoice", entityId = entrySeq, actor = "system", occurredAt = now,
                prevHash = prev, entryHash = "",
            )
            val entry = unhashed.copy(entryHash = NumberJournalChain.computeEntryHash(prev, entrySeq, unhashed))
            prev = entry.entryHash
            entry
        }
    }

    /** Like [chain], but with a periodKey per entry instead of a fixed "2026" — for N4-16. */
    private fun chainAcrossPeriods(entries: List<Pair<String, Long>>): List<NumberJournalEntry> {
        var prev = genesis
        return entries.mapIndexed { index, (periodKey, seq) ->
            val entrySeq = index.toLong() + 1
            val unhashed = NumberJournalEntry(
                seq = entrySeq, numberRangeId = 1L, event = NumberJournalEvent.ALLOCATED, periodKey = periodKey,
                numberPrefix = "P", numberSequence = seq, number = "P-$periodKey-%05d".format(seq),
                entityType = "Invoice", entityId = entrySeq, actor = "system", occurredAt = now,
                prevHash = prev, entryHash = "",
            )
            val entry = unhashed.copy(entryHash = NumberJournalChain.computeEntryHash(prev, entrySeq, unhashed))
            prev = entry.entryHash
            entry
        }
    }

    // N4-16 — the reset to the start value at year-end (YEARLY reset) looks like a break in the
    // raw numbers (the same "1" occurs twice) and isn't one: the second "1" belongs to a
    // different period.
    @Test
    fun `N4-16 - a period rollover to the reset start value is not a Gap or Duplicate`() {
        val entries = chainAcrossPeriods(
            listOf("2026" to 1L, "2026" to 2L, "2026" to 3L, "2027" to 1L, "2027" to 2L),
        )
        val report = buildReport(1L, entries, genesis)

        assertTrue(report.isClean, "Befunde: ${report.findings}")
    }

    @Test
    fun `N4-16 - a genuine gap inside one period is still found despite another period existing`() {
        val entries = chainAcrossPeriods(
            listOf("2026" to 1L, "2026" to 3L, "2027" to 1L, "2027" to 2L),
        )
        val report = buildReport(1L, entries, genesis)

        assertEquals(listOf(NumberJournalFinding.Gap(2L)), report.findings)
    }

    @Test
    fun `N4-14 - a gapless run reports clean with no findings`() {
        val report = buildReport(1L, chain(listOf(1, 2, 3)), genesis)

        assertTrue(report.isClean)
        assertTrue(report.findings.isEmpty())
        assertEquals(1L, report.firstSequence)
        assertEquals(3L, report.lastSequence)
        assertEquals(3, report.entryCount)
    }

    @Test
    fun `a missing sequence in the middle is reported as a Gap`() {
        val report = buildReport(1L, chain(listOf(1, 2, 4)), genesis)

        assertFalse(report.isClean)
        assertEquals(listOf(NumberJournalFinding.Gap(3L)), report.findings)
    }

    @Test
    fun `the same sequence twice is reported as a Duplicate, not two gaps`() {
        val report = buildReport(1L, chain(listOf(1, 2, 2, 3)), genesis)

        assertFalse(report.isClean)
        assertEquals(listOf(NumberJournalFinding.Duplicate(2L, 2)), report.findings)
    }

    @Test
    fun `a period whose allocations all lack a numberSequence contributes no gap or duplicate findings`() {
        // A malformed/legacy ALLOCATED entry with no numberSequence at all: the period still
        // groups (periodKey is present), but periodSequences.mapNotNull leaves it empty — the
        // per-period gap scan must skip it rather than crash on an empty min/max.
        val seqless = NumberJournalEntry(
            seq = 1L, numberRangeId = 1L, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
            numberSequence = null, actor = "system", occurredAt = now, prevHash = genesis, entryHash = "irrelevant",
        )

        val report = buildReport(1L, listOf(seqless), genesis)

        assertTrue(report.findings.isEmpty(), "keine Sequenznummer -> nichts zu pruefen, kein Fehlalarm: ${report.findings}")
    }

    @Test
    fun `an empty journal is clean and reports no first or last sequence`() {
        val report = buildReport(1L, emptyList(), genesis)

        assertTrue(report.isClean)
        assertEquals(null, report.firstSequence)
        assertEquals(null, report.lastSequence)
    }

    // ─── N4 §4/§5 — seal mismatch ─────────────────────────────────

    private fun sealEntry(afterSeq: List<Long>, periodKey: String = "2026"): NumberJournalEntry {
        val allocations = chain(afterSeq)
        val prev = allocations.last().entryHash
        val sealSeq = afterSeq.size.toLong() + 1
        val unhashed = NumberJournalEntry(
            seq = sealSeq, numberRangeId = 1L, event = NumberJournalEvent.PERIOD_SEALED, periodKey = periodKey,
            actor = "system", occurredAt = now, prevHash = prev, entryHash = "",
            sealedStartValue = 1L, sealedCount = afterSeq.size.toLong(),
            sealedFirstNumber = "P-2026-%05d".format(afterSeq.first()), sealedLastNumber = "P-2026-%05d".format(afterSeq.last()),
        )
        return unhashed.copy(entryHash = NumberJournalChain.computeEntryHash(prev, sealSeq, unhashed))
    }

    @Test
    fun `a seal whose recorded chain head matches the externally kept one reports no finding`() {
        val allocations = chain(listOf(1, 2, 3))
        val seal = sealEntry(listOf(1, 2, 3))

        val report = buildReport(1L, allocations + seal, genesis, externalSeals = mapOf("2026" to seal.prevHash))

        assertTrue(report.isClean, "Befunde: ${report.findings}")
    }

    @Test
    fun `a seal whose recorded chain head differs from the externally kept one is a SealMismatch`() {
        val allocations = chain(listOf(1, 2, 3))
        val seal = sealEntry(listOf(1, 2, 3))
        val externalHead = "0".repeat(64) // was tatsaechlich an den Steuerberater ging, weicht ab

        val report = buildReport(1L, allocations + seal, genesis, externalSeals = mapOf("2026" to externalHead))

        assertFalse(report.isClean)
        assertEquals(
            listOf(NumberJournalFinding.SealMismatch("2026", seal.prevHash, externalHead)),
            report.findings,
        )
    }

    @Test
    fun `a seal with no externally kept value to compare against is not flagged`() {
        val allocations = chain(listOf(1, 2, 3))
        val seal = sealEntry(listOf(1, 2, 3))

        val report = buildReport(1L, allocations + seal, genesis)

        assertTrue(report.isClean, "ohne externen Vergleichswert kann SIEGELABWEICHUNG nicht geprueft werden — kein Fehlalarm")
    }

    @Test
    fun `a broken chain makes the report unclean even without gaps or duplicates`() {
        val entries = chain(listOf(1, 2, 3))
        val tampered = entries[1].copy(reason = "manipuliert")
        val report = buildReport(1L, listOf(entries[0], tampered, entries[2]), genesis)

        assertFalse(report.isClean)
        assertTrue(report.chainVerification is ChainVerification.Broken)
        assertTrue(report.findings.isEmpty(), "die Sequenzzahlen selbst sind lueckenlos, nur die Kette ist gebrochen")
    }

    // ─── IMP-2 — the lower bound of the gap check ──────────────

    /** The actual point of IMP-2: if the **first** numbers of a period are missing, a check
     *  starting at the smallest number present begins right after them and sees nothing. */
    @Test
    fun `IMP-2 - fehlende erste Nummern einer Periode werden nur mit Startwert gefunden`() {
        val entries = chain(listOf(4, 5, 6))

        val ohneStartwert = buildReport(1L, entries, genesis)
        assertTrue(ohneStartwert.findings.isEmpty(), "ohne Startwert beginnt die Pruefung bei 4 und sieht 1..3 nicht")

        val mitStartwert = buildReport(1L, entries, genesis, startValue = 1L)
        assertEquals(
            listOf(1L, 2L, 3L),
            mitStartwert.findings.filterIsInstance<NumberJournalFinding.Gap>().map { it.missingSequence },
            "die drei nie protokollierten ersten Nummern muessen als Luecke erscheinen",
        )
    }

    @Test
    fun `IMP-2 - eine ab dem Startwert lueckenlose Folge bleibt ohne Beanstandung`() {
        val report = buildReport(1L, chain(listOf(1, 2, 3)), genesis, startValue = 1L)

        assertTrue(report.isClean, "1..3 ab Startwert 1 ist lueckenlos: ${report.findings}")
    }

    /** A journal starting below the start value must not suddenly report a slew of false gaps —
     *  the entries exist, just lower. */
    @Test
    fun `IMP-2 - ein Journal unterhalb des Startwerts erzeugt keine Schein-Luecken`() {
        val report = buildReport(1L, chain(listOf(1, 2, 3)), genesis, startValue = 100L)

        assertTrue(report.isClean, "kein Fehlalarm fuer 1..3, obwohl der Startwert 100 waere: ${report.findings}")
    }

    /** Under a YEARLY reset, **every** period starts again at the start value — the bound
     *  therefore applies per period, not just the first (extends N4-16). */
    @Test
    fun `IMP-2 - die untere Grenze gilt je Periode, nicht nur fuer die erste`() {
        val entries = chainAcrossPeriods(listOf("2026" to 1L, "2026" to 2L, "2027" to 3L))

        val report = buildReport(1L, entries, genesis, startValue = 1L)

        assertEquals(
            listOf(1L, 2L),
            report.findings.filterIsInstance<NumberJournalFinding.Gap>().map { it.missingSequence },
            "2027 beginnt bei 3 statt beim Startwert 1 — die 1 und die 2 fehlen dort",
        )
    }
}
