package de.littletrotter.shared

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Value Object representing a monetary amount in EUR.
 *
 * All arithmetic lives here. The rounding rule — HALF_UP at
 * [Money.SCALE] decimals — is applied by every operator, so no caller has to repeat it and no caller
 * can quietly pick a different one. If the result of a calculation is a monetary amount, it is
 * produced by a method on this class or by a `Money` extension function in this package (see
 * `MoneyDistribution.kt` for the intermediate-precision/allocation family); `BigDecimal` stays
 * reserved for quantities, percentages and weighting factors, which enter as *arguments* here.
 *
 * Two precision worlds exist on purpose:
 * - [Money.of] and every operator normalize to [Money.SCALE] decimals. This is what almost all code wants.
 * - [Money.exact], [Money.withScale] and the `MoneyDistribution.kt` extensions keep more decimals
 *   for intermediate accumulation. A caller that sums many line amounts before showing one total
 *   works at scale 4 and rounds once at the end; forcing every intermediate step through the
 *   normalizing path reintroduces exactly the rounding errors this type exists to prevent.
 *
 * Lives in `kotlin-shared` (not `kotlin-invoice`), precisely so both `kotlin-invoice` and
 * `kotlin-xpdf` share this one rounding rule: `kotlin-xpdf` depends on `kotlin-shared` but not
 * on `kotlin-invoice`, so before this move `Money` was physically unreachable from the export
 * module and a raw `BigDecimal` crept in there instead. `MoneyConverter` (kept in `kotlin-invoice` because it needs
 * `jakarta.persistence`, which `kotlin-shared` must not gain) is the only thing that stayed
 * behind. Each module also carries an ArchUnit test asserting no money-named field, constructor
 * parameter or method return type is a raw `BigDecimal` — see `MoneyArchitectureTest` in
 * `kotlin-shared`, `kotlin-xpdf` and `kotlin-invoice` — so this rule is enforced, not just
 * documented.
 */
/**
 * `@JsonCreator`/`@JsonProperty` required: Jackson deserializes this only with
 * `jackson-module-kotlin`, but serialization (via getter) succeeds without it. The error only
 * appears on **reading back**, which disabled the reference data cache for `PerDiemRate`.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)`: Jackson introspection treats `isXxx()` methods
 * as bean properties. Money travels on `InvoiceDto`'s wire **byte-identical** to plain
 * `{"amount": ...}`. Methods [isNegative], [isPositive], [isZero] carry `@JsonIgnore` individually;
 * `ignoreUnknown = true` catches any future derived field that someone forgets to mark.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Money @JsonCreator constructor(@param:JsonProperty("amount") val amount: BigDecimal) : Comparable<Money> {

    // ── Sign and comparison ───────────────────────────────────────────────────

    @JsonIgnore
    fun isNegative(): Boolean = amount.signum() < 0

    @JsonIgnore
    fun isPositive(): Boolean = amount.signum() > 0

    @JsonIgnore
    fun isZero(): Boolean = amount.signum() == 0

    fun sameValueAs(other: Money): Boolean = amount.compareTo(other.amount) == 0

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    /**
     * Equality is by *value*, not by scale. The generated data-class equality would fall
     * through to [BigDecimal.equals], which treats 1.50 and 1.5 as different — a trap, because
     * [exact] values carry a different scale than [of] values while meaning the same amount.
     * [hashCode] strips trailing zeros to keep the equals/hashCode contract intact.
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Money && amount.compareTo(other.amount) == 0)

    override fun hashCode(): Int = amount.stripTrailingZeros().hashCode()

    // ── Scale ────────────────────────────────────────────────────────────────

    fun setScale(scale: Int, roundingMode: RoundingMode): Money =
        Money(amount.setScale(scale, roundingMode))

    /** Reads as intent: "keep this many decimals on purpose". See the class KDoc. */
    fun withScale(scale: Int, roundingMode: RoundingMode = ROUNDING): Money =
        setScale(scale, roundingMode)

    // ── Addition and subtraction ─────────────────────────────────────────────

    operator fun plus(other: Money): Money = of(amount.add(other.amount))

    operator fun minus(other: Money): Money = of(amount.subtract(other.amount))

    // ── Multiplication and division ──────────────────────────────────────────

    /**
     * Quantity times price. The receiver is expected to already be normalized (it is, when built
     * through [of]) — that ordering matters: 3 x 33,33 = 99,99, not 3 x 33,3333 rounded
     * afterwards to 100,00.
     */
    operator fun times(factor: BigDecimal): Money = of(amount.multiply(factor))

    operator fun times(factor: Int): Money = times(BigDecimal(factor))

    /**
     * Division always names a scale and a rounding mode, so a non-terminating quotient such as
     * 10,00 / 3 yields 3,33 instead of throwing ArithmeticException the way [BigDecimal.divide]
     * would without them.
     *
     * @throws IllegalArgumentException on a zero divisor — Money is a generic value object and
     *   deliberately does not depend on the invoice domain's exception types.
     */
    operator fun div(divisor: BigDecimal): Money {
        require(divisor.signum() != 0) { "Division durch Null ist nicht erlaubt." }
        return Money(amount.divide(divisor, SCALE, ROUNDING))
    }

    operator fun div(divisor: Int): Money = div(BigDecimal(divisor))

    operator fun unaryMinus(): Money = Money(amount.negate())

    fun abs(): Money = Money(amount.abs())

    /**
     * [rate] is given in percentage points, so 19 means 19 % — matching `TaxRate.rate` and the
     * `x rate / 100` chains this replaces.
     */
    fun percentage(rate: BigDecimal): Money =
        Money(amount.multiply(rate).divide(ONE_HUNDRED, SCALE, ROUNDING))

    /** Fixed two decimals — deliberately not [BigDecimal.stripTrailingZeros], which turned 1,50 into "1.5". */
    override fun toString(): String = amount.setScale(SCALE, ROUNDING).toPlainString()

    companion object {
        /** Currency scale enforced at the persistence boundary and by [of]: the database accepts only two decimal places. */
        const val SCALE = 2
        val ROUNDING: RoundingMode = RoundingMode.HALF_UP
        private val ONE_HUNDRED = BigDecimal("100")

        val ZERO = Money(BigDecimal.ZERO.setScale(SCALE))

        /**
         * Preferred factory for money that represents a final, quotable amount (a price, a stored
         * total). Normalizes to [Money.SCALE] decimals immediately, so "33.3333" becomes "33.33" here
         * rather than only at save time.
         */
        fun of(value: BigDecimal): Money = Money(value.setScale(SCALE, ROUNDING))
        fun of(value: String): Money = of(BigDecimal(value))

        /**
         * Keeps the value unrounded on purpose, for intermediate accumulation that rounds once at
         * the end (see the class KDoc). Same behaviour as the raw constructor, but says so.
         */
        fun exact(value: BigDecimal): Money = Money(value)

        fun sum(amounts: Iterable<Money>): Money =
            of(amounts.fold(BigDecimal.ZERO) { acc, m -> acc.add(m.amount) })
    }
}

/** Sums money without the `fold(Money.ZERO)` boilerplate that used to be copied around. */
fun Iterable<Money>.sum(): Money = Money.sum(this)
