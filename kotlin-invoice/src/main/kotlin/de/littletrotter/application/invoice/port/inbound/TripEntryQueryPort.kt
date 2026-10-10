package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.shared.country.Country
import java.time.LocalDate

/**
 * Input port of the "enter a trip position" use case: the trip dialog and its per-diem editor read
 * the tax rates the trip inherits and the per-diem rates a day can be claimed at — nothing else.
 */
interface TripEntryQueryPort {
    /** See [de.littletrotter.application.reference.port.inbound.TaxRateQueryPort.findAllTaxRates]. */
    fun findAllTaxRates(): List<TaxRateDto>

    /** See [de.littletrotter.application.reference.port.inbound.TaxRateQueryPort.findReferenceTaxRate]. */
    fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRateDto

    /** See [de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort.findAllPerDiemRates]. */
    fun findAllPerDiemRates(): List<PerDiemRateDto>

    /** See [de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort.findDefaultPerDiemRate]. */
    fun findDefaultPerDiemRate(): PerDiemRateDto?
}
