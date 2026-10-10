package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `Article.articleNumber` (e.g. `ART-00042`), assigned once via the number-range
 * counter or typed freely when no range is active. `null` means "never numbered"; once set, it
 * must be a real value and fit the `article_number VARCHAR(60)` column
 * (`V1__baseline_schema.sql`) — both invariants used to be checked lazily, the length one only
 * implicitly via Bean Validation `@Size`/a DB truncation error at flush time; both now throw at
 * construction (see `ArticleNumberTest` pitfall notes on deferred-vs-fail-fast validation).
 */
@JvmInline
value class ArticleNumber(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidParameterException("Artikelnummer darf nicht leer sein, wenn gesetzt.")
        }
        if (value.length > MAX_LENGTH) {
            throw InvalidParameterException("Artikelnummer darf höchstens $MAX_LENGTH Zeichen haben.")
        }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        private const val MAX_LENGTH = 60

        override val maxLength: Int = MAX_LENGTH

        /** Returns `null` for blank/null input instead of throwing — for boundaries (UI fields,
         *  number-range proposals) where "nothing typed yet" is the normal case. */
        fun ofNullable(raw: String?): ArticleNumber? = if (raw.isNullOrBlank()) null else ArticleNumber(raw)
    }
}

@Converter(autoApply = true)
class ArticleNumberConverter : AttributeConverter<ArticleNumber?, String?> {
    override fun convertToDatabaseColumn(attribute: ArticleNumber?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): ArticleNumber? = ArticleNumber.ofNullable(dbData)
}
