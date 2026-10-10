package de.littletrotter.ui.view.referencedata

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import java.math.BigDecimal
import java.time.LocalDate

// Every form bean in this file is a plain `class`, not a `data class`: each is bound by a
// Vaadin Binder via setter references, which need `var` fields; an immutable `val` form would
// need per-field copy() plumbing Binder's bind(getter, setter) API does not support (same
// rationale as SenderProfileFormData). Nothing here compares two instances, prints one, or
// copies one — only the auto-generated equals/hashCode over mutable fields ever tripped
// DataClassShouldBeImmutable.

/** B2B profile metadata (name/description/
 *  active/country scope); the profile's fields are managed separately in
 *  [de.littletrotter.ui.view.referencedata.B2bProfilesTab]'s fields panel, not through this form. */
class B2bProfileFormData(
    var id: Long? = null,
    var name: String = "",
    var description: String = "",
    var active: Boolean = true,
    /** Empty = unscoped (visible for every seller), the pre-existing behavior. */
    var countries: Set<Country> = emptySet(),
) {
    fun toDto(version: Long?): B2bProfileDto = B2bProfileDto(
        id = id,
        version = version,
        name = name.trim(),
        description = description.trim().ifBlank { null },
        active = active,
        countries = countries.toSet(),
    )

    companion object {
        fun from(profile: B2bProfileDto?): B2bProfileFormData {
            if (profile == null) return B2bProfileFormData()
            return B2bProfileFormData(
                id = profile.id,
                name = profile.name,
                description = profile.description.orEmpty(),
                active = profile.active,
                countries = profile.countries.toSet(),
            )
        }
    }
}

/**
 * The fixed `name`/`englishName` pair the old `PaymentTerm.name`/`english_name` columns
 * mirrored is replaced by [namesByLanguageCode], one entry per rendered language, the same shape
 * `SenderProfileFormData.invoiceNotesByLanguageCode` uses for the seller note. Deliberately
 * **not** part of [toDto]'s plain field mirror — [ManagePaymentTerm][de.littletrotter.application
 * .reference.ManagePaymentTerm] resolves each code against the active
 * [de.littletrotter.domain.reference.Language] rows and applies
 * [de.littletrotter.domain.masterdata.PaymentTerm.setName]'s own create/update/delete handling
 * (blank removes the entry), never a plain field copy.
 */
class PaymentTermFormData(
    var id: Long? = null,
    var namesByLanguageCode: MutableMap<String, String> = mutableMapOf(),
    var description: String = "",
    var dueInDays: Int? = 30,
    var discountPercent: BigDecimal? = null,
    var discountDays: Int? = null,
    var isDefault: Boolean = false,
    var isActive: Boolean = true,
) {
    /** The row to save, normalized the way the catalogue stores it; [version] is the revision the edit started from. */
    fun toDto(version: Long?): PaymentTermDto = PaymentTermDto(
        id = id,
        version = version,
        namesByLanguageCode = namesByLanguageCode,
        description = description.trim().ifBlank { null },
        dueInDays = dueInDays ?: 0,
        discountPercent = DiscountPercent(discountPercent ?: BigDecimal.ZERO),
        discountDays = discountDays,
        isDefault = isDefault,
        isActive = isActive,
    )

    companion object {
        fun from(term: PaymentTermDto?): PaymentTermFormData {
            if (term == null) return PaymentTermFormData()
            return PaymentTermFormData(
                id = term.id,
                namesByLanguageCode = term.namesByLanguageCode.toMutableMap(),
                description = term.description.orEmpty(),
                dueInDays = term.dueInDays,
                discountPercent = term.discountPercent.value.takeIf { it > BigDecimal.ZERO },
                discountDays = term.discountDays,
                isDefault = term.isDefault,
                isActive = term.isActive,
            )
        }
    }
}

