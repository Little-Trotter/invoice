package de.littletrotter.domain.reference

import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.ResetCycle

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** N1 — [NumberRange.validate], test case N1-08 and the basic checks alongside it. */
class NumberRangeValidationTest {

    private fun validRange() = NumberRange().apply {
        code = "TEST"
        displayName = "Test-Kreis"
        startValue = 1
        counterWidth = 5
    }

    @Test
    fun `a minimal valid range passes`() {
        assertDoesNotThrow { validRange().validate() }
    }

    @Test
    fun `blank code is rejected`() {
        assertThrows(InvalidParameterException::class.java) { validRange().apply { code = "" }.validate() }
    }

    @Test
    fun `blank display name is rejected`() {
        assertThrows(InvalidParameterException::class.java) { validRange().apply { displayName = " " }.validate() }
    }

    @Test
    fun `negative start value is rejected`() {
        assertThrows(InvalidParameterException::class.java) { validRange().apply { startValue = -1 }.validate() }
    }

    @Test
    fun `zero start value is allowed`() {
        assertDoesNotThrow { validRange().apply { startValue = 0 }.validate() }
    }

    @Test
    fun `counter width below 1 is rejected`() {
        assertThrows(InvalidParameterException::class.java) { validRange().apply { counterWidth = 0 }.validate() }
    }

    @Test
    fun `FIXED prefix source requires a non-blank fixedPrefix`() {
        assertThrows(InvalidParameterException::class.java) {
            validRange().apply { prefixSource = PrefixSource.FIXED; fixedPrefix = null }.validate()
        }
    }

    @Test
    fun `FROM_DATA prefix source requires a chosen field`() {
        assertThrows(InvalidParameterException::class.java) {
            validRange().apply { prefixSource = PrefixSource.FROM_DATA; prefixDataField = null }.validate()
        }
    }

    // N1-08 — the core of this check: without a date part, the same number would be allocated
    // a second time the following year, violating uniqueness (§14 UStG).
    @Test
    fun `N1-08 - YEARLY reset without any date part is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            validRange().apply { resetCycle = ResetCycle.YEARLY; datePart = DatePart.NONE }.validate()
        }
    }

    @Test
    fun `N1-08 - YEARLY reset with a year part is accepted`() {
        assertDoesNotThrow {
            validRange().apply { resetCycle = ResetCycle.YEARLY; datePart = DatePart.YEAR }.validate()
        }
    }

    @Test
    fun `NEVER reset never requires a date part`() {
        assertDoesNotThrow {
            validRange().apply { resetCycle = ResetCycle.NEVER; datePart = DatePart.NONE }.validate()
        }
    }

    // ── prefixSource / fixedPrefix / prefixDataField must agree with each other ──

    @Test
    fun `FIXED prefix source with a blank, non-null fixedPrefix is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            validRange().apply { prefixSource = PrefixSource.FIXED; fixedPrefix = "   " }.validate()
        }
    }

    @Test
    fun `FIXED prefix source with a real fixedPrefix is accepted`() {
        assertDoesNotThrow {
            validRange().apply { prefixSource = PrefixSource.FIXED; fixedPrefix = "INV" }.validate()
        }
    }

    @Test
    fun `a fixedPrefix set while prefixSource is not FIXED is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            validRange().apply { prefixSource = PrefixSource.NONE; fixedPrefix = "INV" }.validate()
        }
    }

    @Test
    fun `a prefixDataField set while prefixSource is not FROM_DATA is rejected`() {
        assertThrows(InvalidParameterException::class.java) {
            validRange().apply { prefixSource = PrefixSource.NONE; prefixDataField = PrefixDataField.NAME_INITIALS }.validate()
        }
    }

    private fun quoteRange() = validRange().apply {
        subject = NumberRangeSubject.QUOTE
        prefixSource = PrefixSource.FIXED
        fixedPrefix = "AN"
        scope = CounterScope.GLOBAL
        continuity = Continuity.GAPLESS
    }

    @Test
    fun `a QUOTE range with fixed prefix, GLOBAL scope and GAPLESS passes`() {
        assertDoesNotThrow { quoteRange().validate() }
    }

    @Test
    fun `a QUOTE range without prefix passes`() {
        assertDoesNotThrow { quoteRange().apply { prefixSource = PrefixSource.NONE; fixedPrefix = null }.validate() }
    }

    @Test
    fun `a QUOTE range rejects prefix sources other than NONE and FIXED`() {
        assertThrows(InvalidParameterException::class.java) {
            quoteRange().apply { prefixSource = PrefixSource.SELLER_PROFILE; fixedPrefix = null }.validate()
        }
        assertThrows(InvalidParameterException::class.java) {
            quoteRange().apply {
                prefixSource = PrefixSource.FROM_DATA
                fixedPrefix = null
                prefixDataField = PrefixDataField.NAME_INITIALS
            }.validate()
        }
    }

    @Test
    fun `a QUOTE range rejects PER_SELLER scope`() {
        assertThrows(InvalidParameterException::class.java) { quoteRange().apply { scope = CounterScope.PER_SELLER }.validate() }
    }

    @Test
    fun `a QUOTE range rejects UNIQUE_ONLY continuity`() {
        assertThrows(InvalidParameterException::class.java) { quoteRange().apply { continuity = Continuity.UNIQUE_ONLY }.validate() }
    }
}
