package de.littletrotter.application.reference

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.LocalDate

/** Implements [TaxRateQueryPort] on top of [TaxRateService]. */
@ApplicationScoped
class TaxRateQueries @Inject constructor(
    private val taxRateService: TaxRateService,
    @param:ConfigProperty(name = "littletrotter.page-size.reference-data.lookup", defaultValue = "1000")
    private val configuredReferenceLookupPageSize: Int = 1000,
) : TaxRateQueryPort {

    private val referenceLookupPageSize = if (configuredReferenceLookupPageSize > 0) configuredReferenceLookupPageSize else 1000

    override fun findAllTaxRates(): List<TaxRateDto> = taxRateService.findAllTaxRates(0, referenceLookupPageSize).map { it.toDto() }

    override fun findTaxRateById(id: Long): TaxRateDto? = taxRateService.findTaxRateById(id)?.toDto()

    override fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRateDto =
        taxRateService.findReferenceTaxRate(country, date).toDto()

    override fun findTaxRates(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String?, sortDir: SortDirection): List<TaxRateDto> =
        taxRateService.findAllTaxRates(pageIndex, pageSize, filterText, sortField, sortDir).map { it.toDto() }

    override fun countTaxRates(filterText: String?): Long = taxRateService.countTaxRates(filterText)

    override fun countInactiveTaxRates(filterText: String?): Long = taxRateService.countInactiveTaxRates(filterText)

    override fun isTaxRateInUse(id: Long): Boolean = taxRateService.isTaxRateInUse(id)

    override fun findTaxRateUsageCounts(): Map<Long, Long> = taxRateService.findTaxRateUsageCounts()
}
