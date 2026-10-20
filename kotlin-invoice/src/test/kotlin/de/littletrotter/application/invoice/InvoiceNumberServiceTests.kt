package de.littletrotter.application.invoice

import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.application.reference.RecordingNumberJournalRepository
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoiceNumberServiceTests {

    // Mirrors InvoiceLifecycleServiceTest.invoiceGaplessRange(): GAPLESS/YEAR_MONTH_DAY, prefix
    // from the SenderProfile, against the real NumberRangeService.
    private fun invoiceRange(startValue: Long): NumberRange = NumberRange().edit {
        code = "INVOICE_GAPLESS"
        displayName = "Rechnungsnummer"
        subject = NumberRangeSubject.INVOICE
        this.startValue = startValue
        prefixSource = PrefixSource.SELLER_PROFILE
        datePart = DatePart.YEAR_MONTH_DAY
        counterWidth = 5
        resetCycle = ResetCycle.YEARLY
        scope = CounterScope.GLOBAL
        continuity = Continuity.GAPLESS
        isActive = true
        isDefault = true
    }

    private class FixedRangeRepository(private val active: NumberRange) : NumberRangeRepository {
        override fun findActive(subject: NumberRangeSubject) = active.takeIf { it.subject == subject && it.isActive }
        override fun findBySubject(subject: NumberRangeSubject) = listOf(active).filter { it.subject == subject }
        override fun findById(id: Long) = active.takeIf { it.id == id }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = listOf(active)
        override fun count(filterText: String?) = 1L
        override fun countInactive(filterText: String?) = if (active.isActive) 0L else 1L
        override fun save(range: NumberRange) = range
        override fun delete(id: Long) {}
    }

    private class InMemoryNumberCounterRepository : NumberCounterRepository {
        private val counters = mutableMapOf<String, Long>()
        private fun key(range: NumberRange, scopeKey: String, periodKey: String) = "${range.id}/$scopeKey/$periodKey"
        override fun peekNext(range: NumberRange, scopeKey: String, periodKey: String): Long =
            counters[key(range, scopeKey, periodKey)]?.let { it + 1 } ?: range.startValue
        private fun allocate(range: NumberRange, scopeKey: String, periodKey: String): Long {
            val next = peekNext(range, scopeKey, periodKey)
            counters[key(range, scopeKey, periodKey)] = next
            return next
        }
        override fun allocateUniqueOnly(range: NumberRange, scopeKey: String, periodKey: String) = allocate(range, scopeKey, periodKey)
        override fun allocateGapless(range: NumberRange, scopeKey: String, periodKey: String) = allocate(range, scopeKey, periodKey)
        override fun currentValue(subject: NumberRangeSubject): Long? = null
    }

    @Test
    fun `generate next number formatting`() {
        val sender = aSenderProfile { id = 123L; invoicePrefix = "ACME" }
        val service = InvoiceNumberService(
            NumberRangeService(
                FixedRangeRepository(invoiceRange(startValue = 42L)), InMemoryNumberCounterRepository(), RecordingNumberJournalRepository(),
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
            ),
        )

        val today = LocalDate.of(2026, 6, 15)
        val number = service.generateNextNumber(sender, today)
        val year = today.year.toString()
        val month = today.monthValue.toString().padStart(2, '0')
        assertTrue(number.value.startsWith("ACME-$year-$month-"))
        assertTrue(number.value.endsWith("00042"))
    }
}

// The real concurrency guarantee of number allocation is tested where it actually happens --
// against real PostgreSQL: `NumberCounterIT` (N1-18..21, gapless sequence under load),
// `MasterDataNumberCollisionIT` (N3-10, two concurrent creations), and
// `NumberJournalReportServiceIT` (sealing in parallel with ongoing allocation).
