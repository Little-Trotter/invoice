package de.littletrotter.shared

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * [Money]'s intermediate-precision accumulation and largest-remainder distribution, split
 * out of the `Money` class itself (`TooManyFunctions`, `thresholdInClasses: 20`): a distinct
 * responsibility from the class's core normalized-arithmetic value-object contract, so it lives as
 * `Money` extension functions in this file rather than as members. Call sites are unchanged
 * (`amount.allocate(3)`, `net.plusExact(vat)`) — same package, no delegation facade.
 */

/** Extra decimals kept while distributing [Money.allocate]'s exact shares, before rounding to whole cents. */
private const val ALLOCATION_INTERMEDIATE_SCALE = 10

/**
 * Addition/subtraction that stays in the [Money.exact] precision world instead of normalizing to
 * [Money.SCALE] (unlike [Money.plus]/[Money.minus]). For running totals that must round only once
 * at the end — a per-rate tax breakdown — using `+`/`-` here would silently round away the
 * sub-cent precision on every step, one accumulation at a time.
 */
fun Money.plusExact(other: Money): Money = Money(amount.add(other.amount))

fun Money.minusExact(other: Money): Money = Money(amount.subtract(other.amount))

/**
 * Multiplication that rounds to a named scale instead of always [Money.SCALE] — the multiplication
 * counterpart to [plusExact]/[minusExact], for the same intermediate-precision accumulators
 * (a tax breakdown splits amounts proportionally at scale 4, then rounds once for display).
 */
fun Money.times(factor: BigDecimal, scale: Int, rounding: RoundingMode = Money.ROUNDING): Money =
    Money(amount.multiply(factor).setScale(scale, rounding))

/**
 * Splits this amount into [parts] pieces whose sum is exactly this amount. Remainder cents go
 * to the leading pieces (largest-remainder), so 10,00 into 3 gives 3,34 / 3,33 / 3,33.
 */
fun Money.allocate(parts: Int): List<Money> {
    require(parts > 0) { "Anzahl der Teile muss groesser als 0 sein." }
    return allocate(List(parts) { BigDecimal.ONE })
}

/**
 * Splits this amount proportionally to [weights], preserving the total exactly: every piece is
 * rounded down first, then the leftover cents are handed out one by one to the pieces with the
 * largest remainder. This is the same invariant a per-rate tax breakdown has to maintain for its
 * running totals (accumulate at scale 4, round once for display).
 *
 * Negative weights are rejected; an all-zero weight vector falls back to an even split.
 */
fun Money.allocate(weights: List<BigDecimal>): List<Money> {
    require(weights.isNotEmpty()) { "Gewichtungsliste darf nicht leer sein." }
    require(weights.none { it.signum() < 0 }) { "Gewichte duerfen nicht negativ sein." }

    val totalWeight = weights.fold(BigDecimal.ZERO) { acc, w -> acc.add(w) }
    if (totalWeight.signum() == 0) return allocate(weights.size)

    // Work in minor units (cents) so the remainder handout is exact integer arithmetic.
    val totalCents = amount.setScale(Money.SCALE, Money.ROUNDING).movePointRight(Money.SCALE).toBigIntegerExact()

    val exactShares = weights.map { w ->
        BigDecimal(totalCents).multiply(w).divide(totalWeight, ALLOCATION_INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
    }
    val floors = exactShares.map { it.setScale(0, RoundingMode.FLOOR).toBigInteger() }
    var remaining = totalCents.subtract(floors.fold(BigInteger.ZERO) { a, b -> a.add(b) })

    // Hand out the leftover cents by descending fractional part; ties keep list order.
    val order = exactShares.indices.sortedWith(
        compareByDescending<Int> { exactShares[it].subtract(BigDecimal(floors[it])) }.thenBy { it }
    )
    val cents = floors.toMutableList()
    var i = 0
    // sum(floor(share_i)) <= total for any sign, so `remaining` is never negative here.
    while (remaining.signum() > 0) {
        val idx = order[i % order.size]
        cents[idx] = cents[idx].add(BigInteger.ONE)
        remaining = remaining.subtract(BigInteger.ONE)
        i++
    }

    return cents.map { Money(BigDecimal(it).movePointLeft(Money.SCALE).setScale(Money.SCALE, Money.ROUNDING)) }
}
