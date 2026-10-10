/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.reference

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Plain cache projection of [TaxRate] for [TaxRateService]'s `ref:tax-rates` Valkey entry.
 *
 * CACHE-FIX: [TaxRate.rate] is a `@JvmInline` value class ([TaxRatePercent]); Kotlin mangles the
 * accessor name it generates for a value-class-typed property, so Jackson's bean introspection
 * cannot find `getRate()`/`setRate()` under their plain names. Caching the entity directly meant a
 * write (serialization, driven by field access, not bean introspection of the target) silently
 * succeeded while the matching read (`readValue<List<TaxRate>>`, which *does* need the mangled
 * accessors) always threw [com.fasterxml.jackson.core.JsonProcessingException] — treated by
 * [TaxRateService] as a corrupt entry, falling back to a fresh DB read every time and
 * disabling the cache in production, not only in tests.
 *
 * This DTO carries [rate] as a plain [BigDecimal] instead — the same fix [de.littletrotter.shared.Money]
 * already applies (via `@JsonCreator`/`@JsonProperty`) for [de.littletrotter.domain.reference.PerDiemRate]'s
 * cache entries — and [TaxRateService] maps entity ↔ DTO explicitly at the cache boundary
 * (direct property access, not Jackson introspection), so callers keep receiving `List<TaxRate>`
 * unchanged.
 *
 * `@JsonCreator`/`@param:JsonProperty` on every constructor parameter, same reasoning as [Money]:
 * a data class with no no-arg constructor needs one or the other to deserialize on a plain
 * `ObjectMapper` (no `jackson-module-kotlin`/`ParameterNamesModule`). The five `is`-prefixed
 * Boolean parameters additionally carry `@get:JsonProperty` under the *same* literal name (Jackson's
 * default bean introspection would otherwise strip the `is` prefix from the generated `isXxx()`
 * getter, e.g. serializing under `"hotelRate"` while the constructor still expects `"isHotelRate"`).
 */
data class TaxRateCacheEntry
@JsonCreator
constructor(
    @param:JsonProperty("id") val id: Long?,
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("rate") val rate: BigDecimal,
    @param:JsonProperty("country") val country: Country,
    @param:JsonProperty("validFrom") val validFrom: LocalDate?,
    @param:JsonProperty("validTo") val validTo: LocalDate?,
    @get:JsonProperty("isHotelRate") @param:JsonProperty("isHotelRate") val isHotelRate: Boolean,
    @get:JsonProperty("isDefault") @param:JsonProperty("isDefault") val isDefault: Boolean,
    @get:JsonProperty("isReference") @param:JsonProperty("isReference") val isReference: Boolean,
    @param:JsonProperty("code") val code: String?,
    @param:JsonProperty("category") val category: String?,
    @param:JsonProperty("exemptionReasonCode") val exemptionReasonCode: String?,
    @param:JsonProperty("exemptionReasonText") val exemptionReasonText: String?,
    @param:JsonProperty("legalBasis") val legalBasis: String?,
    @get:JsonProperty("isActive") @param:JsonProperty("isActive") val isActive: Boolean,
    @get:JsonProperty("isSelectable") @param:JsonProperty("isSelectable") val isSelectable: Boolean,
    @param:JsonProperty("createdAt") val createdAt: OffsetDateTime?,
    @param:JsonProperty("version") val version: Long?,
) {
    /** Rebuilds a detached [TaxRate] carrying every field this DTO captured. */
    fun toEntity(): TaxRate =
        TaxRate().also { e ->
            e.id = id
            e.name = name
            e.rate = TaxRatePercent(rate)
            e.country = country
            e.validFrom = validFrom
            e.validTo = validTo
            e.isHotelRate = isHotelRate
            e.isDefault = isDefault
            e.isReference = isReference
            e.code = code
            e.category = category
            e.exemptionReasonCode = exemptionReasonCode
            e.exemptionReasonText = exemptionReasonText
            e.legalBasis = legalBasis
            e.isActive = isActive
            e.isSelectable = isSelectable
            e.createdAt = createdAt
            e.version = version
        }

    companion object {
        fun from(entity: TaxRate): TaxRateCacheEntry =
            TaxRateCacheEntry(
                id = entity.id,
                name = entity.name,
                rate = entity.rate.value,
                country = entity.country,
                validFrom = entity.validFrom,
                validTo = entity.validTo,
                isHotelRate = entity.isHotelRate,
                isDefault = entity.isDefault,
                isReference = entity.isReference,
                code = entity.code,
                category = entity.category,
                exemptionReasonCode = entity.exemptionReasonCode,
                exemptionReasonText = entity.exemptionReasonText,
                legalBasis = entity.legalBasis,
                isActive = entity.isActive,
                isSelectable = entity.isSelectable,
                createdAt = entity.createdAt,
                version = entity.version,
            )
    }
}