class TaxRateFormData(
    var id: Long? = null,
    var name: String = "",
    var rate: BigDecimal? = BigDecimal.ZERO,
    var country: String = "DE",
    var validFrom: LocalDate? = null,
    var validTo: LocalDate? = null,
    var isHotelRate: Boolean = false,
    var isDefault: Boolean = false,
    var isReference: Boolean = false,
    // ── The tax rule fields, previously only settable via migration/SQL ────
    var code: String? = null,
    var category: String? = null,
    var exemptionReasonCode: String? = null,
    var exemptionReasonText: String? = null,
    var legalBasis: String? = null,
    var isActive: Boolean = true,
    var isSelectable: Boolean = true,
) {
    fun toDto(version: Long?): TaxRateDto = TaxRateDto(
        id = id,
        version = version,
        name = name.trim(),
        rate = TaxRatePercent(rate ?: BigDecimal.ZERO),
        country = Country.fromAlpha2(country.trim()) ?: Country.DE,
        validFrom = validFrom,
        validTo = validTo,
        isHotelRate = isHotelRate,
        isDefault = isDefault,
        isReference = isReference,
        code = code?.trim()?.ifBlank { null },
        category = category?.trim()?.ifBlank { null },
        exemptionReasonCode = exemptionReasonCode?.trim()?.ifBlank { null },
        exemptionReasonText = exemptionReasonText?.trim()?.ifBlank { null },
        legalBasis = legalBasis?.trim()?.ifBlank { null },
        isActive = isActive,
        isSelectable = isSelectable,
    )

    companion object {
        fun from(rate: TaxRateDto?): TaxRateFormData {
            if (rate == null) return TaxRateFormData()
            return TaxRateFormData(
                id = rate.id,
                name = rate.name,
                rate = rate.rate.value,
                country = rate.country.alpha2,
                validFrom = rate.validFrom,
                validTo = rate.validTo,
                isHotelRate = rate.isHotelRate,
                isDefault = rate.isDefault,
                isReference = rate.isReference,
                code = rate.code,
                category = rate.category,
                exemptionReasonCode = rate.exemptionReasonCode,
                exemptionReasonText = rate.exemptionReasonText,
                legalBasis = rate.legalBasis,
                isActive = rate.isActive,
                isSelectable = rate.isSelectable,
            )
        }
    }
}

class PerDiemRateFormData(
    var id: Long? = null,
    var country: String = "",
    var region: String? = null,
    var fullDayRate: Money? = Money.ZERO,
    var arrivalDepartureRate: Money? = Money.ZERO,
    var inlandRules: Boolean = false,
    var isDefault: Boolean = false,
    var validFrom: LocalDate? = null,
    var validTo: LocalDate? = null,
    var isActive: Boolean = true,
    var source: String = "",
) {
    /** The row to save, normalized the way the catalogue stores it; [version] is the revision the edit started from. */
    fun toDto(version: Long?): PerDiemRateDto = PerDiemRateDto(
        id = id,
        version = version,
        country = Country.fromAlpha2(country.trim()),
        region = region?.trim()?.ifBlank { null },
        fullDayRate = fullDayRate ?: Money.ZERO,
        arrivalDepartureRate = arrivalDepartureRate ?: Money.ZERO,
        inlandRules = inlandRules,
        isDefault = isDefault,
        validFrom = validFrom,
        validTo = validTo,
        isActive = isActive,
        source = source.trim().ifBlank { null },
    )

    companion object {
        fun from(rate: PerDiemRateDto?): PerDiemRateFormData {
            if (rate == null) return PerDiemRateFormData()
            val rateCountry = rate.country
            return PerDiemRateFormData(
                id = rate.id,
                country = rateCountry?.alpha2 ?: "",
                region = rate.region,
                fullDayRate = rate.fullDayRate,
                arrivalDepartureRate = rate.arrivalDepartureRate,
                inlandRules = rate.inlandRules,
                isDefault = rate.isDefault,
                validFrom = rate.validFrom,
                validTo = rate.validTo,
                isActive = rate.isActive,
                source = rate.source.orEmpty(),
            )
        }
    }
}

/** Editor form of one hotel-rate row. Shape mirrors PerDiemRateFormData minus the split day-rate. */
class HotelRateFormData(
    var id: Long? = null,
    var country: String = "",
    var region: String? = null,
    var ratePerNight: Money? = Money.ZERO,
    var validFrom: LocalDate? = null,
    var validTo: LocalDate? = null,
    var isActive: Boolean = true,
    var source: String = "",
) {
    /** The row to save, normalized the way the catalogue stores it; [version] is the revision the edit started from. */
    fun toDto(version: Long?): HotelRateDto = HotelRateDto(
        id = id,
        version = version,
        country = Country.fromAlpha2(country.trim()),
        region = region?.trim()?.ifBlank { null },
        ratePerNight = ratePerNight ?: Money.ZERO,
        validFrom = validFrom,
        validTo = validTo,
        isActive = isActive,
        source = source.trim().ifBlank { null },
    )

    companion object {
        fun from(rate: HotelRateDto?): HotelRateFormData {
            if (rate == null) return HotelRateFormData()
            return HotelRateFormData(
                id = rate.id,
                country = rate.country?.alpha2 ?: "",
                region = rate.region,
                ratePerNight = rate.ratePerNight,
                validFrom = rate.validFrom,
                validTo = rate.validTo,
                isActive = rate.isActive,
                source = rate.source.orEmpty(),
            )
        }
    }
}

