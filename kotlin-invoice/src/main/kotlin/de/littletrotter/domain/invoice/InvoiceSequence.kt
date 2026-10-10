package de.littletrotter.domain.invoice

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "invoice_sequence")
class InvoiceSequence : de.littletrotter.domain.shared.NamedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var senderProfileId: Long = 0
    var year: Int = 0
    var month: Int = 0
    var lastValue: Long = 0
}
