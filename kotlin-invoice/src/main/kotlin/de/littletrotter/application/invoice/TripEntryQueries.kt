package de.littletrotter.application.invoice

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.invoice.port.inbound.TripEntryQueryPort
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/** Implements [TripEntryQueryPort] on top of [TaxRateQueryPort]'s and [PerDiemRateQueryPort]'s reads. */
@ApplicationScoped
class TripEntryQueries @Inject constructor(
    private val taxRateQueries: TaxRateQueryPort,
    private val perDiemRateQueries: PerDiemRateQueryPort,
) : TripEntryQueryPort {

    override fun findAllTaxRates(): List<TaxRateDto> = taxRateQueries.findAllTaxRates()

    override fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRateDto =
        taxRateQueries.findReferenceTaxRate(country, date)

    override fun findAllPerDiemRates(): List<PerDiemRateDto> = perDiemRateQueries.findAllPerDiemRates()

    override fun findDefaultPerDiemRate(): PerDiemRateDto? = perDiemRateQueries.findDefaultPerDiemRate()
}
