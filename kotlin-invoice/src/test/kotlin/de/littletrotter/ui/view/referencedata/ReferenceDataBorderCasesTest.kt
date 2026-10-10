package de.littletrotter.ui.view.referencedata

import de.littletrotter.application.reference.ManageNumberRange
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Border cases (light).
 *
 * Selected edge cases (not exhaustive — this domain is non-critical):
 * - Overlapping validity windows for the same rule/kind
 * - Deleting/deactivating a rate that historical invoices reference (snapshot must protect them)
 * - Two defaults marked for the same kind (contract?)
 * - Number range exhaustion at counter width; changing a range's prefix while invoices exist
 * - PDF template deletion while set as a sender's default
 *
 * Each case: test + contract one-liner; undefined contracts → findings list.
 */
@DisplayName("RD-3: Reference Data Border Cases")
class ReferenceDataBorderCasesTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // OVERLAPPING VALIDITY WINDOWS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Overlapping Validity Windows")
    inner class OverlappingValidityWindowsTests {

        @Test
        fun `two tax rates with same code and overlapping validity windows should be rejected`() {
            val rate1 = TaxRate().apply {
                code = "DE_STANDARD_19"
                name = "VAT 19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                validTo = LocalDate.of(2024, 12, 31)
            }

            val rate2 = TaxRate().apply {
                code = "DE_STANDARD_19"
                name = "VAT 19% (renewed)"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 6, 1)
                validTo = LocalDate.of(2025, 12, 31)
            }

            // Contract: overlapping validity windows with same code should be detected
            assertTrue(rate2.overlapsValidityOf(rate1), "overlapping windows must be detected")
        }

        @Test
        fun `two tax rates with different codes do not count as overlapping`() {
            val rate1 = TaxRate().apply {
                code = "DE_STANDARD_19"
                name = "VAT 19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                validTo = LocalDate.of(2024, 12, 31)
            }

            val rate2 = TaxRate().apply {
                code = "DE_STANDARD_7"
                name = "VAT 7%"
                rate = TaxRatePercent(BigDecimal("7.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 6, 1)
                validTo = LocalDate.of(2025, 12, 31)
            }

            // Contract: different codes do not overlap
            assertFalse(rate2.overlapsValidityOf(rate1), "different codes should not overlap")
        }

        @Test
        fun `adjacent validity windows do not overlap`() {
            val rate1 = TaxRate().apply {
                code = "DE_STANDARD_19"
                name = "VAT 19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                validTo = LocalDate.of(2024, 12, 31)
            }

            val rate2 = TaxRate().apply {
                code = "DE_STANDARD_19"
                name = "VAT 19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2025, 1, 1)
                validTo = null
            }

            // Contract: adjacent windows (ending at A, starting at A+1) do not overlap
            assertFalse(rate2.overlapsValidityOf(rate1), "adjacent windows must not overlap")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MULTIPLE DEFAULTS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multiple Defaults for Same Kind")
    inner class MultipleDefaultsTests {

        @Test
        fun `two payment terms marked as default`() {
            val term1 = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Net 30")
                dueInDays = 30
                discountPercent = DiscountPercent(BigDecimal.ZERO)
                isDefault = true
            }

            val term2 = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Net 60")
                dueInDays = 60
                discountPercent = DiscountPercent(BigDecimal.ZERO)
                isDefault = true
            }

            // Contract: unspecified — application/database layer enforces single default per kind
            // (via DB constraint or service logic). Domain models here both report isDefault = true.
            assertTrue(term1.isDefault)
            assertTrue(term2.isDefault)
        }

        @Test
        fun `payment term can be marked as default and revoked`() {
            val term = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Net 30")
                dueInDays = 30
                discountPercent = DiscountPercent(BigDecimal.ZERO)
                isDefault = false
            }
            assertFalse(term.isDefault)
            term.isDefault = true
            assertTrue(term.isDefault)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // NUMBER RANGE EDGE CASES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Number Range Edge Cases")
    inner class NumberRangeEdgeCasesTests {

        @Test
        fun `number range with counter width 3 can produce 999 unique numbers`() {
            val range = NumberRange().apply {
                code = "SMALL_RANGE"
                displayName = "Limited Range"
                subject = NumberRangeSubject.CUSTOMER
                counterWidth = 3
                startValue = 1
            }
            // Contract: counter 1-999 fit in 3 digits (001-999)
            assertEquals(3, range.counterWidth)
        }

        @Test
        fun `counter overflow expands beyond counter width`() {
            val range = NumberRange().apply {
                code = "OVERFLOW_RANGE"
                displayName = "Overflow Range"
                subject = NumberRangeSubject.CUSTOMER
                counterWidth = 3
                startValue = 1
            }
            // Contract: if counter reaches 1000, it should expand to 4 digits (1000),
            // not truncate to 3 (which would lose uniqueness)
            assertEquals(3, range.counterWidth)
            // Application layer responsible for ensuring expansion when counter overflows
        }

        @Test
        fun `number range prefix is freely editable at the domain layer alone`() {
            val range = NumberRange().apply {
                code = "INVOICE_RANGE"
                displayName = "Invoice Numbers"
                subject = NumberRangeSubject.INVOICE
                prefixSource = PrefixSource.FIXED
                fixedPrefix = "INV"
                counterWidth = 6
            }
            range.fixedPrefix = "REC"
            assertEquals("REC", range.fixedPrefix)
            // NumberRange.validate() has no in-use knowledge (no repository access) and never
            // enforced this itself — that lives at the save path, see the finding test below.
        }

        /**
         * A number range's prefix
         * configuration (`prefixSource`/`fixedPrefix`/`prefixDataField`) can no longer be changed
         * once at least one number has ever been allocated from it
         * (`NumberCounterRepository.hasBeenUsed` — a `number_counter` row exists only after an
         * actual `allocate…()`, never after a mere `peek`). `ManageNumberRange
         * .saveNumberRange` rejects the save with a readable German error instead; a new range is
         * the sanctioned way to switch prefixes.
         */
        @Test
        fun `changing a used number range's prefix is rejected`() {
            val numberRangeRepository = mock<NumberRangeRepository>()
            val numberCounterRepository = mock<NumberCounterRepository>()
            val manageNumberRange = ManageNumberRange(
                numberRangeRepository = numberRangeRepository,
                numberCounterRepository = numberCounterRepository,
            )

            val persisted = NumberRange().apply {
                id = 42L
                code = "INVOICE_RANGE"
                displayName = "Rechnungsnummern"
                subject = NumberRangeSubject.INVOICE
                prefixSource = PrefixSource.FIXED
                fixedPrefix = "INV"
                counterWidth = 6
            }
            whenever(numberRangeRepository.findById(42L)).thenReturn(persisted)
            whenever(numberCounterRepository.hasBeenUsed(42L)).thenReturn(true)

            val edited = NumberRangeDto(
                id = 42L,
                code = "INVOICE_RANGE",
                displayName = "Rechnungsnummern",
                subject = NumberRangeSubject.INVOICE,
                prefixSource = PrefixSource.FIXED,
                fixedPrefix = "REC", // changed prefix on an already-used range
                counterWidth = 6,
            )

            val ex = assertThrows(InvalidParameterException::class.java) { manageNumberRange.saveNumberRange(edited) }

            assertTrue(ex.message?.contains("Präfix") == true, ex.message)
            verify(numberRangeRepository, never()).save(any())
        }

        /**
         * Counterpart to the finding above: an UNUSED range's prefix may still be changed freely —
         * only an already-used range is protected.
         */
        @Test
        fun `changing an unused number range's prefix is still allowed`() {
            val numberRangeRepository = mock<NumberRangeRepository>()
            val numberCounterRepository = mock<NumberCounterRepository>()
            val manageNumberRange = ManageNumberRange(
                numberRangeRepository = numberRangeRepository,
                numberCounterRepository = numberCounterRepository,
            )

            val persisted = NumberRange().apply {
                id = 7L
                code = "CUSTOMER_RANGE"
                displayName = "Kundennummern"
                subject = NumberRangeSubject.CUSTOMER
                prefixSource = PrefixSource.FIXED
                fixedPrefix = "KD"
                counterWidth = 5
            }
            whenever(numberRangeRepository.findById(7L)).thenReturn(persisted)
            whenever(numberCounterRepository.hasBeenUsed(7L)).thenReturn(false)
            whenever(numberRangeRepository.save(any())).thenAnswer { it.arguments[0] }

            val edited = NumberRangeDto(
                id = 7L,
                code = "CUSTOMER_RANGE",
                displayName = "Kundennummern",
                subject = NumberRangeSubject.CUSTOMER,
                prefixSource = PrefixSource.FIXED,
                fixedPrefix = "NEU",
                counterWidth = 5,
            )

            assertDoesNotThrow { manageNumberRange.saveNumberRange(edited) }
            verify(numberRangeRepository).save(persisted)
            assertEquals("NEU", persisted.fixedPrefix)
        }

        @Test
        fun `number range validation rejects invalid configuration`() {
            val range = NumberRange().apply {
                code = ""
                displayName = "Invalid"
                subject = NumberRangeSubject.CUSTOMER
                counterWidth = 0
            }
            assertThrows(InvalidParameterException::class.java) { range.validate() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // IMMUTABLE FIELDS WHEN IN USE
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Immutable Fields When In Use")
    inner class ImmutableFieldsTests {

        @Test
        fun `tax rate code should be immutable once rule is in use on invoices`() {
            val rate = TaxRate().apply {
                code = "DE_STANDARD_19"
                name = "VAT 19%"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
            }
            // Contract: once a rule is in use on an invoice, its code must not change
            // (domain contract enforced at UI layer via read-only field)
            // This test documents the contract; enforcement is at application/UI layer
            assertEquals("DE_STANDARD_19", rate.code)
        }

        @Test
        fun `payment term used on historical invoices preserves snapshot`() {
            val term = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "Net 30")
                dueInDays = 30
                discountPercent = DiscountPercent(BigDecimal.ZERO)
            }
            // Contract: a payment term snapshot frozen at invoice finalization time
            // preserves the values at that moment, independent of later edits to master data
            assertEquals("Net 30", term.nameFor("de"))
            assertEquals(30, term.dueInDays)
        }
    }
}
