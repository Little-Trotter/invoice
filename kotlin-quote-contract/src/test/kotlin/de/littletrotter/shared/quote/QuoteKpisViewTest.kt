package de.littletrotter.shared.quote

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QuoteKpisViewTest {

    private val zero = Money(BigDecimal.ZERO)

    @Test
    fun `while nothing was sent the average and the days to sending are absent`() {
        val kpis = QuoteKpisView(sentVolume = zero, sentCount = 0, inProgressAmount = Money(BigDecimal("120.00")), inProgressCount = 2)

        assertNull(kpis.averageSent)
        assertNull(kpis.avgDaysToSend)
        assertEquals(2, kpis.inProgressCount)
    }

    @Test
    fun `figures given explicitly are kept`() {
        val kpis = QuoteKpisView(
            sentVolume = Money(BigDecimal("300.00")),
            sentCount = 3,
            averageSent = Money(BigDecimal("100.00")),
            inProgressAmount = zero,
            inProgressCount = 0,
            avgDaysToSend = BigDecimal("1.5"),
        )

        assertEquals(Money(BigDecimal("100.00")), kpis.averageSent)
        assertEquals(BigDecimal("1.5"), kpis.avgDaysToSend)
    }
}
