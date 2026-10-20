package de.littletrotter.application.reference

import de.littletrotter.application.masterdata.MasterDataNumberService
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.fixture.NumberRangeEditor
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.shared.numbering.NumberFormatter as SharedNumberFormatter
import de.littletrotter.shared.numbering.NumberPattern
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.value.ArticleNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.LocalDate

/**
 * Allocation semantics against fake ports (no CDI, no DB): `peek` vs. `allocate`,
 * `startValue`, period-key reset, and the master-data orchestration (`assign` sets prefix/counter/value,
 * never overwrites a manually typed value, does not mutate without an active number range, and
 * lets the short hash backfill on an existing entity while the number itself may not).
 */
class NumberServicesTest {

    private val ranges = FakeRanges()
    private val counters = FakeCounters()
    private val time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))
    private val journal = RecordingNumberJournalRepository()
    private val service = NumberRangeService(ranges, counters, journal, time)
    private val masterData = MasterDataNumberService(
        service, mock<RecipientRepository>(), mock<ArticleRepository>(), mock<SenderProfileRepository>(), time,
    )

    private fun article(name: String = "Beratung") = anArticle {
        this.name = name
        category = InvoiceItemCategory.SERVICE
    }

    private fun recipient(name: String = "Müller GmbH") = aRecipient {
        this.name = name
    }

    private fun seller(name: String = "DevEdge") = aSenderProfile {
        this.name = name
        invoicePrefix = "DEVEDGE-"
    }

    private fun install(subject: NumberRangeSubject, configure: NumberRangeEditor.() -> Unit = {}) =
        ranges.install(NumberRange().edit {
            code = "${subject.name}_DEFAULT"
            displayName = ""
            this.subject = subject
            counterWidth = 5
            continuity = Continuity.UNIQUE_ONLY
            datePart = DatePart.NONE
            resetCycle = ResetCycle.NEVER
            prefixSource = de.littletrotter.domain.reference.PrefixSource.NONE
            configure()
        })

    // ── QUOTE: the counter lives in the quote module ────────────────────────────────

    @Test
    fun `QUOTE wird von peek, allocate, hasActiveRangeFor, syncCounterTo und sealPeriod abgewiesen`() {
        install(NumberRangeSubject.QUOTE)
        val ctx = NumberContext(LocalDate.of(2026, 6, 15))
        val message = "Nummernkreis-Subjekt QUOTE: der Zähler liegt im Angebotsmodul"
        val calls = listOf<() -> Any?>(
            { service.peek(NumberRangeSubject.QUOTE, ctx) },
            { service.allocate(NumberRangeSubject.QUOTE, ctx) },
            { service.hasActiveRangeFor(NumberRangeSubject.QUOTE) },
            { service.syncCounterTo(NumberRangeSubject.QUOTE, 5L) },
            { service.sealPeriod(NumberRangeSubject.QUOTE, "2026") },
        )
        calls.forEach { call ->
            val e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { call() }
            assertEquals(message, e.message)
        }
    }

    // ── allocation ──────────────────────────────────────────────────────────────────

    @Test
    fun `peek wirft NoActiveNumberRangeException ohne aktiven Nummernkreis`() {
        org.junit.jupiter.api.Assertions.assertThrows(de.littletrotter.domain.reference.NoActiveNumberRangeException::class.java) {
            service.peek(NumberRangeSubject.CATALOGUE, NumberContext(LocalDate.of(2026, 8, 21)))
        }
    }

    @Test
    fun `allocate wirft NoActiveNumberRangeException ohne aktiven Nummernkreis`() {
        org.junit.jupiter.api.Assertions.assertThrows(de.littletrotter.domain.reference.NoActiveNumberRangeException::class.java) {
            service.allocate(NumberRangeSubject.CATALOGUE, NumberContext(LocalDate.of(2026, 8, 21)))
        }
    }

    @Test
    fun `peek zweimal liefert dieselbe Nummer und verbraucht nichts`() {
        install(NumberRangeSubject.CATALOGUE, { startValue = 4712 })
        val ctx = NumberContext(LocalDate.of(2026, 8, 21))
        val first = service.peek(NumberRangeSubject.CATALOGUE, ctx)
        val second = service.peek(NumberRangeSubject.CATALOGUE, ctx)
        assertEquals(first, second)
        assertEquals(4712, counters.peekNext(
            de.littletrotter.domain.reference.NumberRange().edit { startValue = 4712 }, "*", ""
        ))
    }

    @Test
    fun `allocate zweimal ergibt zwei Nummern und erhoeht den Zaehler um 2`() {
        install(NumberRangeSubject.CATALOGUE)
        val ctx = NumberContext(LocalDate.of(2026, 8, 21))
        assertEquals("00001", service.allocate(NumberRangeSubject.CATALOGUE, ctx).value)
        assertEquals("00002", service.allocate(NumberRangeSubject.CATALOGUE, ctx).value)
    }

    @Test
    fun `startValue bestimmt die erste Vergabe`() {
        install(NumberRangeSubject.CUSTOMER, { startValue = 4712 })
        val a = service.allocate(NumberRangeSubject.CUSTOMER, NumberContext(LocalDate.of(2026, 8, 21)))
        assertEquals("04712", a.value)
        assertEquals(4712L, a.sequence)
    }

    @Test
    fun `allocate traegt Praefix Zaehler und Wert getrennt und stimmig`() {
        install(NumberRangeSubject.CATALOGUE, {
            prefixSource = de.littletrotter.domain.reference.PrefixSource.FIXED
            fixedPrefix = "ART"
        })
        val a = service.allocate(NumberRangeSubject.CATALOGUE, NumberContext(LocalDate.of(2026, 8, 21)))
        assertEquals("ART", a.prefix)
        assertEquals(1L, a.sequence)
        assertEquals("ART-00001", a.value)
    }

    @Test
    fun `YEARLY startet in jeder Periode wieder beim Startwert`() {
        // A yearly reset needs the year in the number — without it both years would hand out the
        // same number, which is exactly what the range's own validation forbids.
        install(NumberRangeSubject.SELLER, { startValue = 4712; resetCycle = ResetCycle.YEARLY; datePart = DatePart.YEAR })
        val d2026 = NumberContext(LocalDate.of(2026, 8, 21), sellerId = 1)
        val d2027 = NumberContext(LocalDate.of(2027, 8, 21), sellerId = 1)
        assertEquals("2026-04712", service.allocate(NumberRangeSubject.SELLER, d2026).value)
        assertEquals("2027-04712", service.allocate(NumberRangeSubject.SELLER, d2027).value)
    }

    @Test
    fun `YEARLY mit Tagesdatum - die letzte Nummer des Jahres und die erste des neuen Jahres`() {
        // The shipped invoice range: gapless, yearly reset, the full date in the number.
        install(NumberRangeSubject.INVOICE, {
            id = 7L; continuity = Continuity.GAPLESS; resetCycle = ResetCycle.YEARLY; datePart = DatePart.YEAR_MONTH_DAY
        })
        val silvester = NumberContext(LocalDate.of(2026, 12, 31), sellerId = 1)
        val neujahr = NumberContext(LocalDate.of(2027, 1, 1), sellerId = 1)

        val a = service.allocate(NumberRangeSubject.INVOICE, silvester)
        val b = service.allocate(NumberRangeSubject.INVOICE, silvester)
        val c = service.allocate(NumberRangeSubject.INVOICE, neujahr)
        val d = service.allocate(NumberRangeSubject.INVOICE, neujahr)

        assertEquals(listOf("2026-12-31-00001", "2026-12-31-00002", "2027-01-01-00001", "2027-01-01-00002"), listOf(a, b, c, d).map { it.value })
        assertEquals(listOf("2026", "2026", "2027", "2027"), listOf(a, b, c, d).map { it.periodKey })
        assertEquals(listOf("2026", "2026", "2027", "2027"), journal.entries.map { it.periodKey }, "every number is journaled under the year it counts in")
    }

    @Test
    fun `YEAR_MONTH ohne monatliche Ruecksetzung zaehlt ueber den Monatswechsel weiter`() {
        install(NumberRangeSubject.SELLER, { resetCycle = ResetCycle.YEARLY; datePart = DatePart.YEAR_MONTH })
        val januar = NumberContext(LocalDate.of(2027, 1, 31), sellerId = 1)
        val februar = NumberContext(LocalDate.of(2027, 2, 1), sellerId = 1)
        assertEquals("2027-01-00001", service.allocate(NumberRangeSubject.SELLER, januar).value)
        assertEquals("2027-02-00002", service.allocate(NumberRangeSubject.SELLER, februar).value, "only the year resets, not the month")
    }

    @Test
    fun `NEVER laeuft ueber den Jahreswechsel weiter`() {
        install(NumberRangeSubject.SELLER, { resetCycle = ResetCycle.NEVER })
        val d2026 = NumberContext(LocalDate.of(2026, 8, 21), sellerId = 1)
        val d2027 = NumberContext(LocalDate.of(2027, 8, 21), sellerId = 1)
        assertEquals("00001", service.allocate(NumberRangeSubject.SELLER, d2026).value)
        assertEquals("00002", service.allocate(NumberRangeSubject.SELLER, d2027).value)
    }

    // ── master-data numbers ──────────────────────────────────────────────────────────────────

    @Test
    fun `neuer Artikel wird mit Nummer aus dem ARTICLE-Kreis vorbelegt`() {
        install(NumberRangeSubject.CATALOGUE, {
            prefixSource = de.littletrotter.domain.reference.PrefixSource.FIXED
            fixedPrefix = "ART"
        })
        val a = article()
        assertNotNull(masterData.proposeArticle(a.category))
        masterData.assignArticleNumber(a)
        assertEquals("ART", a.articleNumberPrefix)
        assertNotNull(a.articleNumberSequence)
        assertEquals(a.articleNumber?.value, SharedNumberFormatter.compose(NumberPattern(a.articleNumberPrefix, DatePart.NONE, 5), LocalDate.now(), a.articleNumberSequence!!))
    }

    @Test
    fun `Anwender ueberschreibt den Vorschlag - getippter Wert bleibt`() {
        install(NumberRangeSubject.CATALOGUE)
        val a = article()
        a.edit { articleNumber = ArticleNumber("MEINE-EIGENE-NUMMER") }
        masterData.assignArticleNumber(a)
        assertEquals("MEINE-EIGENE-NUMMER", a.articleNumber?.value)
        assertNull(a.articleNumberSequence)
    }

    @Test
    fun `ohne aktiven Kreis bleibt das Feld leer ohne Wurf`() {
        val a = article()
        assertNull(masterData.proposeArticle(a.category))
        assertNull(masterData.assignArticleNumber(a))
        assertNull(a.articleNumber)
        val r = recipient()
        assertNull(masterData.proposeCustomer(r.name))
    }

    @Test
    fun `Bestandsartikel ohne Kurznamen bekommt einen - ohne Nummer`() {
        install(NumberRangeSubject.CATALOGUE)
        val a = article()
        a.edit { id = 99L }
        assertTrue(a.ensureShortHash())
        assertNotNull(a.shortHash)
        assertNull(a.articleNumber)
    }

    @Test
    fun `propose verbraucht nichts - abbrechen liess den Zaehler unveraendert`() {
        install(NumberRangeSubject.CUSTOMER)
        val r = recipient()
        val before = counters.peekNext(active(NumberRangeSubject.CUSTOMER), "*", "")
        masterData.proposeCustomer(r.name)
        masterData.proposeCustomer(r.name)
        assertEquals(before, counters.peekNext(active(NumberRangeSubject.CUSTOMER), "*", ""))
    }

    @Test
    fun `Kurzname ist stabil - zweite Erzeugung aendert nichts`() {
        val a = article()
        a.ensureShortHash()
        val first = a.shortHash!!
        assertTrue(!a.ensureShortHash())
        assertEquals(first, a.shortHash)
    }

    @Test
    fun `Umbenennen nach der Vergabe laesst die Nummer unveraendert`() {
        install(NumberRangeSubject.CUSTOMER)
        val r = recipient("Müller GmbH")
        masterData.assignCustomerNumber(r)
        val stored = r.customerNumber
        r.edit { this.name = "Musterfirma AG" }
        assertEquals(stored, r.customerNumber)
    }

    @Test
    fun `propose mit explizitem date liefert einen Vorschlag fuer diese Periode statt fuer heute`() {
        // propose* and assign* both take an explicit date parameter for the NumberContext, so a
        // proposal (shown when the dialog opens) and the eventual allocation (on save) can be
        // pinned to the same date -- if they read different dates, a period boundary between the
        // two points in time (e.g. midnight with YEARLY) would make them diverge.
        install(NumberRangeSubject.CATALOGUE, { startValue = 1; resetCycle = ResetCycle.YEARLY; datePart = DatePart.YEAR })
        val a = article()
        val vorjahr = LocalDate.of(2025, 12, 31)

        val proposedForVorjahr = masterData.proposeArticle(a.category, vorjahr)
        val proposedForHeute = masterData.proposeArticle(a.category)

        assertEquals("2025-00001", proposedForVorjahr)
        assertNotEquals(proposedForHeute, proposedForVorjahr, "unterschiedliche Perioden muessen unterschiedliche Vorschlaege liefern")

        masterData.assignArticleNumber(a, null, vorjahr)

        assertEquals(proposedForVorjahr, a.articleNumber?.value, "Vorschlag und Vergabe auf demselben date muessen uebereinstimmen")
    }

    @Test
    fun `sellerNumber und invoicePrefix sind unabhaengig gespeichert`() {
        install(NumberRangeSubject.SELLER)
        val s = seller()
        masterData.assignSellerNumber(s)
        s.edit { invoicePrefix = "GANZ-ANDERS-" }
        assertNotNull(s.sellerNumber)
        assertEquals("GANZ-ANDERS-", s.invoicePrefix)
        assertNull(s.sellerNumberPrefix)
    }

    private fun active(subject: NumberRangeSubject): NumberRange =
        ranges.findActive(subject) ?: error("Kein aktiver Kreis installiert")

    private class FakeRanges : NumberRangeRepository {
        private val bySubject = mutableMapOf<NumberRangeSubject, NumberRange>()
        fun install(range: NumberRange) { bySubject[range.subject] = range }
        override fun findActive(subject: NumberRangeSubject) = bySubject[subject]?.takeIf { it.isActive }
        override fun findBySubject(subject: NumberRangeSubject) = listOfNotNull(bySubject[subject])
        override fun findById(id: Long) = bySubject.values.firstOrNull { it.id == id }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = bySubject.values.toList()
        override fun count(filterText: String?) = bySubject.size.toLong()
        override fun countInactive(filterText: String?) = bySubject.values.count { !it.isActive }.toLong()
        override fun save(range: NumberRange): NumberRange { bySubject[range.subject] = range; return range }
        override fun delete(id: Long) { bySubject.entries.removeIf { it.value.id == id } }
    }

    private class FakeCounters : NumberCounterRepository {
        private val state = mutableMapOf<Pair<String, String>, Long>()
        private fun key(scope: String, period: String) = scope to period
        override fun peekNext(range: NumberRange, scopeKey: String, periodKey: String): Long =
            state[key(scopeKey, periodKey)] ?: range.startValue
        override fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String): Long =
            allocate(range, scopeKey, periodKey)
        override fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String): Long =
            allocate(range, scopeKey, periodKey)
        private fun allocate(range: NumberRange, scopeKey: String, periodKey: String): Long {
            val next = state[key(scopeKey, periodKey)] ?: range.startValue
            state[key(scopeKey, periodKey)] = next + 1
            return next
        }
        override fun currentValue(subject: NumberRangeSubject): Long? = null
    }
}
