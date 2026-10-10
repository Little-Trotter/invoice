package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.Money
import de.littletrotter.shared.quote.QuoteDashboardView
import de.littletrotter.shared.quote.QuoteKpisView
import de.littletrotter.shared.quote.QuoteQuarterView
import de.littletrotter.shared.quote.QuoteRankingRowView
import de.littletrotter.shared.quote.QuoteStatusCountsView
import de.littletrotter.shared.quote.QuoteTrendPointView
import de.littletrotter.shared.quote.QuoteWireCodec
import de.littletrotter.shared.tax.CustomerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The statistics adapters over a real HTTP round trip: the selection goes out as query parameters,
 * the answer is encoded with the contract codec the offer component uses and read back through the
 * anti-corruption translation.
 */
@DisplayName("Offer statistics adapters (round trip over HTTP)")
class HttpQuoteStatisticsDashboardAdapterIT {

    private val stub = QuoteStubServer()
    private val api = QuoteStubServer.client(stub.baseUrl)

    @AfterEach
    fun tearDown() = stub.close()

    private val view = QuoteDashboardView(
        availableYears = listOf(2026, 2025),
        kpis = QuoteKpisView(Money.of("90.00"), 3, Money.of("30.00"), Money.of("5.00"), 2, BigDecimal("2.5")),
        kpisPreviousYear = QuoteKpisView(Money.ZERO, 0, null, Money.ZERO, 0, null),
        statusCounts = QuoteStatusCountsView(draft = 1, review = 1, sent = 3, cancelled = 0),
        quarters = listOf(QuoteQuarterView(2, Money.of("90.00"), 3)),
        trend = listOf(QuoteTrendPointView(LocalDate.of(2026, 5, 1), Money.of("90.00"), 3)),
    )

    @Test
    fun `the dashboard goes out with the selection and comes back translated`() {
        stub.respond("/quote/api/quotes/statistics/dashboard", 200, QuoteWireCodec.encode(view))

        val dashboard = HttpQuoteStatisticsDashboardAdapter(api).dashboard(
            QuoteStatisticsSelection(2026, quarter = 2, status = QuoteStatus.SENT, customerType = CustomerType.BUSINESS),
        )

        val request = stub.last()
        assertEquals("GET", request.method)
        assertEquals("year=2026&quarter=2&status=SENT&customerType=BUSINESS", request.query)
        assertEquals(listOf(2026, 2025), dashboard.availableYears)
        assertEquals(Money.of("90.00"), dashboard.kpis.sentVolume)
        assertEquals(BigDecimal("2.5"), dashboard.kpis.avgDaysToSend)
        assertNull(dashboard.kpisPreviousYear.averageSent)
        assertEquals(3, dashboard.statusCounts.sent)
        assertEquals(2, dashboard.statusCounts.run { draft + review })
        assertEquals(2, dashboard.quarters.single().quarter)
        assertEquals(LocalDate.of(2026, 5, 1), dashboard.trend.single().day)
    }

    @Test
    fun `the ranking goes out with kind and limit and comes back translated`() {
        stub.respond(
            "/quote/api/quotes/statistics/ranking",
            200,
            QuoteWireCodec.encode(listOf(QuoteRankingRowView("c-1", "Acme GmbH", 3, Money.of("90.00")))),
        )

        val rows = HttpQuoteRankingAdapter(api).ranking(QuoteRankingKind.CUSTOMER, QuoteStatisticsSelection(2026, month = 5), 10)

        assertEquals("year=2026&month=5&kind=CUSTOMER&limit=10", stub.last().query)
        assertEquals("Acme GmbH", rows.single().name)
        assertEquals(Money.of("90.00"), rows.single().net)
    }

    @Test
    fun `an unreadable answer surfaces as a service failure`() {
        stub.respond("/quote/api/quotes/statistics/dashboard", 200, "{\"availableYears\":\"nope\"}")

        assertThrows(QuoteServiceException::class.java) {
            HttpQuoteStatisticsDashboardAdapter(api).dashboard(QuoteStatisticsSelection(2026))
        }
    }
}
