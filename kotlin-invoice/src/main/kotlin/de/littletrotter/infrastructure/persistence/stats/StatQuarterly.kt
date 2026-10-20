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

/** Read-only mapping of `v_stat_quarterly`. See [StatYearly]'s KDoc; identical
 *  shape, one extra `quarter` id component. */
@Entity
@Immutable
@IdClass(StatQuarterlyId::class)
@Table(name = "v_stat_quarterly")
class StatQuarterly {

    @Id
    @Column(name = "year")
    var year: Int = 0

    @Id
    @Column(name = "quarter")
    var quarter: Int = 0

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

    /** money-currency-display R6 — see [de.littletrotter.infrastructure.persistence.stats.StatYearly
     *  .currency]'s KDoc. */
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

data class StatQuarterlyId(

    val year: Int = 0,
    val quarter: Int = 0,
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
