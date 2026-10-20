package de.littletrotter.ui.view.referencedata

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.reference.toDto
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextEntry
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Mapping-arm coverage for every `applyTo`/`from` pair in ReferenceDataFormData.kt:
 *  each nullable/blank field arm (elvis, `?.trim()?.ifBlank { null }`, `orEmpty()`) round-tripped
 *  both populated and null/blank, plus the `from(null)` default-instance arm per class. */
class ReferenceDataFormDataTest {

    // ── B2bProfileFormData ──────────────────────────────────────────────────────────────

    @Test
    fun `happy path - B2bProfileFormData toDto and from round trip every field`() {
        val form = B2bProfileFormData(
            id = 7L,
            name = " Reisebüro ",
            description = " Profil für Reisebüros ",
            active = false,
            countries = setOf(Country.FR),
        )
        val profile = form.toDto(version = 4L)

        assertEquals(7L, profile.id)
        assertEquals(4L, profile.version)
        assertEquals("Reisebüro", profile.name)
        assertEquals("Profil für Reisebüros", profile.description)
        assertEquals(false, profile.active)
        assertEquals(setOf(Country.FR), profile.countries)

        val roundTripped = B2bProfileFormData.from(profile)
        assertEquals(profile.id, roundTripped.id)
        assertEquals(profile.name, roundTripped.name)
        assertEquals(profile.description, roundTripped.description)
        assertEquals(profile.active, roundTripped.active)
        assertEquals(profile.countries, roundTripped.countries)
    }

    @Test
    fun `boundary - B2bProfileFormData toDto blanks a whitespace-only description to null`() {
        assertNull(B2bProfileFormData(description = "   ").toDto(version = null).description)
    }

    @Test
    fun `boundary - B2bProfileFormData from returns defaults for a null profile`() {
        // B2bProfileFormData is a plain class (Vaadin-Binder form bean), not a data
        // class: no generated equals(), so every field is asserted explicitly.
        val result = B2bProfileFormData.from(null)
        assertNull(result.id)
        assertEquals("", result.name)
        assertEquals("", result.description)
        assertEquals(true, result.active)
        assertEquals(emptySet<Country>(), result.countries)
    }

    @Test
    fun `boundary - B2bProfileFormData from defaults a null row description to empty string`() {
        val profile = B2bProfileDto(name = "Standard", description = null)
        assertEquals("", B2bProfileFormData.from(profile).description)
    }

    // ── PaymentTermFormData ─────────────────────────────────────────────────────────────

    @Test
    fun `happy path - PaymentTermFormData toDto and from round trip every field`() {
        val form = PaymentTermFormData(
            id = 3L,
            description = " 14 Tage netto ",
            dueInDays = 14,
            discountPercent = BigDecimal("2.00"),
            discountDays = 7,
            isDefault = true,
            isActive = true,
        )
        val dto = form.toDto(version = 5L)

        assertEquals(3L, dto.id)
        assertEquals(5L, dto.version)
        assertEquals("14 Tage netto", dto.description)
        assertEquals(14, dto.dueInDays)
        assertEquals(BigDecimal("2.00"), dto.discountPercent.value)
        assertEquals(7, dto.discountDays)
        assertTrue(dto.isDefault)
        assertTrue(dto.isActive)

        val roundTripped = PaymentTermFormData.from(dto)
        assertEquals(dto.description, roundTripped.description)
        assertEquals(dto.dueInDays, roundTripped.dueInDays)
        assertEquals(dto.discountPercent.value, roundTripped.discountPercent)
        assertEquals(dto.discountDays, roundTripped.discountDays)
    }

    @Test
    fun `boundary - PaymentTermFormData toDto blanks a whitespace-only description to null`() {
        val dto = PaymentTermFormData(description = "   ").toDto(null)
        assertNull(dto.description)
    }

    @Test
    fun `boundary - PaymentTermFormData toDto defaults a null dueInDays to zero`() {
        val dto = PaymentTermFormData(dueInDays = null).toDto(null)
        assertEquals(0, dto.dueInDays)
    }

    @Test
    fun `boundary - PaymentTermFormData from maps a null nameGroup to an empty map`() {
        val term = aPaymentTerm { nameGroup = null }
        assertTrue(PaymentTermFormData.from(term.toDto()).namesByLanguageCode.isEmpty())
    }

