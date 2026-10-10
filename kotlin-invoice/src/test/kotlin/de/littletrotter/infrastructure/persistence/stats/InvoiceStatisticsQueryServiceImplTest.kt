package de.littletrotter.infrastructure.persistence.stats

import de.littletrotter.infrastructure.persistence.stats.InvoiceStatisticsQueryServiceImpl.Companion.longOrZero
import de.littletrotter.infrastructure.persistence.stats.InvoiceStatisticsQueryServiceImpl.Companion.nameOrUnbekannt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * -Folge (PO-Entscheid 29.08.2026, Core-Härtung) — [InvoiceStatisticsQueryServiceImpl]
 * no longer builds native SQL strings (Criteria over the
 * `v_stat_*` view entities in `de.littletrotter.infrastructure.persistence.stats` instead), so there is no
 * query string left for a mocked `EntityManager`/`Query` to capture — the former tests here that
 * asserted on captured SQL fragments (`getAvailableYears`' COALESCE clause, the ranking-source
 * view-name fallback, the field-stripping `contains(...)` assertions) are superseded by real
 * `@QuarkusTest` coverage against Postgres: [de.littletrotter.application.InvoiceStatisticsQueryServiceIT]
 * and — in depth, one dimension at a time plus the combined/attack-shaped cases —
 * [de.littletrotter.application.StatisticsFilterIT]. Test relocation, not loss of coverage
 * (see `feedback-coverage-80-80.md`). moved this test alongside the impl it pins, out of
 * `application` into `infrastructure.persistence.stats`.
 *
 * What remains genuinely unit-testable without a live `EntityManager` is the pure row-mapping
 * fallback logic every ranking total funnels through, pinned directly here.
 */
class InvoiceStatisticsQueryServiceImplTest {

    @Test
    fun `nameOrUnbekannt falls back for a null name but passes a real one through`() {
        assertEquals("Unbekannt", nameOrUnbekannt(null))
        assertEquals("Acme GmbH", nameOrUnbekannt("Acme GmbH"))
    }

    @Test
    fun `longOrZero falls back for a null SUM but converts a real numeric result`() {
        assertEquals(0L, longOrZero(null))
        assertEquals(5L, longOrZero(5L))
        assertEquals(5L, longOrZero(5))
        assertEquals(5L, longOrZero(BigDecimal("5")))
    }
}
