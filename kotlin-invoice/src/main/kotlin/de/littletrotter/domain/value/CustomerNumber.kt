package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * A recipient's customer number (Kundennummer): either auto-assigned by
 * `MasterDataNumberService`/`NumberRangeService` from an active number range, or typed by the
 * Anwender when no range is active. No format invariant beyond non-blank + a length bound —
 * `MasterDataNumberService` accepts whatever a number-range template or a manual entry produces
 * (see [MAX_LENGTH]'s own KDoc). `@JvmInline` over [String] so a nullable field
 * (`CustomerNumber?`) does not box (see [TaxRatePercent]'s KDoc for why nullable reference-typed
 * value classes are exempt).
 *
 * `recipient.customer_number` is itself `NOT NULL VARCHAR(50)` — this type models a
 * not-yet-assigned number as `null` regardless (the same shape every other optional identifier
 * in this inventory uses); [CustomerNumberConverter] is what preserves the column's existing
 * NOT-NULL/empty-string-sentinel behaviour (`MasterDataNumberService.saveCustomerWithNumber`'s
 * reassign step still sets the field to `null` before a fresh allocation, exactly as it used to
 * set `""`), so this is a Kotlin-side-only refinement, not a schema change.
 *
 * Only trims — never case-folds. Construct via [of]/[ofNullable].
 */
@JvmInline
value class CustomerNumber(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidParameterException("Kundennummer darf nicht leer sein.")
        }
        if (value.length > MAX_LENGTH) {
            throw InvalidParameterException("Kundennummer darf höchstens $MAX_LENGTH Zeichen lang sein.")
        }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        /** `recipient.customer_number`'s `VARCHAR(50)` bound, carried forward from the
         *  `@Size(max = 50)` Bean Validation annotation the raw `String` field used to carry. */
        const val MAX_LENGTH = 50

        override val maxLength: Int = MAX_LENGTH

        fun of(raw: String): CustomerNumber = CustomerNumber(raw.trim())

        fun ofNullable(raw: String?): CustomerNumber? = if (raw.isNullOrBlank()) null else of(raw)
    }
}

@Converter(autoApply = true)
class CustomerNumberConverter : AttributeConverter<CustomerNumber?, String?> {
    // The column stays NOT NULL with "" as the DB-level "not yet assigned" sentinel (its own
    // partial unique index already reads `WHERE customer_number <> ''`) — only the entity-side
    // Kotlin type turns that into the idiomatic null every other optional value class here uses.
    override fun convertToDatabaseColumn(attribute: CustomerNumber?): String = attribute?.value ?: ""
    override fun convertToEntityAttribute(dbData: String?): CustomerNumber? = CustomerNumber.ofNullable(dbData)
}