    @Test
    fun `boundary - PaymentTermFormData from drops entries whose language is null and keeps the rest`() {
        val germanLanguage = Language().edit { code = "de" }
        val group = LocalizedTextGroup().edit { purpose = LocalizedTextPurpose.PAYMENT_TERM_NAME }
        group.addEntry(germanLanguage, "Sofort")
        // an entry with no language assigned (data anomaly / not-yet-saved row) must be filtered out
        group.entries.add(
            LocalizedTextEntry().edit { this.group = group; language = null; value = "orphan" },
        )
        val term = aPaymentTerm { nameGroup = group }

        val result = PaymentTermFormData.from(term.toDto()).namesByLanguageCode
        assertEquals(mapOf("de" to "Sofort"), result)
    }

    @Test
    fun `boundary - PaymentTermFormData from clears a zero discountPercent to null`() {
        val term = aPaymentTerm { discountPercent = DiscountPercent(BigDecimal.ZERO) }
        assertNull(PaymentTermFormData.from(term.toDto()).discountPercent)
    }

    @Test
    fun `boundary - PaymentTermFormData from keeps a positive discountPercent`() {
        val term = aPaymentTerm { discountPercent = DiscountPercent(BigDecimal("3.50")) }
        assertEquals(BigDecimal("3.50"), PaymentTermFormData.from(term.toDto()).discountPercent)
    }

    @Test
    fun `boundary - PaymentTermFormData from returns defaults for a null term`() {
        val result = PaymentTermFormData.from(null)
        assertNull(result.id)
        assertEquals(emptyMap<String, String>(), result.namesByLanguageCode)
        assertEquals("", result.description)
        assertEquals(30, result.dueInDays)
        assertNull(result.discountPercent)
        assertNull(result.discountDays)
        assertEquals(false, result.isDefault)
        assertEquals(true, result.isActive)
    }

    // ── TaxRateFormData ─────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - TaxRateFormData toDto and from round trip every field`() {
        val form = TaxRateFormData(
            id = 5L,
            name = " Standard ",
            rate = BigDecimal("19.00"),
            country = "de",
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = LocalDate.of(2026, 12, 31),
            isHotelRate = false,
            isDefault = true,
            isReference = false,
            code = " S ",
            category = " STANDARD ",
            exemptionReasonCode = " VATEX-EU-79-C ",
            exemptionReasonText = " steuerfrei ",
            legalBasis = " §4 UStG ",
            isActive = true,
            isSelectable = true,
        )
        val taxRate = form.toDto(version = 3L)

        assertEquals(5L, taxRate.id)
        assertEquals(3L, taxRate.version)
        assertEquals("Standard", taxRate.name)
        assertEquals(BigDecimal("19.00"), taxRate.rate.value)
        assertEquals(Country.DE, taxRate.country)
        assertEquals("S", taxRate.code)
        assertEquals("STANDARD", taxRate.category)
        assertEquals("VATEX-EU-79-C", taxRate.exemptionReasonCode)
        assertEquals("steuerfrei", taxRate.exemptionReasonText)
        assertEquals("§4 UStG", taxRate.legalBasis)

