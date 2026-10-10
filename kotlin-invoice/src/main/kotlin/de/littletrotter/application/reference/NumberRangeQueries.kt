package de.littletrotter.application.reference

import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.application.masterdata.MasterDataNumberService
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [NumberRangeQueryPort] on top of the number-range/counter repositories and [MasterDataNumberService]. */
@ApplicationScoped
class NumberRangeQueries @Inject constructor(
    private val numberRangeRepository: NumberRangeRepository,
    private val numberCounterRepository: NumberCounterRepository,
    private val masterDataNumberService: MasterDataNumberService,
) : NumberRangeQueryPort {

    override fun findNumberRanges(pageIndex: Int, pageSize: Int, filterText: String?): List<NumberRangeDto> =
        numberRangeRepository.findAll(pageIndex, pageSize, filterText).map { it.toDto() }

    override fun countNumberRanges(filterText: String?): Long = numberRangeRepository.count(filterText)

    override fun countInactiveNumberRanges(filterText: String?): Long = numberRangeRepository.countInactive(filterText)

    override fun findNumberRangeById(id: Long): NumberRangeDto? = numberRangeRepository.findById(id)?.toDto()

    override fun numberRangeCurrentValue(subject: NumberRangeSubject): Long? = numberCounterRepository.currentValue(subject)

    override fun proposeArticleNumber(category: InvoiceItemCategory): String? = masterDataNumberService.proposeArticle(category)

    override fun proposeSellerNumber(sellerName: String): String? = masterDataNumberService.proposeSeller(sellerName)
}
