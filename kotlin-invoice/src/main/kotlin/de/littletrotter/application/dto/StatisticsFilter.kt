package de.littletrotter.application.dto

import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus

/**
 * Parameter object for the statistics query dimensions (sender/recipient/year/status/
 * reverseCharge/free-text/month/quarter) that used to be threaded individually through every
 * method of [InvoiceStatisticsQueryPort]. Every field is optional; each query method's KDoc
 * states which of these fields it actually reads — the rest are silently ignored by that method.
 *
 * Z2 — introduced to remove the eight-parameter data clump that was repeated across the
 * statistics service interface, its implementation, the facades, and the statistics view.
 */
data class StatisticsFilter(
    val senderId: Long? = null,
    val recipientId: Long? = null,
    val year: Int? = null,
    val status: InvoiceStatus? = null,
    val reverseCharge: Boolean? = null,
    val filterText: String? = null,
    val month: Int? = null,
    val quarter: Int? = null,
) {
    companion object {
        val EMPTY = StatisticsFilter()
    }
}
