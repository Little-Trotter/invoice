package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.domain.reference.NumberRangeSubject
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The shipped invoice range (gapless, reset every year, full date in the number) across a year
 * boundary against real PostgreSQL: each year is its own counter row starting at the start value,
 * every number is journaled under its year, and sealing the old year covers exactly the old
 * year's numbers while the new year keeps counting.
 *
 * The years lie far in the future so no other test, which draws invoice numbers for the real
 * date from the same shared range, can ever share these periods.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class NumberRangeYearRolloverIT {

    @Inject
    lateinit var numberRangeService: NumberRangeService

    @Inject
    lateinit var em: EntityManager

    private fun allocateFor(date: LocalDate) = QuarkusTransaction.requiringNew().call {
        numberRangeService.allocate(NumberRangeSubject.INVOICE, NumberContext(date, sellerId = 1, sellerPrefix = "YR"))
    }

    private fun rows(sql: String): List<String> = QuarkusTransaction.requiringNew().call {
        em.createNativeQuery(sql).resultList.map { it.toString() }
    }

    @Test
    fun `the new year starts its own counter at the start value and the old year can be sealed on its own`() {
        val lastButOne = allocateFor(LocalDate.of(2096, 12, 31))
        val last = allocateFor(LocalDate.of(2096, 12, 31))
        val first = allocateFor(LocalDate.of(2097, 1, 1))
        val second = allocateFor(LocalDate.of(2097, 1, 1))

        assertEquals(
            listOf("YR-2096-12-31-00001", "YR-2096-12-31-00002", "YR-2097-01-01-00001", "YR-2097-01-01-00002"),
            listOf(lastButOne, last, first, second).map { it.value },
        )
        assertEquals(
            listOf("2096 2", "2097 2"),
            rows(
                "SELECT c.period_key || ' ' || c.last_value FROM number_counter c JOIN number_range r ON r.id = c.number_range_id " +
                    "WHERE r.code = 'INVOICE_GAPLESS' AND c.period_key IN ('2096', '2097') ORDER BY c.period_key",
            ),
            "one counter row per year, each holding the last number it handed out",
        )
        assertEquals(
            listOf("2096 YR-2096-12-31-00001", "2096 YR-2096-12-31-00002", "2097 YR-2097-01-01-00001", "2097 YR-2097-01-01-00002"),
            rows("SELECT period_key || ' ' || number FROM number_journal WHERE number LIKE 'YR-209%' ORDER BY seq"),
        )

        val seal = QuarkusTransaction.requiringNew().call { numberRangeService.sealPeriod(NumberRangeSubject.INVOICE, "2096") }
        assertEquals(2L, seal.sealedCount)
        assertEquals("YR-2096-12-31-00001", seal.sealedFirstNumber)
        assertEquals("YR-2096-12-31-00002", seal.sealedLastNumber)

        assertEquals("YR-2097-01-02-00003", allocateFor(LocalDate.of(2097, 1, 2)).value, "sealing the old year leaves the new year's sequence untouched")
    }
}
