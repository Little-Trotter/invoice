package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.port.inbound.InvoiceListPage
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

class InvoiceListQueriesTest {

    private val overviewQueryService: InvoiceOverviewQueryService = mock()
    private val statisticsQuery: InvoiceStatisticsQueryPort = mock()
    private val invoiceReadRepository: InvoiceReadRepository = mock()
    private val queries = InvoiceListQueries(overviewQueryService, statisticsQuery, invoiceReadRepository)

    private val filter = InvoiceExportFilter(
        filterText = "acme",
        status = InvoiceStatus.SENT,
        reverseCharge = true,
        validationStatus = ValidationStatus.VALID,
        recipientId = 1L,
        senderProfileId = 2L,
        dateFrom = LocalDate.of(2026, 1, 1),
        dateTo = LocalDate.of(2026, 12, 31),
    )
    private val dimensions = InvoiceOverviewDimensions(
        reverseCharge = true,
        validationStatus = ValidationStatus.VALID,
        recipientId = 1L,
        senderProfileId = 2L,
        dateFrom = LocalDate.of(2026, 1, 1),
        dateTo = LocalDate.of(2026, 12, 31),
    )

    @Test
    fun `findPage forwards page, sort and every filter dimension`() {
        val sort = InvoiceOverviewSort(InvoiceOverviewSortField.entries.first(), SortDirection.entries.first())
        val rows = listOf(mock<InvoiceOverviewDto>())
        whenever(overviewQueryService.findOverview(3, 25, "acme", InvoiceStatus.SENT, sort, dimensions)).thenReturn(rows)

        assertEquals(rows, queries.findPage(filter, InvoiceListPage(3, 25, sort)))
    }

    @Test
    fun `count forwards text, status and every filter dimension`() {
        whenever(overviewQueryService.countOverview("acme", InvoiceStatus.SENT, dimensions)).thenReturn(7L)

        assertEquals(7L, queries.count(filter))
    }

    @Test
    fun `countByStatus forwards every dimension but never the status itself`() {
        val counts = mapOf(InvoiceStatus.SENT to 4L)
        whenever(
            overviewQueryService.countOverviewGroupedByStatus(
                "acme", ValidationStatus.VALID, true, 1L, 2L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            ),
        ).thenReturn(counts)

        assertEquals(counts, queries.countByStatus(filter))
    }

    @Test
    fun `availableYears narrows the statistics filter to seller and recipient`() {
        whenever(statisticsQuery.getAvailableYears(StatisticsFilter(senderId = 2L, recipientId = 1L))).thenReturn(listOf(2026, 2025))

        assertEquals(listOf(2026, 2025), queries.availableYears(senderProfileId = 2L, recipientId = 1L))
    }

    @Test
    fun `exists is true only while the invoice can still be read`() {
        whenever(invoiceReadRepository.findInvoiceDetailDto(5L)).thenReturn(mock<InvoiceDetailDto>())

        assertTrue(queries.exists(5L))
        assertFalse(queries.exists(6L))
    }
}
