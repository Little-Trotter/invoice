package de.littletrotter.infrastructure.persistence.stats

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.math.BigDecimal

/**
 * Read-only mapping of `v_stat_yearly` (`V1__baseline_schema.sql`), already grouped
 * by `year, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge` at the
 * SQL level (see the migration's own "two-level aggregation" comment). That exact tuple is this
 * view's natural key — no single column is unique per row — so it becomes the [StatYearlyId]
 * composite id below rather than a synthetic surrogate: [de.littletrotter.application.invoice
 * .InvoiceStatisticsQueryServiceImpl] never calls `em.find` on it (`@Immutable`, Criteria
 * `SELECT`/`GROUP BY` only), so the nullable `senderId`/`senderName`/`recipientId`/`recipientName`
 * components (an invoice's seller/recipient FK may be unset) only ever need to round-trip through a
 * query result, never back through an identity lookup.
 */
@Entity
@Immutable
@IdClass(StatYearlyId::class)
@Table(name = "v_stat_yearly")
class StatYearly {

    @Id
    @Column(name = "year")
    var year: Int = 0

    @Id
    @Column(name = "sender_id")
    var senderId: Long? = null

    @Id
    @field:Size(max = 255)
    @Column(name = "sender_name")
    var senderName: String? = null

    @Id
    @Column(name = "recipient_id")
    var recipientId: Long? = null

    @Id
    @field:Size(max = 255)
    @Column(name = "recipient_name")
    var recipientName: String? = null

    @Id
    @Column(name = "status")
    var status: Int = 0

    @Id
    @Column(name = "reverse_charge")
    var reverseCharge: Boolean = false

    /** money-currency-display R6 — part of the grouping key: a sum can only carry the one
     *  currency every row it summed actually shares, so currency joins the `GROUP BY` tuple
     *  exactly like `status`/`reverseCharge` above. See [de.littletrotter.infrastructure.persistence.stats
     *  .StatInvoiceBase.currency]'s KDoc for the plain-`Int` rationale. */
    @Id
    @Column(name = "currency")
    var currency: Int = 0

    @Column(name = "net_amount")
    var netAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "vat_amount")
    var vatAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "gross_amount")
    var grossAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "invoice_count")
    var invoiceCount: Long = 0
}

/** [StatYearly]'s `@IdClass` — see that class's KDoc. `@JvmOverloads` gives Hibernate the no-arg
 *  constructor `@IdClass` requires, without giving up the `data class` `equals`/`hashCode`. */
data class StatYearlyId(

    val year: Int = 0,
    val senderId: Long? = null,
    val senderName: String? = null,
    val recipientId: Long? = null,
    val recipientName: String? = null,
    val status: Int = 0,
    val reverseCharge: Boolean = false,
    val currency: Int = 0,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }

    /** JPA/@IdClass needs a no-arg constructor; an explicit one instead of @JvmOverloads so the
     *  generated intermediate arities do not exist at all (domain gate). */
    constructor() : this(year = 0)
}
