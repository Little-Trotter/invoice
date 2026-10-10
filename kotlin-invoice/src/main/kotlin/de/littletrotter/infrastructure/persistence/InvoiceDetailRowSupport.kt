package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.value.BigDecimalValueObject
import de.littletrotter.shared.Money
import java.math.BigDecimal

/**
 * Tolerant scalar-cell parsing shared by every per-group [jakarta.persistence.Tuple]
 * mapper [InvoiceReadRepositoryImpl.findInvoiceDetailDto] composes
 * ([InvoiceDetailHeaderFieldMapper], [InvoiceDetailPartyFieldMapper],
 * [InvoiceDetailTotalsFieldMapper], [InvoiceDetailTaxFieldMapper], [InvoiceDetailTextFieldMapper],
 * [InvoiceDetailItemFieldMapper]): every raw cell may come back either already converted to its
 * entity-attribute type (Hibernate applying an `AttributeConverter` to a scalar path select) or as
 * its underlying SQL-native type.
 */
internal object InvoiceDetailRowSupport {

    fun parseInvoiceNumber(raw: Any?): String = when (raw) {
        null -> ""
        is InvoiceNumber -> raw.value
        is String -> raw
        else -> raw.toString()
    }

    fun parseInvoiceStatusOrdinal(raw: Any?): Int = when (raw) {
        null -> InvoiceStatus.DRAFT.ordinal
        is Number -> raw.toInt()
        is InvoiceStatus -> raw.ordinal
        is String -> raw.toIntOrNull()
            ?: InvoiceStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.ordinal
            ?: InvoiceStatus.DRAFT.ordinal
        else -> InvoiceStatus.DRAFT.ordinal
    }

    fun parseCurrencyOrdinal(raw: Any?): Int? = when (raw) {
        null -> null
        is Number -> raw.toInt()
        is de.littletrotter.shared.Currency -> raw.ordinal
        is String -> raw.toIntOrNull()
        else -> null
    }

    /** Generic ordinal reader for a scalar enum-column projection (`documentEngine`,
     *  `validationStatus`, `invoiceFormat`, `typeCode`): the raw column, the JPA-converted enum
     *  instance, or a numeric-string fallback all decode to the same ordinal. `null` when
     *  genuinely absent. */
    fun parseOrdinal(raw: Any?): Int? = when (raw) {
        null -> null
        is Number -> raw.toInt()
        is Enum<*> -> raw.ordinal
        is String -> raw.toIntOrNull()
        else -> null
    }

    fun parseBigDecimal(raw: Any?): BigDecimal? = when (raw) {
        null -> null
        is BigDecimal -> raw
        is Money -> raw.amount
        // COALESCE(tr.rate, 0) selects a TaxRatePercent-converted attribute; Hibernate
        // applies the converter to a scalar tuple projection too, so this branch keeps the raw
        // value from a silent null instead of one per-type cast.
        is BigDecimalValueObject -> raw.value
        is Number -> raw.toString().toBigDecimalOrNull()
        is String -> raw.toBigDecimalOrNull()
        else -> null
    }

    /** Same tolerant parsing as [parseBigDecimal], but for the money-typed projection columns —
     *  a JPQL path straight off a `Money`-converted entity attribute already comes back as
     *  [Money], a native/aggregate query cell comes back as a raw [BigDecimal]; either way the DTO
     *  field wants [Money], not [BigDecimal]. */
    fun parseMoney(raw: Any?): Money? = parseBigDecimal(raw)?.let { Money(it) }

    fun parseString(raw: Any?): String? = raw?.toString()
}
