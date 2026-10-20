package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Pure chain logic, no database. Covers N4-01 through N4-06. */
class NumberJournalChainTest {

    private val now = OffsetDateTime.parse("2026-08-21T10:00:00Z")

    private fun entry(
        seq: Long,
        prevHash: String,
        number: String = "DEVEDGE-2026-08-21-00001",
        entityId: Long? = 42L,
        reason: String? = null,
        occurredAt: OffsetDateTime = now,
    ): NumberJournalEntry {
        val unhashed = NumberJournalEntry(
            seq = seq, numberRangeId = 1L, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
            numberPrefix = "DEVEDGE", numberSequence = seq, number = number, entityType = "Invoice",
            entityId = entityId, reason = reason, actor = "system", occurredAt = occurredAt,
            prevHash = prevHash, entryHash = "",
        )
        return unhashed.copy(entryHash = NumberJournalChain.computeEntryHash(prevHash, seq, unhashed))
    }

    /** An ALLOCATED entry for the standalone hash tests below; only the varied fields are
     *  parameters, the chain fields ([NumberJournalEntry.prevHash]/[NumberJournalEntry.entryHash])
     *  stay blank because [NumberJournalChain.computeEntryHash] never reads them. */
    private fun allocation(
        numberPrefix: String? = "P",
        number: String? = "P-2026-00001",
        reason: String? = null,
        actor: String = "system",
        occurredAt: OffsetDateTime = now,
    ) = NumberJournalEntry(
        numberRangeId = 1L, event = NumberJournalEvent.ALLOCATED, periodKey = "2026",
        numberPrefix = numberPrefix, numberSequence = 1L, number = number, entityType = "Invoice",
        entityId = 1L, reason = reason, actor = actor, occurredAt = occurredAt,
        prevHash = "", entryHash = "",
    )

    private fun hashOf(prevHash: String, seq: Long, entry: NumberJournalEntry): String =
        NumberJournalChain.computeEntryHash(prevHash, seq, entry)

    // ─── N4-01/02 — the chain itself ─────────────────────────────────────────

    @Test
    fun `N4-01 - the second entry's prevHash equals the first entry's entryHash`() {
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)

