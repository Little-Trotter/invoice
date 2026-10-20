package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class MoneyTest {

    // ── Addition / Subtraktion ───────────────────────────────────────────────

    @Test
    fun `basic arithmetic and helpers`() {
        val a = Money.of("1.50")
        val b = Money.of("2.25")
        assertEquals(Money.of("3.75"), a + b)
        assertEquals(Money.of("-0.75"), a - b)
        assertFalse(Money.ZERO.isNegative())
    }

    @Test
    fun `addition and subtraction cross zero`() {
        assertEquals(Money.of("-2.00"), Money.of("3.00") - Money.of("5.00"))
        assertEquals(Money.of("0.00"), Money.of("-5.00") + Money.of("5.00"))
        assertTrue((Money.of("3.00") - Money.of("5.00")).isNegative())
    }

    @Test
    fun `setScale and toString`() {
        val m = Money.of("1.2345").setScale(2, RoundingMode.HALF_UP)
        assertEquals("1.23", m.toString())
    }

    @Test
    fun `of string and of BigDecimal`() {
        assertEquals(Money.of(BigDecimal("5.00")), Money.of("5.00"))
    }

    @Test
    fun `of rounds half up to two decimals at construction`() {
        assertEquals("1.01", Money.of("1.005").toString())
        assertEquals("1.00", Money.of("1.004").amount.toPlainString())
        assertEquals("33.33", Money.of("33.3333").toString())
        assertEquals(2, Money.of("1").amount.scale())
        assertEquals(BigDecimal("1.00"), Money.of("1").amount)
    }

    @Test
    fun `plusExact and minusExact preserve scale instead of rounding to two decimals`() {
        val a = Money.exact(BigDecimal("1.2345"))
        val b = Money.exact(BigDecimal("0.0001"))
        assertEquals(BigDecimal("1.2346"), a.plusExact(b).amount)
        assertEquals(BigDecimal("1.2344"), a.minusExact(b).amount)
        // + would have rounded this to 1.23 immediately, losing the sub-cent remainder.
        assertEquals(BigDecimal("1.23"), (a + b).amount)
    }

    // ── Multiplikation ───────────────────────────────────────────────────────

    @Test
    fun `times with integer factor`() {
        assertEquals(Money.of("30.00"), Money.of("10.00") * 3)
        assertEquals(Money.of("30.00"), Money.of("10.00") * BigDecimal("3"))
    }

    @Test
    fun `times with fractional factor`() {
        assertEquals(Money.of("25.00"), Money.of("10.00") * BigDecimal("2.5"))
        assertEquals(Money.of("7.50"), Money.of("10.00") * BigDecimal("0.75"))
    }

    @Test
    fun `times keeps task A rule three times 33 33 is 99 99`() {
        // The price is normalized on construction, so the multiply sees 33.33 — not 33.3333.
        // Rounding afterwards would have produced 100.00; that is exactly the bug this fixed.
        assertEquals(Money.of("99.99"), Money.of("33.3333") * 3)
    }

    @Test
    fun `times rounds half up at the cent boundary`() {
        // 0.005 * 1 -> the receiver is already normalized to 0.01 by of()
        assertEquals(Money.of("0.01"), Money.of("0.005") * 1)
        // 1.115 as a factor: 10.00 * 1.115 = 11.15 exactly, no rounding needed
        assertEquals(Money.of("11.15"), Money.of("10.00") * BigDecimal("1.115"))
        // genuine half-up case: 0.03 * 1.5 = 0.045 -> 0.05
        assertEquals(Money.of("0.05"), Money.of("0.03") * BigDecimal("1.5"))
    }

    @Test
    fun `times with zero and negative factors`() {
        assertEquals(Money.ZERO, Money.of("10.00") * 0)
        assertEquals(Money.of("-20.00"), Money.of("10.00") * -2)
        assertEquals(Money.of("-20.00"), Money.of("-10.00") * 2)
    }

    @Test
    fun `times with large factor does not overflow`() {
        assertEquals(Money.of("1000000000.00"), Money.of("1.00") * BigDecimal("1000000000"))
    }

    @Test
    fun `times with an explicit scale keeps intermediate precision`() {
        assertEquals(BigDecimal("3.3333"), (Money.of("10.00").times(BigDecimal("0.33333333"), 4, RoundingMode.HALF_UP)).amount)
        // default times() would have rounded this to 2 decimals instead.
        assertEquals(BigDecimal("3.33"), (Money.of("10.00") * BigDecimal("0.33333333")).amount)
    }

    @Test
    fun `times with a named scale but the default rounding mode still rounds HALF_UP`() {
        // Omitting the rounding argument exercises the Kotlin-generated times$default overload
        // (rounding: RoundingMode = ROUNDING) — a caller that only cares about the scale, not the
        // rounding mode, is expected to still get HALF_UP.
        assertEquals(BigDecimal("3.3333"), Money.of("10.00").times(BigDecimal("0.33333333"), 4).amount)
        assertEquals(BigDecimal("1.24"), Money.of("1.00").times(BigDecimal("1.235"), 2).amount)
    }

    // ── Division ─────────────────────────────────────────────────────────────

    @Test
    fun `div with exact result`() {
        assertEquals(Money.of("2.50"), Money.of("10.00") / 4)
        assertEquals(Money.of("2.50"), Money.of("10.00") / BigDecimal("4"))
    }

    @Test
    fun `div with non-terminating quotient does not throw`() {
        // BigDecimal.divide without a scale would raise ArithmeticException here.
        assertEquals(Money.of("3.33"), Money.of("10.00") / 3)
    }

    @Test
    fun `div by zero throws with a german message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { Money.of("10.00") / 0 }
        assertEquals("Division durch Null ist nicht erlaubt.", ex.message)
        assertThrows(IllegalArgumentException::class.java) { Money.of("10.00") / BigDecimal.ZERO }
    }

    @Test
    fun `div with negative divisor`() {
        assertEquals(Money.of("-5.00"), Money.of("10.00") / -2)
    }

    @Test
    fun `div by one returns the same amount`() {
        assertEquals(Money.of("7.77"), Money.of("7.77") / 1)
    }

    // ── Prozent ──────────────────────────────────────────────────────────────

    @Test
    fun `percentage computes vat from a net amount`() {
        assertEquals(Money.of("19.00"), Money.of("100.00").percentage(BigDecimal("19")))
        assertEquals(Money.of("7.00"), Money.of("100.00").percentage(BigDecimal("7")))
        assertEquals(Money.ZERO, Money.of("100.00").percentage(BigDecimal.ZERO))
    }

    @Test
    fun `percentage rounds half up`() {
        // 33.33 * 19 / 100 = 6.3327 -> 6.33
        assertEquals(Money.of("6.33"), Money.of("33.33").percentage(BigDecimal("19")))
        // 0.10 * 7 / 100 = 0.007 -> 0.01
        assertEquals(Money.of("0.01"), Money.of("0.10").percentage(BigDecimal("7")))
        // 0.10 * 4 / 100 = 0.004 -> 0.00
        assertEquals(Money.ZERO, Money.of("0.10").percentage(BigDecimal("4")))
    }

    // ── Vorzeichen ───────────────────────────────────────────────────────────

    @Test
    fun `negate and abs`() {
        assertEquals(Money.of("-5.00"), -Money.of("5.00"))
        assertEquals(Money.of("5.00"), -Money.of("-5.00"))
        assertEquals(Money.of("5.00"), Money.of("-5.00").abs())
        assertEquals(Money.of("5.00"), Money.of("5.00").abs())
    }

    @Test
    fun `sign predicates including negative zero`() {
        assertTrue(Money.ZERO.isZero())
        assertFalse(Money.ZERO.isPositive())
        assertFalse(Money.ZERO.isNegative())

        val negativeZero = Money(BigDecimal("-0.00"))
        assertTrue(negativeZero.isZero())
        assertFalse(negativeZero.isNegative())
        assertFalse(negativeZero.isPositive())

        assertTrue(Money.of("0.01").isPositive())
        assertTrue(Money.of("-0.01").isNegative())
        // IMP-5 — the wrong branch of isZero was untested: previously only confirmed that null is
        // null, never that an amount is not null.
        assertFalse(Money.of("0.01").isZero())
        assertFalse(Money.of("-0.01").isZero())
    }

    @Test
    fun `equality ignores scale`() {
        // The classic trap: BigDecimal("1.5") != BigDecimal("1.50"). Money must not inherit it.
        assertEquals(Money.exact(BigDecimal("1.5")), Money.of("1.50"))
        assertEquals(Money.exact(BigDecimal("1.5")).hashCode(), Money.of("1.50").hashCode())
        assertTrue(Money.exact(BigDecimal("1.5")).sameValueAs(Money.of("1.50")))
        assertEquals(Money.exact(BigDecimal("0.0000")), Money.ZERO)
    }

    /**
     * IMP-5 — `equals` starts with `this === other`, and this shortcut was untested. It is not
     * an end in itself: amounts land in sets and as map keys (tax grouping), and there an object
     * is first compared with itself.
     */
    @Test
    fun `equals erkennt Identitaet und weist Fremdtypen ab`() {
        val betrag = Money.of("19.00")
        // A typed null, not the literal `null`, so this genuinely exercises equals(Any?)'s own
        // null branch through a direct call (the point of this test) instead of the `==`
        // operator's compiler-special-cased, equals()-bypassing null check.
        val keinBetrag: Any? = null

        assertTrue(betrag.equals(betrag), "ein Betrag ist mit sich selbst gleich")
        assertFalse(betrag.equals(keinBetrag), "null ist kein Betrag")
        assertFalse(betrag.equals("19.00"), "eine Zeichenkette mit gleichem Text ist kein Betrag")
        assertEquals(1, setOf(betrag, Money.of("19.00")).size, "wertgleiche Betraege sind in einer Menge einer")
    }

    @Test
    fun `comparison and sorting`() {
        assertTrue(Money.of("1.00") < Money.of("2.00"))
        assertTrue(Money.of("-1.00") < Money.ZERO)
        assertEquals(0, Money.of("1.50").compareTo(Money.exact(BigDecimal("1.5"))))

        val sorted = listOf(Money.of("3.00"), Money.of("-1.00"), Money.of("0.50")).sorted()
        assertEquals(listOf(Money.of("-1.00"), Money.of("0.50"), Money.of("3.00")), sorted)
    }

    @Test
    fun `sum of empty list is zero`() {
        assertEquals(Money.ZERO, emptyList<Money>().sum())
    }

    @Test
    fun `sum over mixed signs`() {
        assertEquals(Money.of("4.00"), listOf(Money.of("5.00"), Money.of("-2.00"), Money.of("1.00")).sum())
        assertEquals(2, listOf(Money.of("0.10"), Money.of("0.20")).sum().amount.scale())
    }

    @Test
    fun `allocate splits evenly and preserves the total`() {
        val parts = Money.of("10.00").allocate(3)
        assertEquals(listOf(Money.of("3.34"), Money.of("3.33"), Money.of("3.33")), parts)
        assertEquals(Money.of("10.00"), parts.sum())
    }

    @Test
    fun `allocate by weights preserves the total`() {
        val parts = Money.of("100.00").allocate(listOf(BigDecimal("1"), BigDecimal("2"), BigDecimal("1")))
        assertEquals(Money.of("100.00"), parts.sum())
        assertEquals(listOf(Money.of("25.00"), Money.of("50.00"), Money.of("25.00")), parts)
    }

    @Test
    fun `allocate handles zero amount and single part`() {
        assertEquals(listOf(Money.ZERO, Money.ZERO), Money.ZERO.allocate(2))
        assertEquals(listOf(Money.of("7.77")), Money.of("7.77").allocate(1))
    }

    @Test
    fun `allocate with more parts than cents gives zero shares to the tail`() {
        val parts = Money.of("0.02").allocate(5)
        assertEquals(5, parts.size)
        assertEquals(Money.of("0.02"), parts.sum())
        assertEquals(listOf(Money.of("0.01"), Money.of("0.01"), Money.ZERO, Money.ZERO, Money.ZERO), parts)
    }

    @Test
    fun `allocate distributes a negative amount without losing cents`() {
        val parts = Money.of("-10.00").allocate(3)
        assertEquals(Money.of("-10.00"), parts.sum())
    }

    @Test
    fun `allocate rejects invalid input`() {
        assertThrows(IllegalArgumentException::class.java) { Money.of("1.00").allocate(0) }
        assertThrows(IllegalArgumentException::class.java) { Money.of("1.00").allocate(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            Money.of("1.00").allocate(listOf(BigDecimal("-1"), BigDecimal("2")))
        }
    }

    @Test
    fun `allocate with all-zero weights falls back to an even split`() {
        val parts = Money.of("9.00").allocate(listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals(listOf(Money.of("3.00"), Money.of("3.00"), Money.of("3.00")), parts)
    }

    @Test
    fun `allocate with unequal weights distributes correctly with remainder`() {
        // Test case: 10.00 split by weights 3, 2, 1 => 5.00, 3.33, 1.67
        val parts = Money.of("10.00").allocate(listOf(BigDecimal("3"), BigDecimal("2"), BigDecimal("1")))
        assertEquals(Money.of("10.00"), parts.sum())
        assertEquals(3, parts.size)
    }

    @Test
    fun `allocate with single weight returns single part`() {
        val parts = Money.of("42.73").allocate(listOf(BigDecimal("5")))
        assertEquals(listOf(Money.of("42.73")), parts)
        assertEquals(Money.of("42.73"), parts.sum())
    }

    @Test
    fun `allocate negative amount with uneven weights`() {
        val parts = Money.of("-15.00").allocate(listOf(BigDecimal("2"), BigDecimal("3"), BigDecimal("1")))
        assertEquals(Money.of("-15.00"), parts.sum())
        assertEquals(3, parts.size)
        assertTrue(parts[0].isNegative())
        assertTrue(parts[1].isNegative())
        assertTrue(parts[2].isNegative())
    }

    @Test
    fun `allocate by weight with fractional weights`() {
        val parts = Money.of("100.00").allocate(listOf(BigDecimal("1.5"), BigDecimal("2.5")))
        assertEquals(Money.of("100.00"), parts.sum())
        assertEquals(2, parts.size)
    }

    @Test
    fun `allocate with very small amounts`() {
        val parts = Money.of("0.03").allocate(3)
        assertEquals(Money.of("0.03"), parts.sum())
        assertEquals(3, parts.size)
        // At least one part should get a cent, others should be zero
        assertTrue(parts.any { !it.isZero() })
    }

    @Test
    fun `allocate by weights with very large amounts`() {
        val parts = Money.of("999999.99").allocate(listOf(BigDecimal("1"), BigDecimal("1"), BigDecimal("1")))
        assertEquals(Money.of("999999.99"), parts.sum())
        assertEquals(3, parts.size)
    }

    @Test
    fun `allocate with weights that sum to one`() {
        val parts = Money.of("100.00").allocate(listOf(BigDecimal("0.25"), BigDecimal("0.75")))
        assertEquals(Money.of("100.00"), parts.sum())
        assertEquals(2, parts.size)
    }

    @Test
    fun `toString keeps two decimals`() {
        // Previously stripTrailingZeros() turned these into "0", "1.5" and "100".
        assertEquals("0.00", Money.ZERO.toString())
        assertEquals("1.50", Money.of("1.50").toString())
        assertEquals("100.00", Money.of("100.00").toString())
        assertEquals("-2.50", Money.of("-2.50").toString())
    }

    @Test
    fun `exact keeps precision while of normalizes`() {
        assertEquals(4, Money.exact(BigDecimal("1.2345")).amount.scale())
        assertEquals(2, Money.of("1.2345").amount.scale())
        assertEquals(BigDecimal("1.2345"), Money.exact(BigDecimal("1.2345")).amount)
    }

    @Test
    fun `withScale states the intent explicitly`() {
        assertEquals(BigDecimal("1.2345"), Money.exact(BigDecimal("1.23450")).withScale(4).amount)
        assertEquals(BigDecimal("1.23"), Money.exact(BigDecimal("1.2345")).withScale(2).amount)
    }
}
