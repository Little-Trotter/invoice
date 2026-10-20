package de.littletrotter.application.reference

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Implements [PerDiemRateQueryPort] on top of [PerDiemRateService]. */
@ApplicationScoped
class PerDiemRateQueries @Inject constructor(
    private val perDiemRateService: PerDiemRateService,
    @param:ConfigProperty(name = "littletrotter.page-size.reference-data.lookup", defaultValue = "1000")
    private val configuredReferenceLookupPageSize: Int = 1000,
) : PerDiemRateQueryPort {

    private val referenceLookupPageSize = if (configuredReferenceLookupPageSize > 0) configuredReferenceLookupPageSize else 1000

    override fun findAllPerDiemRates(): List<PerDiemRateDto> = perDiemRateService.findAllPerDiemRates(0, referenceLookupPageSize).map { it.toDto() }

    override fun findPerDiemRateById(id: Long): PerDiemRateDto? = perDiemRateService.findPerDiemRateById(id)?.toDto()

    override fun findDefaultPerDiemRate(): PerDiemRateDto? = perDiemRateService.findDefaultPerDiemRate()?.toDto()

    override fun findPerDiemRates(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<PerDiemRateDto> =
        perDiemRateService.findAllPerDiemRates(pageIndex, pageSize, filterText, sortField, sortDir).map { it.toDto() }

    override fun countPerDiemRates(filterText: String?): Long = perDiemRateService.countPerDiemRates(filterText)

    override fun countInactivePerDiemRates(filterText: String?): Long = perDiemRateService.countInactivePerDiemRates(filterText)
}
