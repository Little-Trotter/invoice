package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * A seller's or buyer's VAT identification number (USt-IdNr.), per-country-prefix
 * format-validated by [VatIdValidator]. `@JvmInline` over [String] so a nullable field
 * (`VatId?`) does not box (see [TaxRatePercent]'s KDoc for why nullable reference-typed value
 * classes are exempt).
 *
 * Always stores the normalised form ([VatIdValidator.normalize]: whitespace stripped,
 * upper-cased) — construct via [of]/[ofNullable].
 *
 * Carries forward every `@Size`/`VARCHAR` bound the raw `String` fields this replaces
 * used to enforce via Bean Validation: [VatIdValidator.MAX_LENGTH] (64,
 * `sender_profile.vat_id`/`recipient.vat_id`/`invoice_party_snapshot.vat_id`'s column width) is
 * still checked before the per-country regex ever runs and is exposed as [maxLength] for
 * [de.littletrotter.infrastructure.persistence.EntityColumnSizeConsistencyIT].
 * `seller_vat_registration.vat_id` used to be narrower (`VARCHAR(32)`) — every recognised country
 * pattern the regex actually matches is far shorter than that (longest: 15 characters), so no
 * stored value was ever at risk, but the column bound itself silently disagreed with this value
 * class's own 64-character contract; the baseline widened it to `VARCHAR(64)` so the one
 * invariant this class states is the one every column carrying it actually enforces.
 *
 * Coordinator follow-up (post-review) — [VatIdValidator.Violation.UNKNOWN_PREFIX] (a
 * syntactically complete two-letter prefix [VatIdValidator] does not recognise, e.g. a Japanese
 * or Brazilian buyer's own tax id) is accepted, stored as-is: before this value class existed,
 * `sender_profile.vat_id`/`recipient.vat_id` carried only a `@Size(max = 64)` bound and no format
 * check at all, so a real third-country identifier this validator simply has no regex for must
 * keep working, matching that pre-existing behaviour instead of a stricter, EU/curated-country-
 * only default this task never asked for. [Violation.NO_PREFIX] (fewer than two characters — not
 * even syntactically a country-prefixed identifier) and [Violation.FORMAT] (a *recognised* prefix
 * with the wrong shape) stay rejected: the Vaadin form's own `VatIdField`/`SenderProfileFormBinder`
 * validators already hard-blocked both identically pre-change (`validation.vatId.unknownPrefix`),
 * and a value too short to carry a prefix at all is malformed input, not a real, merely-
 * unrecognised VAT id — unlike `UNKNOWN_PREFIX`, there is no plausible real-world value this
 * would ever reject in error.
 */
@JvmInline
value class VatId(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidParameterException("USt-IdNr. darf nicht leer sein.")
        }
        when (VatIdValidator.violationOf(value)) {
            VatIdValidator.Violation.TOO_LONG, VatIdValidator.Violation.NO_PREFIX, VatIdValidator.Violation.FORMAT ->
                throw InvalidParameterException(VatIdValidator.validate(value).orEmpty())
            VatIdValidator.Violation.UNKNOWN_PREFIX, null -> Unit
        }
    }

    override fun toString(): String = value

    companion object : BoundedStringValue {
        override val maxLength: Int = VatIdValidator.MAX_LENGTH

        fun of(raw: String): VatId = VatId(VatIdValidator.normalize(raw))

        fun ofNullable(raw: String?): VatId? = if (raw.isNullOrBlank()) null else of(raw)
    }
}

@Converter(autoApply = true)
class VatIdConverter : AttributeConverter<VatId?, String?> {
    override fun convertToDatabaseColumn(attribute: VatId?): String? = attribute?.value
    override fun convertToEntityAttribute(dbData: String?): VatId? = VatId.ofNullable(dbData)
}
