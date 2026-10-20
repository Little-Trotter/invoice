package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.port.inbound.InvoiceListPage
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.domain.invoice.InvoiceStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [InvoiceListQueryPort] on top of the overview/statistics read services. */
@ApplicationScoped
class InvoiceListQueries @Inject constructor(
    private val overviewQueryService: InvoiceOverviewQueryService,
    private val statisticsQuery: InvoiceStatisticsQueryPort,
    private val invoiceReadRepository: InvoiceReadRepository,
) : InvoiceListQueryPort {

    override fun findPage(filter: InvoiceExportFilter, page: InvoiceListPage): List<InvoiceOverviewDto> =
        overviewQueryService.findOverview(page.pageIndex, page.pageSize, filter.filterText, filter.status, page.sort, filter.toDimensions())

    override fun count(filter: InvoiceExportFilter): Long =
        overviewQueryService.countOverview(filter.filterText, filter.status, filter.toDimensions())

    override fun countByStatus(filter: InvoiceExportFilter): Map<InvoiceStatus, Long> =
        overviewQueryService.countOverviewGroupedByStatus(
            filter.filterText,
            filter.validationStatus,
            filter.reverseCharge,
            filter.recipientId,
            filter.senderProfileId,
            filter.dateFrom,
            filter.dateTo,
        )

    override fun availableYears(senderProfileId: Long?, recipientId: Long?): List<Int> =
        statisticsQuery.getAvailableYears(StatisticsFilter(senderId = senderProfileId, recipientId = recipientId))

    override fun exists(invoiceId: Long): Boolean = invoiceReadRepository.findInvoiceDetailDto(invoiceId) != null

    private fun InvoiceExportFilter.toDimensions() = InvoiceOverviewDimensions(
        reverseCharge = reverseCharge,
        validationStatus = validationStatus,
        recipientId = recipientId,
        senderProfileId = senderProfileId,
        dateFrom = dateFrom,
        dateTo = dateTo,
    )
}
