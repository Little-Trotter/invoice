package de.littletrotter.application.invoice

import java.math.BigDecimal

/**
 * Z2 — small scalar-conversion helper shared by [InvoiceOverviewQueryServiceImpl] (its
 * `getTotals` JPQL projection) and [de.littletrotter.infrastructure.persistence.stats
 * .InvoiceStatisticsQueryServiceImpl] (every native `v_stat_*` projection). `internal`, not a
 * public port, just a spot to avoid duplicating this conversion in both classes — module-visible
 * so the adapter in `infrastructure.persistence.stats` can still reach it.
 */
internal object StatsSqlSupport {
    fun asBigDecimal(value: Any?): BigDecimal = when (value) {
        is de.littletrotter.shared.Money -> value.amount
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        else -> BigDecimal.ZERO
    }
}
