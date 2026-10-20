package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.converters.PartyRoleConverter
import de.littletrotter.domain.masterdata.ElectronicAddress
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

enum class PartyRole { SELLER, BUYER }

/**
 * P1 — an immutable snapshot of one party's (seller or buyer) identity as it stood at the
 * moment the invoice was created, samt Scheme-IDs. Once written, nothing about this row
 * should ever change (Compliance-Matrix Checkpoint 4: "Finalisierte Rechnungen lesen
 * ausschließlich Snapshots, nie mutable Party-Referenzen") — a later edit to the referenced
 * [SenderProfile]/[Recipient] row must never alter an already-invoiced party's identity.
 *
 * One row per role per invoice (`uq_invoice_party_snapshot` in V020), not a wide set of
 * columns on `invoice` itself — `Invoice.kt` is outside P1's Schreibbereich, so this entity
 * only carries the FK column; wiring an `Invoice.partySnapshots` back-reference and calling
 * [SenderProfile.snapshotAsSeller]/[Recipient.snapshotAsBuyer] at the right point in the
 * invoice lifecycle is the hook P1 hands to P2/R6, the same way R1 handed
 * `InvoiceItem.snapshotTaxRule()` to R4.
 */
@Entity
@Table(name = "invoice_party_snapshot")
@EntityListeners(CreationStampListener::class)
class InvoicePartySnapshot : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    var invoice: Invoice? = null
        protected set

    @Convert(converter = PartyRoleConverter::class)
    @Column(name = "party_role", nullable = false)
    var partyRole: PartyRole = PartyRole.SELLER
        protected set

    @field:Size(max = 255)
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "identifier_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "identifier_scheme_id")),
    )
    var identifier: PartyIdentifier? = null
        protected set

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "legal_registration_identifier")),
        AttributeOverride(name = "schemeId", column = Column(name = "legal_registration_scheme_id")),
    )
    var legalRegistration: LegalRegistrationIdentifier? = null
        protected set

    var vatId: VatId? = null
        protected set

    @Column(name = "tax_number")
    var taxNumber: TaxNumber? = null
        protected set

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "electronic_address_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "electronic_address_scheme_id")),
    )
    var electronicAddress: ElectronicAddress? = null
        protected set

    /** The seller's tax number in the foreign registration the invoice is billed under, frozen when
     *  the invoice is issued; only the SELLER row carries it. */
    @Column(name = "registration_tax_number", length = ForeignTaxNumber.MAX_LENGTH)
    var registrationTaxNumber: ForeignTaxNumber? = null
        protected set

    /** BT-10 — nur auf der BUYER-Zeile befüllt (`chk_invoice_party_snapshot_buyer_reference_role`). */
    @field:Size(max = 100)
    @Column(name = "buyer_reference")
    var buyerReference: String? = null
        protected set

    @Column(name = "created_at")
    var createdAt: OffsetDateTime? = null
        protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    /** Overwrites every identity field of this row with [other]'s; role, invoice and creation stamp stay. */
    fun copyIdentityFrom(other: InvoicePartySnapshot) {
        name = other.name
        identifier = other.identifier
        legalRegistration = other.legalRegistration
        vatId = other.vatId
        taxNumber = other.taxNumber
        registrationTaxNumber = other.registrationTaxNumber
        electronicAddress = other.electronicAddress
        buyerReference = other.buyerReference
    }

    fun validate() {
        if (name.isBlank()) throw InvalidParameterException("Party-Snapshot: Name darf nicht leer sein.")
        if (buyerReference != null && partyRole != PartyRole.BUYER)
            throw InvalidParameterException("Party-Snapshot: buyerReference (BT-10) ist nur auf der BUYER-Zeile zulässig.")
    }

    /** Freezes [taxNumber] (or clears it with `null`) as the tax number of the foreign registration. */
    fun freezeRegistrationTaxNumber(taxNumber: ForeignTaxNumber?) {
        if (partyRole != PartyRole.SELLER && taxNumber != null) {
            throw InvalidParameterException("Party-Snapshot: Steuernummer der Registrierung ist nur auf der SELLER-Zeile zulässig.")
        }
        registrationTaxNumber = taxNumber
    }

    /** Wires this row to its owning [invoice]; the invoice calls it when it takes the row over. */
    internal fun attachTo(invoice: Invoice) {
        this.invoice = invoice
    }

    /** Overrides the name and VAT id with what the source document stated: an invoice drafted from
     *  an accepted offer names the buyer as the offer did, not as master data reads today. */
    fun correctIdentity(name: String, vatId: VatId?) {
        this.name = name
        if (vatId != null) this.vatId = vatId
    }

    companion object {
        /** P1 — freezes [sender]'s document-relevant identity onto an invoice. */
        fun forSeller(sender: SenderProfile): InvoicePartySnapshot = InvoicePartySnapshot().apply {
            partyRole = PartyRole.SELLER
            name = sender.name
            identifier = sender.sellerIdentifier
            legalRegistration = sender.commercialRegisterNumber?.let { reg ->
                sender.legalRegistrationSchemeId?.let { scheme ->
                    LegalRegistrationIdentifier.of(reg, scheme)
                }
            }
            vatId = sender.vatId
            taxNumber = sender.taxNumber
            electronicAddress = sender.electronicAddress
        }

        /** P1 — freezes [recipient]'s document-relevant identity onto an invoice. [buyerReference]
         *  is BT-10 as it stands for THIS invoice (the recipient's [Recipient.routingId] default,
         *  possibly overridden on the invoice itself — see P2's "Standard-Leitweg-ID überschreibbar"). */
        fun forBuyer(recipient: Recipient, buyerReference: String? = recipient.routingId): InvoicePartySnapshot =
            InvoicePartySnapshot().apply {
                partyRole = PartyRole.BUYER
                name = recipient.name
                identifier = recipient.buyerIdentifier
                legalRegistration = recipient.legalRegistrationIdentifier
                vatId = recipient.vatId
                electronicAddress = recipient.electronicAddress
                this.buyerReference = buyerReference
            }
    }
}
