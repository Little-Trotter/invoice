package de.littletrotter.application.invoice

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * COV — [StatsSqlSupport.asBigDecimal] had zero direct coverage before this AP (0/6 branches).
 * It funnels every statistics/ranking total's raw JPA tuple value on its way to a DTO —
 * [Money], [BigDecimal], a plain [Number] (a SUM/AVG's typical JDBC return type), and the
 * `null`-on-zero-rows fallback.
 */
class StatsSqlSupportTest {

    @Test
    fun `asBigDecimal liest den Betrag aus einem Money-Wert`() {
        assertEquals(BigDecimal("42.50"), StatsSqlSupport.asBigDecimal(Money.of("42.50")))
    }

    @Test
    fun `asBigDecimal reicht ein BigDecimal unveraendert durch`() {
        val value = BigDecimal("19.99")
        assertEquals(value, StatsSqlSupport.asBigDecimal(value))
    }

    @Test
    fun `asBigDecimal wandelt eine sonstige Number ueber ihre String-Darstellung um`() {
        assertEquals(BigDecimal("5"), StatsSqlSupport.asBigDecimal(5L))
        assertEquals(BigDecimal("3.14"), StatsSqlSupport.asBigDecimal(3.14))
    }

    @Test
    fun `asBigDecimal faellt fuer null oder einen unerwarteten Typ auf ZERO zurueck`() {
        assertEquals(BigDecimal.ZERO, StatsSqlSupport.asBigDecimal(null))
        assertEquals(BigDecimal.ZERO, StatsSqlSupport.asBigDecimal("not-a-number"))
    }
}
