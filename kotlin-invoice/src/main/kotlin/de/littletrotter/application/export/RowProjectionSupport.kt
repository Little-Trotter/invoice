package de.littletrotter.application.export

import de.littletrotter.domain.value.BigDecimalValueObject
import de.littletrotter.shared.Money
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.shared.tax.VatCategory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Extension helpers over a raw Criteria tuple projection (`Array<Any?>`), shared by
 * [InvoiceHeaderRowMapper] and [InvoicePositionRowMapper] — both map a
 * scalar-projection row into a fully-typed export row the same way
 * [de.littletrotter.application.dto.InvoiceOverviewMapper.fromRow] already does elsewhere in this
 * module, so the field-by-field extraction logic is written once instead of twice.
 */
internal object RowProjectionSupport {

    fun Array<Any?>.str(index: Int): String? = getOrNull(index)?.toString()

    /** Code of a converted enum attribute selected through JPQL; plain values pass through as text. */
    fun Array<Any?>.code(index: Int): String? = when (val v = getOrNull(index)) {
        is DocumentLanguage -> v.code
        is VatCategory -> v.code
        else -> v?.toString()
    }

    fun Array<Any?>.long(index: Int): Long? = (getOrNull(index) as? Number)?.toLong()

    fun Array<Any?>.int(index: Int): Int? = (getOrNull(index) as? Number)?.toInt()

    fun Array<Any?>.date(index: Int): LocalDate? = getOrNull(index) as? LocalDate

    fun Array<Any?>.bool(index: Int): Boolean? = getOrNull(index) as? Boolean

    fun Array<Any?>.bigDecimal(index: Int): BigDecimal? = when (val raw = getOrNull(index)) {
        null -> null
        is BigDecimal -> raw
        is Money -> raw.amount
        // A BigDecimal-based value class (TaxRatePercent, DiscountPercent, ...) already
        // comes back converted from a Criteria/JPQL scalar projection (see this object's own KDoc
        // on enumOrDefault for the same behaviour on converter-backed enums); one generic branch
        // here instead of one `is XyzPercent -> raw.value` per type.
        is BigDecimalValueObject -> raw.value
        is Number -> raw.toString().toBigDecimalOrNull()
        is String -> raw.toBigDecimalOrNull()
        else -> null
    }

    /** A JPQL/Criteria path onto a [de.littletrotter.domain.shared.MoneyConverter]-backed
     *  attribute already comes back as [Money]; kept tolerant of a raw [BigDecimal] the same way
     *  [de.littletrotter.infrastructure.persistence.InvoiceReadRepositoryImpl.parseMoney] is. */
    fun Array<Any?>.money(index: Int): Money? = bigDecimal(index)?.let { Money(it) }

    /**
     * A converter-backed enum attribute (e.g. `typeCode`, `category`, `unit`) already comes back
     * fully converted from a Criteria scalar projection — only [de.littletrotter.domain.invoice
     * .InvoiceNumberConverter] is known to throw instead (handled separately via
     * a native-SQL bypass). The `is Number` branch stays as a defensive fallback for a raw
     * ordinal, never relied upon in practice.
     */
    inline fun <reified T : Enum<T>> Array<Any?>.enumOrDefault(index: Int, default: T): T =
        when (val raw = getOrNull(index)) {
            is T -> raw
            is Number -> enumValues<T>().getOrNull(raw.toInt()) ?: default
            else -> default
        }
}
