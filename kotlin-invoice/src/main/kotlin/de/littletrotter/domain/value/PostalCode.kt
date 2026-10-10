package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * A postal code (Postleitzahl / PLZ). [PostalCodeValidator]'s own format rule is
 * country-gated (the strict German five-digit format only when the owning address's country is
 * Germany, a lenient [PostalCodeValidator.MAX_LENGTH]-char sanity check otherwise — see that
 * object's KDoc), but a single-value wrapper like this one does not carry the country alongside
 * the raw string, so the country-specific format check cannot live in this class's own `init`.
 * Design choice: the format check runs at the one call site that has both
 * values — [de.littletrotter.domain.masterdata.Address]'s factory functions
 * ([de.littletrotter.domain.masterdata.Address.forRecipient]/`forSender`, via [forCountry]) — not
 * in the bare constructor.
 *
 * The bare constructor (used by [PostalCodeConverter] to rehydrate an already-persisted row,
 * where no country is in scope, and by [forCountry] itself once the country-specific check
 * already passed) instead enforces the column-level invariant the field carried before this type
 * existed: `address.postal_code` is `VARCHAR(20) NOT NULL` (V1__baseline_schema.sql) — was
 * `@field:Size(max = 20)` — so [MAX_COLUMN_LENGTH] stays 20, not
 * [PostalCodeValidator.MAX_LENGTH] (10, the *country-format* rule's own lenient bound). Blank
 * stays allowed here, same as [PostalCodeValidator] itself — requiredness is
 * [de.littletrotter.domain.masterdata.Address.validate]'s own concern, unchanged.
 */
@JvmInline
value class PostalCode(val value: String) {

    init {
        if (value.length > MAX_COLUMN_LENGTH) {
            throw InvalidParameterException("PLZ darf höchstens $MAX_COLUMN_LENGTH Zeichen lang sein.")
        }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        /** `address.postal_code VARCHAR(20)` (V1__baseline_schema.sql). */
        const val MAX_COLUMN_LENGTH = 20

        override val maxLength: Int = MAX_COLUMN_LENGTH

        /** The only place [PostalCodeValidator]'s country-specific format is enforced — see this
         *  class's own KDoc for why it cannot live in the bare constructor. */
        fun forCountry(countryAlpha2: String?, raw: String): PostalCode {
            val violation = PostalCodeValidator.check(countryAlpha2, raw)
            if (violation != null) throw InvalidParameterException(violation.message)
            return PostalCode(raw)
        }
    }
}

@Converter(autoApply = true)
class PostalCodeConverter : AttributeConverter<PostalCode?, String?> {
    override fun convertToDatabaseColumn(attribute: PostalCode?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): PostalCode? = dbData?.let { PostalCode(it) }
}
