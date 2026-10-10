/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 */

package de.littletrotter.domain.service

import de.littletrotter.application.invoice.InvoiceNumberService
import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.application.reference.RecordingNumberJournalRepository
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Wired against a real [NumberRangeService] with in-memory fakes, matching the production
 * path. The number range below mirrors `invoiceGaplessRange()` (`InvoiceLifecycleServiceTest`)
 * -- GAPLESS/YEAR_MONTH_DAY, prefix from the SenderProfile -- so the expected format matches
 * production instead of the old, per-seller-monthly legacy format.
 */
class InvoiceNumberServiceTest {

    private fun invoiceRange(startValue: Long): NumberRange = NumberRange().apply {
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

    /** Mirrors `last_value`, like `InMemoryNumberCounterRepository` in `InvoiceLifecycleServiceTest`. */
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

    private fun numberService(startValue: Long): InvoiceNumberService = InvoiceNumberService(
        NumberRangeService(
            FixedRangeRepository(invoiceRange(startValue)), InMemoryNumberCounterRepository(), RecordingNumberJournalRepository(),
            de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        ),
    )

    @Test
    fun `generateNextNumber formats number correctly`() {
        // Arrange
        val today = LocalDate.of(2026, 6, 15)
        val service = numberService(startValue = 42L)
        val sender = SenderProfile().apply {
            id = 1L
            invoicePrefix = "RE"
        }

        // Act
        val result = service.generateNextNumber(sender, today)

        // Assert
        val expected = "RE-%04d-%02d-%02d-00042".format(today.year, today.monthValue, today.dayOfMonth)
        assertEquals(expected, result.value)
    }

    @Test
    fun `generateNextNumber handles hyphen in prefix`() {
        // Arrange
        val today = LocalDate.of(2026, 6, 15)
        val service = numberService(startValue = 1L)
        val sender = SenderProfile().apply {
            id = 1L
            invoicePrefix = "ABC-"
        }

        // Act
        val result = service.generateNextNumber(sender, today)

        // Assert
        val expected = "ABC-%04d-%02d-%02d-00001".format(today.year, today.monthValue, today.dayOfMonth)
        assertEquals(expected, result.value)
    }

    @Test
    fun `generateNextNumber throws when sender id is missing`() {
        // Arrange — the missing-id check happens before the number range is ever consulted, so
        // no active range needs to be wired for this case.
        val service = numberService(startValue = 1L)
        val sender = SenderProfile().apply {
            invoicePrefix = "RE"
        }

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            service.generateNextNumber(sender, LocalDate.of(2026, 6, 15))
        }
    }
}
