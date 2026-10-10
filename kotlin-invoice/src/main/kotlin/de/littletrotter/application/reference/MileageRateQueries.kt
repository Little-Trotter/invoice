package de.littletrotter.application.reference

import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [MileageRateQueryPort] on top of [MileageRateService]. */
@ApplicationScoped
class MileageRateQueries @Inject constructor(
    private val mileageRateService: MileageRateService,
) : MileageRateQueryPort {

    override fun findMileageRateById(id: Long): MileageRateDto? = mileageRateService.findMileageRateById(id)?.toDto()

    override fun findMileageRates(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<MileageRateDto> =
        mileageRateService.findAllMileageRates(pageIndex, pageSize, filterText, sortField, sortDir).map { it.toDto() }

    override fun countMileageRates(filterText: String?): Long = mileageRateService.countMileageRates(filterText)

    override fun countInactiveMileageRates(filterText: String?): Long = mileageRateService.countInactiveMileageRates(filterText)
}