        val roundTripped = TaxRateFormData.from(taxRate)
        assertEquals(taxRate.name, roundTripped.name)
        assertEquals(taxRate.rate.value, roundTripped.rate)
        assertEquals(taxRate.country.alpha2, roundTripped.country)
        assertEquals(taxRate.code, roundTripped.code)
        assertEquals(taxRate.category, roundTripped.category)
    }

    @Test
    fun `boundary - TaxRateFormData toDto defaults a null rate to zero`() {
        val taxRate = TaxRateFormData(rate = null).toDto(version = null)
        assertEquals(BigDecimal.ZERO, taxRate.rate.value)
    }

    @Test
    fun `boundary - TaxRateFormData toDto falls back to DE for an unknown country code`() {
        val taxRate = TaxRateFormData(country = "XX").toDto(version = null)
        assertEquals(Country.DE, taxRate.country)
    }

    @Test
    fun `boundary - TaxRateFormData toDto blanks whitespace-only optional text fields to null`() {
        val taxRate = TaxRateFormData(
            code = "   ",
            category = "   ",
            exemptionReasonCode = "   ",
            exemptionReasonText = "   ",
            legalBasis = "   ",
        ).toDto(version = null)

        assertNull(taxRate.code)
        assertEquals("", taxRate.category)
        assertNull(taxRate.exemptionReasonCode)
        assertNull(taxRate.exemptionReasonText)
        assertNull(taxRate.legalBasis)
    }

    @Test
    fun `boundary - TaxRateFormData toDto maps null optional text fields to null`() {
        val taxRate = TaxRateFormData(
            code = null,
            category = null,
            exemptionReasonCode = null,
            exemptionReasonText = null,
            legalBasis = null,
        ).toDto(version = null)

        assertNull(taxRate.code)
        assertEquals("", taxRate.category)
        assertNull(taxRate.exemptionReasonCode)
        assertNull(taxRate.exemptionReasonText)
        assertNull(taxRate.legalBasis)
    }

    @Test
    fun `boundary - TaxRateFormData from returns defaults for a null rate`() {
        val result = TaxRateFormData.from(null)
        assertNull(result.id)
        assertEquals("", result.name)
        assertEquals(BigDecimal.ZERO, result.rate)
        assertEquals("DE", result.country)
        assertNull(result.validFrom)
        assertNull(result.validTo)
        assertEquals(false, result.isHotelRate)
        assertEquals(false, result.isDefault)
        assertEquals(false, result.isReference)
        assertNull(result.code)
        assertNull(result.category)
        assertNull(result.exemptionReasonCode)
        assertNull(result.exemptionReasonText)
        assertNull(result.legalBasis)
        assertEquals(true, result.isActive)
        assertEquals(true, result.isSelectable)
    }

    // ── PerDiemRateFormData ─────────────────────────────────────────────────────────────

    @Test
    fun `happy path - PerDiemRateFormData applyTo and from round trip every field`() {
        val form = PerDiemRateFormData(
            id = 9L,
            country = "de",
            region = " Bayern ",
            fullDayRate = Money.of("28.00"),
            arrivalDepartureRate = Money.of("14.00"),
            inlandRules = true,
            isDefault = true,
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = null,
            isActive = true,
            source = " BMF-Schreiben ",
        )
        val dto = form.toDto(version = 6L)

        assertEquals(Country.DE, dto.country)
        assertEquals("Bayern", dto.region)
        assertEquals(Money.of("28.00"), dto.fullDayRate)
        assertEquals(Money.of("14.00"), dto.arrivalDepartureRate)
        assertEquals("BMF-Schreiben", dto.source)

        val roundTripped = PerDiemRateFormData.from(dto)
        assertEquals("DE", roundTripped.country)
        assertEquals(dto.region, roundTripped.region)
        assertEquals(dto.fullDayRate, roundTripped.fullDayRate)
        assertEquals(dto.source, roundTripped.source)
    }

    @Test
    fun `boundary - PerDiemRateFormData toDto blanks a whitespace-only region to null`() {
        val dto = PerDiemRateFormData(region = "   ").toDto(null)
        assertNull(dto.region)
    }

    @Test
    fun `boundary - PerDiemRateFormData toDto defaults null rate amounts to zero`() {
        val dto = PerDiemRateFormData(fullDayRate = null, arrivalDepartureRate = null).toDto(null)
        assertEquals(Money.ZERO, dto.fullDayRate)
        assertEquals(Money.ZERO, dto.arrivalDepartureRate)
    }

    @Test
    fun `boundary - PerDiemRateFormData from defaults a null country to an empty string`() {
        val rate = PerDiemRate().edit { country = null }
        assertEquals("", PerDiemRateFormData.from(rate.toDto()).country)
    }

    @Test
    fun `boundary - PerDiemRateFormData from defaults a null source to an empty string`() {
        val rate = PerDiemRate().edit { source = null }
        assertEquals("", PerDiemRateFormData.from(rate.toDto()).source)
    }

    @Test
    fun `boundary - PerDiemRateFormData from returns defaults for a null rate`() {
        val result = PerDiemRateFormData.from(null)
        assertNull(result.id)
        assertEquals("", result.country)
        assertNull(result.region)
        assertEquals(Money.ZERO, result.fullDayRate)
        assertEquals(Money.ZERO, result.arrivalDepartureRate)
        assertEquals(false, result.inlandRules)
        assertEquals(false, result.isDefault)
        assertNull(result.validFrom)
        assertNull(result.validTo)
        assertEquals(true, result.isActive)
        assertEquals("", result.source)
    }

    // ── HotelRateFormData ───────────────────────────────────────────────────────────────

    @Test
    fun `happy path - HotelRateFormData toDto and from round trip every field`() {
        val form = HotelRateFormData(
            id = 11L,
            country = "fr",
            region = " Paris ",
            ratePerNight = Money.of("180.00"),
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = LocalDate.of(2026, 12, 31),
            isActive = true,
            source = " BMF-Schreiben ",
        )
        val rate = form.toDto(version = 4L)

        assertEquals(11L, rate.id)
        assertEquals(4L, rate.version)
        assertEquals(Country.FR, rate.country)
        assertEquals("Paris", rate.region)
        assertEquals(Money.of("180.00"), rate.ratePerNight)
        assertEquals("BMF-Schreiben", rate.source)

        val roundTripped = HotelRateFormData.from(rate)
        assertEquals("FR", roundTripped.country)
        assertEquals(rate.region, roundTripped.region)
        assertEquals(rate.ratePerNight, roundTripped.ratePerNight)
        assertEquals(rate.source, roundTripped.source)
    }

    @Test
    fun `boundary - HotelRateFormData toDto blanks a whitespace-only region to null`() {
        assertNull(HotelRateFormData(region = "   ").toDto(version = null).region)
    }

    @Test
    fun `boundary - HotelRateFormData toDto defaults a null rate per night to zero`() {
        assertEquals(Money.ZERO, HotelRateFormData(ratePerNight = null).toDto(version = null).ratePerNight)
    }

    @Test
    fun `boundary - HotelRateFormData from defaults a null country to an empty string`() {
        assertEquals("", HotelRateFormData.from(HotelRateDto(country = null)).country)
    }

    @Test
    fun `boundary - HotelRateFormData from defaults a null source to an empty string`() {
        assertEquals("", HotelRateFormData.from(HotelRateDto(source = null)).source)
    }

    @Test
    fun `boundary - HotelRateFormData from returns defaults for a null rate`() {
        val result = HotelRateFormData.from(null)
        assertNull(result.id)
        assertEquals("", result.country)
        assertNull(result.region)
        assertEquals(Money.ZERO, result.ratePerNight)
        assertNull(result.validFrom)
        assertNull(result.validTo)
        assertEquals(true, result.isActive)
        assertEquals("", result.source)
    }

    // ── ArticleFormData ─────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - ArticleFormData toDraft and from round trip every field`() {
        val form = ArticleFormData(
            id = 2L,
            name = " Beratung ",
            description = " Stundenweise Beratung ",
            category = InvoiceItemCategory.PRODUCT,
            defaultUnit = InvoiceItemUnit.PIECE,
            defaultQuantity = BigDecimal("2.5"),
            defaultUnitPriceNet = Money.of("120.00"),
            defaultTaxRateId = 4L,
            version = 3L,
        )
        val article = form.toDraft(3L)

        assertEquals(2L, article.id)
        assertEquals(3L, article.version)
        assertEquals("Beratung", article.name)
        assertEquals("Stundenweise Beratung", article.description)
        assertEquals(InvoiceItemCategory.PRODUCT, article.category)
        assertEquals(InvoiceItemUnit.PIECE, article.defaultUnit)
        assertEquals(BigDecimal("2.5"), article.defaultQuantity)
        assertEquals(Money.of("120.00"), article.defaultUnitPriceNet)

        assertNull(article.defaultTaxRateId, "the view adds the tax rate from its own combo")

        val roundTripped = ArticleFormData.from(ArticleDto(id = 2L, version = 3L, description = article.description, defaultTaxRateId = 4L))
        assertEquals(article.description, roundTripped.description)
        assertEquals(4L, roundTripped.defaultTaxRateId)
        assertEquals(3L, roundTripped.version)
    }

    @Test
    fun `boundary - ArticleFormData toDraft blanks a whitespace-only description to null`() {
        assertNull(ArticleFormData(description = "   ").toDraft(null).description)
    }

    @Test
    fun `boundary - ArticleFormData toDraft defaults null quantity and price to defaults`() {
        val article = ArticleFormData(defaultQuantity = null, defaultUnitPriceNet = null).toDraft(null)
        assertEquals(BigDecimal.ONE, article.defaultQuantity)
        assertEquals(Money.ZERO, article.defaultUnitPriceNet)
    }

    @Test
    fun `boundary - ArticleFormData from maps a null tax rate reference to a null id`() {
        assertNull(ArticleFormData.from(ArticleDto(defaultTaxRateId = null)).defaultTaxRateId)
    }

    @Test
    fun `boundary - ArticleFormData from returns defaults for a null article`() {
        val result = ArticleFormData.from(null)
        assertNull(result.id)
        assertEquals("", result.name)
        assertNull(result.description)
        assertEquals(InvoiceItemCategory.SERVICE, result.category)
        assertEquals(InvoiceItemUnit.HOUR, result.defaultUnit)
        assertEquals(BigDecimal.ONE, result.defaultQuantity)
        assertEquals(Money.ZERO, result.defaultUnitPriceNet)
        assertNull(result.defaultTaxRateId)
        assertEquals(0L, result.version)
    }

    // ── NumberRangeFormData ─────────────────────────────────────────────────────────────

    @Test
    fun `happy path - NumberRangeFormData toDto and from round trip every field`() {
        val form = NumberRangeFormData(
            id = 1L,
            code = " INV ",
            displayName = " Rechnungen ",
            subject = NumberRangeSubject.INVOICE,
            startValue = 100,
            prefixSource = PrefixSource.FROM_DATA,
            fixedPrefix = null,
            prefixDataField = PrefixDataField.COUNTRY_CODE,
            datePart = DatePart.YEAR,
            counterWidth = 6,
            resetCycle = ResetCycle.YEARLY,
            scope = CounterScope.PER_SELLER,
            continuity = Continuity.GAPLESS,
            isActive = true,
            isDefault = true,
        )
        val range = form.toDto(5L)

        assertEquals(1L, range.id)
        assertEquals(5L, range.version)
        assertEquals("INV", range.code)
        assertEquals("Rechnungen", range.displayName)
        assertEquals(100L, range.startValue)
        assertEquals(PrefixSource.FROM_DATA, range.prefixSource)
        assertNull(range.fixedPrefix)
        assertEquals(PrefixDataField.COUNTRY_CODE, range.prefixDataField)
        assertEquals(ResetCycle.YEARLY, range.resetCycle)
        assertEquals(CounterScope.PER_SELLER, range.scope)
        assertEquals(Continuity.GAPLESS, range.continuity)

        val roundTripped = NumberRangeFormData.from(range)
        assertEquals(range.code, roundTripped.code)
        assertEquals(range.startValue.toInt(), roundTripped.startValue)
        assertEquals(range.resetCycle, roundTripped.resetCycle)
        assertEquals(range.continuity, roundTripped.continuity)
    }

    @Test
    fun `boundary - NumberRangeFormData toDto defaults a null start value to one`() {
        assertEquals(1L, NumberRangeFormData(startValue = null).toDto(null).startValue)
    }

    @Test
    fun `boundary - NumberRangeFormData toDto blanks a whitespace-only fixed prefix to null`() {
        assertNull(NumberRangeFormData(fixedPrefix = "   ").toDto(null).fixedPrefix)
    }

    @Test
    fun `boundary - NumberRangeFormData toDto keeps a non-blank fixed prefix trimmed`() {
        assertEquals("RE-", NumberRangeFormData(fixedPrefix = " RE- ").toDto(null).fixedPrefix)
    }

    @Test
    fun `boundary - NumberRangeFormData from returns defaults for a null range`() {
        val result = NumberRangeFormData.from(null)
        assertNull(result.id)
        assertEquals("", result.code)
        assertEquals("", result.displayName)
        assertEquals(NumberRangeSubject.CUSTOMER, result.subject)
        assertEquals(1, result.startValue)
        assertEquals(PrefixSource.NONE, result.prefixSource)
        assertNull(result.fixedPrefix)
        assertNull(result.prefixDataField)
        assertEquals(DatePart.NONE, result.datePart)
        assertEquals(5, result.counterWidth)
        assertEquals(ResetCycle.NEVER, result.resetCycle)
        assertEquals(CounterScope.GLOBAL, result.scope)
        assertEquals(Continuity.UNIQUE_ONLY, result.continuity)
        assertEquals(true, result.isActive)
        assertEquals(false, result.isDefault)
    }
}
