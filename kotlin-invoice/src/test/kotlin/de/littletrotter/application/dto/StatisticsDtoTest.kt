package de.littletrotter.application.dto

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatisticsDtoTest {

    @Test
    fun `boundary - SenderTotals defaults currency to EUR when omitted`() {
        val row = SenderTotals(
            senderId = 1L,
            senderName = "Sender A",
            grossAmount = Money.of("100.00"),
            invoiceCount = 2L,
        )
        assertEquals(Currency.EUR, row.currency)
    }

    @Test
    fun `boundary - ArticleTotals defaults currency to EUR when omitted`() {
        val row = ArticleTotals(
            description = "Screws",
            netAmount = Money.of("50.00"),
            itemCount = 4L,
            avgPrice = Money.of("12.50"),
        )
        assertEquals(Currency.EUR, row.currency)
    }
}
