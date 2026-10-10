package de.littletrotter.infrastructure.persistence.stats

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable
import java.math.BigDecimal

/**
 * Read-only mapping of `v_stat_item_base` (`V1__baseline_schema.sql`), the flat
 * per-item base layer `v_stat_item_yearly/quarterly/monthly` group from. Backs
 * [de.littletrotter.application.invoice.InvoiceStatisticsQueryServiceImpl]'s article-ranking
 * text-search fallback (`itemRankingSource`). See [StatInvoiceBase]'s KDoc for the `@Immutable`/
 * plain-`Int` status rationale, which applies here unchanged.
 */
@Entity
@Immutable
@Table(name = "v_stat_item_base")
class StatItemBase {

    @Id
    @Column(name = "item_id")
    var itemId: Long = 0

    @Column(name = "description")
    var description: String = ""

    @Column(name = "net_amount")
    var netAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "quantity")
    var quantity: BigDecimal = BigDecimal.ZERO

    @Column(name = "invoice_id")
    var invoiceId: Long = 0

    @field:Size(max = 255)
    @Column(name = "invoice_number")
    var invoiceNumber: String = ""

    @Column(name = "status")
    var status: Int = 0

    @Column(name = "reverse_charge")
    var reverseCharge: Boolean = false

    /** money-currency-display R6 — see [StatInvoiceBase.currency]'s KDoc. */
    @Column(name = "currency")
    var currency: Int = 0

    @Column(name = "year")
    var year: Int = 0

    @Column(name = "month")
    var month: Int = 0

    @Column(name = "quarter")
    var quarter: Int = 0

    @Column(name = "sender_id")
    var senderId: Long? = null

    @field:Size(max = 255)
    @Column(name = "sender_name")
    var senderName: String? = null

    @Column(name = "recipient_id")
    var recipientId: Long? = null

    @field:Size(max = 255)
    @Column(name = "recipient_name")
    var recipientName: String? = null
}
