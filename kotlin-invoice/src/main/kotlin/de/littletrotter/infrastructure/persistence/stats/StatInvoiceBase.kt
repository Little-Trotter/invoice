package de.littletrotter.infrastructure.persistence.stats

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Read-only JPA mapping of the
 * `v_stat_invoice_base` view (`V1__baseline_schema.sql`), the flat per-invoice base layer every
 * `v_stat_*` aggregation view groups from. Backs [de.littletrotter.application.invoice
 * .InvoiceStatisticsQueryServiceImpl.getStatusDistribution] directly and the text-search ranking
 * fallback (`invoiceRankingSource`) — the one case that needs row-level `invoice_number` search
 * instead of a pre-aggregated period view.
 *
 * `@Immutable`: this is a reporting view, never written through JPA — `em.persist`/`merge` must
 * never be called on it. `invoiceId` is the view's own natural key (`invoice.id`), the one column
 * here that is genuinely unique per row, unlike the aggregation views below which have no single
 * unique column and need a composite [de.littletrotter.infrastructure.persistence.stats.StatYearlyId]-style key
 * instead.
 *
 * `status`/`reverseCharge` stay plain `Int`/`Boolean` (no `@Convert`), matching exactly how
 * [de.littletrotter.application.invoice.InvoiceStatisticsQueryServiceImpl] bound/read them before
 * this rewrite (`filter.status.ordinal`, `InvoiceStatus.entries[...]`) — the view's `status` column
 * is the same raw smallint `invoice.status` already is, and keeping the mapping untyped here avoids
 * relying on `@Convert` translating correctly through Hibernate's Criteria/JPQL SUM/GROUP BY path,
 * which the migration's own comment already flags as unreliable for converters ("Amounts stay
 * NUMERIC ... bypasses the Hibernate MoneyConverter path").
 */
@Entity
@Immutable
@Table(name = "v_stat_invoice_base")
class StatInvoiceBase {

    @Id
    @Column(name = "invoice_id")
    var invoiceId: Long = 0

    @field:Size(max = 255)
    @Column(name = "invoice_number")
    var invoiceNumber: String = ""

    @Column(name = "status")
    var status: Int = 0

    @Column(name = "reverse_charge")
    var reverseCharge: Boolean = false

    /** money-currency-display R6 — `invoice.currency`'s own smallint (see [de.littletrotter.shared
     *  .Currency]'s KDoc on the project-wide integer-ordinal persistence convention), never
     *  `@Convert`ed here for the same reason `status` isn't (see this class's own KDoc). */
    @Column(name = "currency")
    var currency: Int = 0

    @Column(name = "net_amount")
    var netAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "vat_amount")
    var vatAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "gross_amount")
    var grossAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "invoice_date")
    var invoiceDate: LocalDate? = null

    @Column(name = "year")
    var year: Int = 0

    @Column(name = "month")
    var month: Int = 0

    @Column(name = "day")
    var day: Int = 0

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
