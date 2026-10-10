package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRateTerms
import de.littletrotter.domain.value.TaxRatePercent
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One tax rule of the tax-rate catalogue: what the catalogue grid shows and its editor saves, and
 * what the article form and the invoice item dialogs offer and pre-select. [id] `null` marks a new
 * row; [version] is the row's optimistic-lock revision, checked on save.
 *
 * Implements [TaxRateTerms] so applicability, category and reference-rate eligibility are
 * answered by the same rule the stored row uses, never by a second copy of it.
 */
data class TaxRateDto(
    val id: Long? = null,
    val version: Long? = null,
    val name: String = "",
    override val rate: TaxRatePercent = TaxRatePercent(BigDecimal.ZERO),
    override val country: Country = Country.DE,
    override val validFrom: LocalDate? = null,
    override val validTo: LocalDate? = null,
    override val isHotelRate: Boolean = false,
    val isDefault: Boolean = false,
    override val isReference: Boolean = false,
    override val code: String? = null,
    override val category: String? = null,
    val exemptionReasonCode: String? = null,
    val exemptionReasonText: String? = null,
    val legalBasis: String? = null,
    override val isActive: Boolean = true,
    override val isSelectable: Boolean = true,
) : TaxRateTerms