        assertEquals(first.entryHash, second.prevHash)
    }

    @Test
    fun `N4-02 - a chain of unmodified entries verifies as valid`() {
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        val third = entry(3, second.entryHash)

        assertEquals(ChainVerification.Valid, verifyChain(listOf(first, second, third), genesis))
    }

    // ─── N4-03/04 — tampering is detected ────────────────────────────────

    @Test
    fun `N4-03 - a changed field in a middle entry is detected, naming that entry`() {
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        val tampered = second.copy(reason = "nachträglich eingefügt") // entryHash stays the old one
        val third = entry(3, second.entryHash) // prevHash still refers to the real hash

        val result = verifyChain(listOf(first, tampered, third), genesis)

        assertEquals(ChainVerification.Broken(2, "entryHash passt nicht zum Inhalt des Eintrags"), result)
    }

    @Test
    fun `N4 - an entry without a seq cannot be checked and breaks the chain there`() {
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val first = entry(1, genesis)
        val seqless = first.copy(seq = null)

        val result = verifyChain(listOf(seqless), genesis)

        assertEquals(ChainVerification.Broken(-1, "Eintrag ohne seq kann nicht geprüft werden"), result)
    }

    @Test
    fun `N4-04 - a removed middle entry breaks the chain at its successor`() {
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val first = entry(1, genesis)
        val second = entry(2, first.entryHash)
        val third = entry(3, second.entryHash)

        // "second" is simply missing from the list — third.prevHash points into nothing.
        val result = verifyChain(listOf(first, third), genesis)

        assertEquals(ChainVerification.Broken(3, "prevHash weicht vom vorherigen entryHash ab"), result)
    }

    // ─── N4-05 — the test case that would otherwise make the chain worthless ─────────────

    @Test
    fun `N4-05 - a null field and a blank-string field never collide in the hash`() {
        val genesis = NumberJournalChain.genesisHash("X", 1L)
        val withNullReason = hashOf(genesis, 1L, allocation(reason = null))
        val withBlankReason = hashOf(genesis, 1L, allocation(reason = ""))
        assertNotEquals(withNullReason, withBlankReason)
    }

    @Test
    fun `N4-05 - null number and null prefix produce a different hash than swapped values`() {
        val genesis = NumberJournalChain.genesisHash("X", 1L)
        // Two entries where one field is blank ("") and another is missing (null), but at
        // swapped positions -- without unambiguous serialization they could collide.
        val a = hashOf(genesis, 1L, allocation(numberPrefix = null, number = ""))
        val b = hashOf(genesis, 1L, allocation(numberPrefix = "", number = null))
        assertNotEquals(a, b)
    }

    // ─── N4-06 — the start value is covered by the anchor ────────────────────

    @Test
    fun `N4-06 - genesis hash differs for a different start value`() {
        val withStart1 = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val withStart4712 = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 4712L)
        assertNotEquals(withStart1, withStart4712)
    }

    @Test
    fun `N4-06 - a shifted start value breaks the chain at the first entry`() {
        val genesisAsRecorded = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val first = entry(1, genesisAsRecorded)

        // someone later claims the range always started at 4712
        val claimedGenesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 4712L)

        val result = verifyChain(listOf(first), claimedGenesis)
        assertEquals(ChainVerification.Broken(1, "prevHash weicht vom vorherigen entryHash ab"), result)
    }

    @Test
    fun `entryHash is deterministic for identical input`() {
        val a = hashOf("prev", 1L, allocation())
        val b = hashOf("prev", 1L, allocation())
        assertEquals(a, b)
    }

    // ─── N4 §4 — the seal fields are part of the chain, not just decoration ────────

    @Test
    fun `N4-05 - a PERIOD_SEALED entry's summary fields are covered by the hash`() {
        val genesis = NumberJournalChain.genesisHash("INVOICE_GAPLESS", 1L)
        val seal = NumberJournalEntry(
            numberRangeId = 1L, event = NumberJournalEvent.PERIOD_SEALED, periodKey = "2026",
            actor = "system", occurredAt = now, prevHash = "", entryHash = "",
            sealedStartValue = 1L, sealedCount = 42L, sealedFirstNumber = "P-2026-00001", sealedLastNumber = "P-2026-00042",
        )
        val asRecorded = hashOf(genesis, 1L, seal)
        // someone later claims there were only 41 allocations — everything else the same
        val tamperedCount = hashOf(genesis, 1L, seal.copy(sealedCount = 41L))
        assertNotEquals(asRecorded, tamperedCount)
    }

    @Test
    fun `entryHash changes when seq changes, everything else equal`() {
        val a = hashOf("prev", 1L, allocation())
        val b = hashOf("prev", 2L, allocation())
        assertNotEquals(a, b)
    }

    // ─── The parameter-object signature must not change a single persisted hash ───────────────

    /** Golden values computed independently of this code (Python `hashlib.sha256` over the
     *  documented 0x1F-separated, 0x00-for-null payload). A change here means every existing
     *  `number_journal.entry_hash` would stop verifying — never "fix" the expected value. */
    @Test
    fun `T12 - an ALLOCATED entry hashes to the value recorded before the signature change`() {
        val entry = allocation().copy(entityId = 42L)

        assertEquals("ff5c1b2e6fd17f655152ef9edce2af5a3daa017c8c9ea5ebbbf33544c42443a3", hashOf("genesis", 1L, entry))
    }

    @Test
    fun `T12 - a PERIOD_SEALED entry with a reason hashes to the value recorded before the signature change`() {
        val seal = NumberJournalEntry(
            numberRangeId = 1L, event = NumberJournalEvent.PERIOD_SEALED, periodKey = "2026",
            reason = "Jahresabschluss", actor = "system", occurredAt = now, prevHash = "", entryHash = "",
            sealedStartValue = 1L, sealedCount = 42L, sealedFirstNumber = "P-2026-00001", sealedLastNumber = "P-2026-00042",
        )

        assertEquals("0af678870924aa0acd4f64a0467b8860326548ba037a59dadb4f5800d2810b88", hashOf("genesis", 7L, seal))
    }

    @Test
    fun `T12 - the entry's own seq, range, prevHash and entryHash fields never enter the hash`() {
        val base = allocation()
        val decorated = base.copy(seq = 99L, numberRangeId = 77L, prevHash = "other", entryHash = "stale")

        assertEquals(hashOf("prev", 1L, base), hashOf("prev", 1L, decorated))
    }

    @Test
    fun `T12 - the same instant in a different offset hashes identically`() {
        val utc = allocation(occurredAt = OffsetDateTime.parse("2026-08-21T10:00:00Z"))
        val berlin = allocation(occurredAt = OffsetDateTime.parse("2026-08-21T12:00:00+02:00"))

        assertEquals(hashOf("prev", 1L, utc), hashOf("prev", 1L, berlin))
    }

    @Test
    fun `T12 - a different instant changes the hash`() {
        val a = allocation(occurredAt = now)
        val b = allocation(occurredAt = now.plusNanos(1_000_000))

        assertNotEquals(hashOf("prev", 1L, a), hashOf("prev", 1L, b))
    }

    // ─── IMP-4 — the boundary of canonicalization ───────────────────────

    private fun canonicalizationCandidate(actor: String = "system", reason: String? = null) = NumberJournalEntry(
        numberRangeId = 1L,
        event = NumberJournalEvent.ALLOCATED,
        periodKey = "2026",
        numberPrefix = "P",
        numberSequence = 1L,
        number = "P-2026-00001",
        entityType = "Invoice",
        entityId = 1L,
        reason = reason,
        actor = actor,
        occurredAt = now,
        prevHash = "",
        entryHash = "",
    )

    /**
     * Why this check exists: if the separator itself occurs **inside** a field, two different
     * entries produce the same payload. `reason="a\u001Fb", actor="system"` and
     * `reason="a", actor="b\u001Fsystem"` both serialize to `…a\u001Fb\u001Fsystem…`.
     * The hash would compute as fine while actually describing something different from what's
     * recorded in the journal.
     */
    @Test
    fun `IMP-4 - ein Trennzeichen im Feld laesst zwei verschiedene Eintraege gleich aussehen`() {
        val einer = hashOf("prev", 1L, allocation(reason = "a\u001Fb", actor = "system"))
        val anderer = hashOf("prev", 1L, allocation(reason = "a", actor = "b\u001Fsystem"))

        assertEquals(
            einer, anderer,
            "genau diese Kollision macht die Pruefung noetig — solche Werte duerfen nie in die Kette gelangen",
        )
    }

    @Test
    fun `IMP-4 - ein Trennzeichen im actor wird beim Anhaengen abgelehnt`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            NumberJournalChain.assertCanonicalizable(canonicalizationCandidate(actor = "system\u001Fadmin"))
        }
        assertTrue(ex.message!!.contains("actor"), "die Meldung muss das Feld benennen: ${ex.message}")
    }

    @Test
    fun `IMP-4 - auch das NULL-Markierungszeichen im Freitext wird abgelehnt`() {
        assertThrows(IllegalArgumentException::class.java) {
            NumberJournalChain.assertCanonicalizable(canonicalizationCandidate(reason = "Storno\u0000"))
        }
    }

    @Test
    fun `IMP-4 - gewoehnliche Texte inklusive Umlauten und Prozentzeichen bleiben zulaessig`() {
        NumberJournalChain.assertCanonicalizable(
            canonicalizationCandidate(actor = "buero@example.test", reason = "Storno — Rueckgabe, 50 %"),
        )
    }

    // ── Data class equality and hashing ────────────────────────────────

    @Test
    fun `two NumberJournalEntry instances with identical fields are equal`() {
        val entry1 = NumberJournalEntry(
            seq = 1L,
            numberRangeId = 100L,
            event = NumberJournalEvent.ALLOCATED,
            periodKey = "2026",
            numberPrefix = "P",
            numberSequence = 1L,
            number = "P-2026-00001",
            entityType = "Invoice",
            entityId = 1L,
            reason = "test",
            actor = "admin",
            occurredAt = now,
            prevHash = "hash1",
            entryHash = "hash2",
        )
        val entry2 = NumberJournalEntry(
            seq = 1L,
            numberRangeId = 100L,
            event = NumberJournalEvent.ALLOCATED,
            periodKey = "2026",
            numberPrefix = "P",
            numberSequence = 1L,
            number = "P-2026-00001",
            entityType = "Invoice",
            entityId = 1L,
            reason = "test",
            actor = "admin",
            occurredAt = now,
            prevHash = "hash1",
            entryHash = "hash2",
        )
        assertEquals(entry1, entry2)
        assertEquals(entry1.hashCode(), entry2.hashCode())
    }

    @Test
    fun `NumberJournalEntry with differing seq is not equal`() {
        val entry1 = canonicalizationCandidate().copy(seq = 1L)
        val entry2 = canonicalizationCandidate().copy(seq = 2L)
        assertNotEquals(entry1, entry2)
    }

    @Test
    fun `NumberJournalEntry with differing numberRangeId is not equal`() {
        val entry1 = canonicalizationCandidate().copy(numberRangeId = 100L)
        val entry2 = canonicalizationCandidate().copy(numberRangeId = 200L)
        assertNotEquals(entry1, entry2)
    }

    @Test
    fun `NumberJournalEntry is not equal to null`() {
        val entry = canonicalizationCandidate()
        assertNotEquals(entry, null)
    }

    @Test
    fun `NumberJournalEntry is not equal to a different type`() {
        val entry = canonicalizationCandidate()
        assertNotEquals(entry, "not a NumberJournalEntry")
    }

    @Test
    fun `NumberJournalEntry copy with modified fields creates new instance`() {
        val entry1 = canonicalizationCandidate(actor = "admin")
        val entry2 = entry1.copy(actor = "system")
        assertEquals("admin", entry1.actor)
        assertEquals("system", entry2.actor)
        assertNotEquals(entry1, entry2)
    }

    @Test
    fun `two NumberJournalChainStepResult instances with identical fields are equal`() {
        val state = mapOf(1L to NumberJournalRangeState.Ongoing("hash1"))
        val result1 = NumberJournalChainStepResult(state, 10L)
        val result2 = NumberJournalChainStepResult(state, 10L)
        assertEquals(result1, result2)
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `NumberJournalChainStepResult with differing nextState is not equal`() {
        val state1 = mapOf(1L to NumberJournalRangeState.Ongoing("hash1"))
        val state2 = mapOf(1L to NumberJournalRangeState.Ongoing("hash2"))
        val result1 = NumberJournalChainStepResult(state1, 10L)
        val result2 = NumberJournalChainStepResult(state2, 10L)
        assertNotEquals(result1, result2)
    }

    @Test
    fun `NumberJournalChainStepResult with differing checked count is not equal`() {
        val state = mapOf(1L to NumberJournalRangeState.Ongoing("hash1"))
        val result1 = NumberJournalChainStepResult(state, 10L)
        val result2 = NumberJournalChainStepResult(state, 20L)
        assertNotEquals(result1, result2)
    }

    @Test
    fun `ChainVerification Valid is equal to another Valid instance`() {
        val valid1: ChainVerification = ChainVerification.Valid
        val valid2: ChainVerification = ChainVerification.Valid
        assertEquals(valid1, valid2)
    }

    @Test
    fun `ChainVerification Broken with same seq and reason are equal`() {
        val broken1: ChainVerification = ChainVerification.Broken(1L, "reason")
        val broken2: ChainVerification = ChainVerification.Broken(1L, "reason")
        assertEquals(broken1, broken2)
        assertEquals(broken1.hashCode(), broken2.hashCode())
    }

    @Test
    fun `ChainVerification Broken with different seq are not equal`() {
        val broken1: ChainVerification = ChainVerification.Broken(1L, "reason")
        val broken2: ChainVerification = ChainVerification.Broken(2L, "reason")
        assertNotEquals(broken1, broken2)
    }

    @Test
    fun `NumberJournalRangeState Ongoing with same hash are equal`() {
        val ongoing1: NumberJournalRangeState = NumberJournalRangeState.Ongoing("hash1")
        val ongoing2: NumberJournalRangeState = NumberJournalRangeState.Ongoing("hash1")
        assertEquals(ongoing1, ongoing2)
        assertEquals(ongoing1.hashCode(), ongoing2.hashCode())
    }

    @Test
    fun `NumberJournalRangeState Broken with same seq and reason are equal`() {
        val broken1: NumberJournalRangeState = NumberJournalRangeState.Broken(1L, "reason")
        val broken2: NumberJournalRangeState = NumberJournalRangeState.Broken(1L, "reason")
        assertEquals(broken1, broken2)
        assertEquals(broken1.hashCode(), broken2.hashCode())
    }
}