class ArticleFormData(
    var id: Long? = null,
    var name: String = "",
    var description: String? = null,
    var category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
    var defaultUnit: InvoiceItemUnit = InvoiceItemUnit.HOUR,
    var defaultQuantity: BigDecimal? = BigDecimal.ONE,
    var defaultUnitPriceNet: Money? = Money.ZERO,
    var defaultTaxRateId: Long? = null,
    val version: Long? = 0,
) {
    /** The binder-managed part of the article to save; the view adds identifiers, tax rate and
     *  owned rows from its own widgets. [version] is the revision the edit started from. */
    fun toDraft(version: Long?): ArticleDraft = ArticleDraft(
        id = id,
        version = version,
        name = name.trim(),
        description = description?.trim()?.ifBlank { null },
        category = category,
        defaultUnit = defaultUnit,
        defaultQuantity = defaultQuantity ?: BigDecimal.ONE,
        defaultUnitPriceNet = defaultUnitPriceNet ?: Money.ZERO,
    )

    companion object {
        fun from(article: ArticleDto?): ArticleFormData {
            if (article == null) return ArticleFormData()
            return ArticleFormData(
                id = article.id,
                name = article.name,
                description = article.description,
                category = article.category,
                defaultUnit = article.defaultUnit,
                defaultQuantity = article.defaultQuantity,
                defaultUnitPriceNet = article.defaultUnitPriceNet,
                defaultTaxRateId = article.defaultTaxRateId,
                version = article.version,
            )
        }
    }
}

/** Number-range reference data. [prefixSource]/[prefixDataField] control where the
 *  prefix comes from; a blank `fixedPrefix` with `FIXED` is the common user error and is rejected
 *  by the domain, along with `YEARLY`+`datePart=NONE`. */
class NumberRangeFormData(
    var id: Long? = null,
    var code: String = "",
    var displayName: String = "",
    var subject: NumberRangeSubject = NumberRangeSubject.CUSTOMER,
    var startValue: Int? = 1,
    var prefixSource: PrefixSource = PrefixSource.NONE,
    var fixedPrefix: String? = null,
    // Genuinely nullable domain value, not a component-wiring artefact: mirrors
    // NumberRange.prefixDataField, which the domain (NumberRange's own validation) requires to be
    // null unless prefixSource == FROM_DATA. Not a Vaadin component reference at all.
    var prefixDataField: PrefixDataField? = null,
    var datePart: DatePart = DatePart.NONE,
    var counterWidth: Int = 5,
    var resetCycle: ResetCycle = ResetCycle.NEVER,
    var scope: CounterScope = CounterScope.GLOBAL,
    var continuity: Continuity = Continuity.UNIQUE_ONLY,
    var isActive: Boolean = true,
    var isDefault: Boolean = false,
) {
    /** The range to save, normalized the way the catalogue stores it; [version] is the revision the edit started from. */
    fun toDto(version: Long?): NumberRangeDto = NumberRangeDto(
        id = id,
        version = version,
        code = code.trim(),
        displayName = displayName.trim(),
        subject = subject,
        startValue = (startValue ?: 1).toLong(),
        prefixSource = prefixSource,
        fixedPrefix = fixedPrefix?.trim()?.ifBlank { null },
        prefixDataField = prefixDataField,
        datePart = datePart,
        counterWidth = counterWidth,
        resetCycle = resetCycle,
        scope = scope,
        continuity = continuity,
        isActive = isActive,
        isDefault = isDefault,
    )

    companion object {
        fun from(range: NumberRangeDto?): NumberRangeFormData {
            if (range == null) return NumberRangeFormData()
            return NumberRangeFormData(
                id = range.id,
                code = range.code,
                displayName = range.displayName,
                subject = range.subject,
                startValue = range.startValue.toInt(),
                prefixSource = range.prefixSource,
                fixedPrefix = range.fixedPrefix,
                prefixDataField = range.prefixDataField,
                datePart = range.datePart,
                counterWidth = range.counterWidth,
                resetCycle = range.resetCycle,
                scope = range.scope,
                continuity = range.continuity,
                isActive = range.isActive,
                isDefault = range.isDefault,
            )
        }
    }
}
