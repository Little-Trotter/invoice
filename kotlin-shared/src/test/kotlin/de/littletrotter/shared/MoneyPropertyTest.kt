package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

class MoneyPropertyTest {

    @Test
    fun `money arithmetic and scaling invariants`() {
        val rnd = Random(123)
        repeat(1000) {
            val a = BigDecimal((rnd.nextDouble() * 1_000_000.0) - 500_000.0).setScale(rnd.nextInt(0, 5), RoundingMode.HALF_UP)
            val b = BigDecimal((rnd.nextDouble() * 1_000_000.0) - 500_000.0).setScale(rnd.nextInt(0, 5), RoundingMode.HALF_UP)

            val ma = Money(a)
            val mb = Money(b)

            // minus behaves as expected (Money.minus normalizes to 2 decimals)
            assertEquals(ma.minus(mb).amount, Money.of(a.subtract(b)).amount)

            // setScale produces equivalent BigDecimal scale
            val s2 = ma.setScale(2, RoundingMode.HALF_UP)
            assertEquals(2, s2.amount.scale())

            val s4 = mb.setScale(4, RoundingMode.HALF_UP)
            assertEquals(4, s4.amount.scale())

            // isNegative consistent with BigDecimal sign
            assertEquals(a < BigDecimal.ZERO, ma.isNegative())
            assertEquals(b < BigDecimal.ZERO, mb.isNegative())
        }
    }

    @Test
    fun `addition is commutative and subtraction is antisymmetric`() {
        val rnd = Random(4711)
        repeat(1000) {
            val a = randomMoney(rnd)
            val b = randomMoney(rnd)

            assertEquals(a + b, b + a)
            assertEquals(a - b, -(b - a))
            assertEquals(a, a + Money.ZERO)
        }
    }

    @Test
    fun `multiplication by a whole number equals repeated addition`() {
        // Only holds for integer factors. For fractional factors rounded arithmetic is NOT
        // associative, so the equivalent claim would be false — see the tolerance test below.
        val rnd = Random(99)
        repeat(500) {
            val a = randomMoney(rnd)
            val n = rnd.nextInt(0, 12)

            var repeated = Money.ZERO
            repeat(n) { repeated += a }

            assertEquals(repeated, a * n)
        }
    }

    @Test
    fun `multiply-then-divide drifts only within the rounding error the two steps can produce`() {
        // Equality would be wrong to assert: both steps round to cents. The reachable bound is
        // not "one cent" either — the multiply is off by at most half a cent, and dividing by a
        // factor smaller than 1 scales that error up by 1/factor before the division adds its own
        // half cent. A test asserting 0.01 fails for e.g. 9135.62 with factor 0.2 (drift 0.02).
        val rnd = Random(2024)
        val halfCent = BigDecimal("0.005")
        repeat(1000) {
            val a = randomMoney(rnd)
            val factor = BigDecimal(rnd.nextInt(1, 100)).divide(BigDecimal("10"), 1, RoundingMode.HALF_UP)

            val roundTrip = (a * factor) / factor
            val delta = roundTrip.amount.subtract(a.amount).abs()
            val tolerance = halfCent.divide(factor, 10, RoundingMode.HALF_UP).add(halfCent)

            assertTrue(
                delta <= tolerance,
                "round trip drifted by $delta (tolerance $tolerance) for amount ${a.amount} and factor $factor",
            )
        }
    }

    @Test
    fun `allocate always preserves the total exactly`() {
        val rnd = Random(31337)
        repeat(1000) {
            val a = randomMoney(rnd)
            val parts = rnd.nextInt(1, 9)

            val even = a.allocate(parts)
            assertEquals(parts, even.size)
            assertEquals(a, even.sum(), "even split lost cents for ${a.amount} over $parts parts")

            val weights = List(parts) { BigDecimal(rnd.nextInt(0, 50)) }
            val weighted = a.allocate(weights)
            assertEquals(parts, weighted.size)
            assertEquals(a, weighted.sum(), "weighted split lost cents for ${a.amount} with $weights")
            weighted.forEach { assertEquals(2, it.amount.scale()) }
        }
    }

    @Test
    fun `every operation returns currency scale`() {
        val rnd = Random(7)
        repeat(500) {
            val a = randomMoney(rnd)
            val b = randomMoney(rnd)
            val factor = BigDecimal(rnd.nextInt(1, 20))

            listOf(
                a + b, a - b, a * factor, a / factor, -a, a.abs(),
                a.percentage(BigDecimal("19")), listOf(a, b).sum(),
            ).forEach { assertEquals(2, it.amount.scale()) }
        }
    }

    @Test
    fun `comparison is consistent with BigDecimal and equals implies same hashCode`() {
        val rnd = Random(555)
        repeat(1000) {
            val a = randomMoney(rnd)
            val b = randomMoney(rnd)

            assertEquals(a.amount.compareTo(b.amount), a.compareTo(b))
            assertEquals(a.sameValueAs(b), a == b)
            if (a == b) assertEquals(a.hashCode(), b.hashCode())

            // scale must not influence equality or the hash
            val rescaled = Money(a.amount.setScale(6, RoundingMode.HALF_UP))
            assertEquals(a, rescaled)
            assertEquals(a.hashCode(), rescaled.hashCode())
        }
    }

    @Test
    fun `multiplying by zero yields zero and dividing by one is the identity`() {
        val rnd = Random(13)
        repeat(500) {
            val a = randomMoney(rnd)
            assertEquals(Money.ZERO, a * 0)
            assertEquals(a, a / 1)
        }
    }

    private fun randomMoney(rnd: Random): Money =
        Money.of(BigDecimal((rnd.nextDouble() * 20_000.0) - 10_000.0).setScale(2, RoundingMode.HALF_UP))
}
