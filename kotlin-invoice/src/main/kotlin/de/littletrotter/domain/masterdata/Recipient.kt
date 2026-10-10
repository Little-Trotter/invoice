
package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.IbanConverter
import de.littletrotter.domain.value.VatId
import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.Version
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.masterdata.converters.CustomerTypeConverter
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.domain.shared.converters.InvoiceFormatConverter
import de.littletrotter.domain.masterdata.converters.VatIdVerificationStatusConverter
import de.littletrotter.domain.masterdata.converters.ReverseChargePreferenceConverter
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

@Entity
@Table(name = "recipient")
class Recipient : de.littletrotter.domain.shared.AuditableEntity(), AddressOwner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column
    var customerNumber: CustomerNumber? = null

    // ── N3-09: identifiers (short hash + customer number parts) independently settable ──

    /** Technical handle; never semantic, never changes. */
    @field:Size(max = 10)
    @Column(name = "short_hash", length = 10, unique = true)
    var shortHash: String? = null

    /** Customer number prefix (separate column for reporting). */
    @field:Size(max = 30)
    @Column(name = "customer_number_prefix")
    var customerNumberPrefix: String? = null

    /** Customer number counter (as number, not text pattern). */
    @Column(name = "customer_number_sequence")
    var customerNumberSequence: Long? = null

    @field:NotBlank
    @field:Size(max = 255)
    var name: String = ""

    var vatId: VatId? = null

    /** BT-10 — default buyer reference (for German authorities, often the Leitweg-ID).
     *  Column name `routing_id` kept for historical compat (property is `routingId`). */
    @field:Size(max = 50)
    var routingId: String? = null

    var email: Email? = null

    @field:Size(max = 50)
    var phone: String? = null

    @field:Size(max = 255)
    var contactPerson: String? = null

    @Transient
    var globalId: String? = null

    @Convert(converter = IbanConverter::class)
    @Column(name = "iban")
    var iban: Iban? = null

    // ── P1 — BT-46/BT-47/BT-49, customer type, VAT-ID verification ────────────

    /** BT-46 — buyer identifier. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "buyer_identifier_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "buyer_identifier_scheme_id")),
    )
    var buyerIdentifier: PartyIdentifier? = null

    /** BT-47 — legal buyer registration identifier. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "legal_registration_identifier")),
        AttributeOverride(name = "schemeId", column = Column(name = "legal_registration_scheme_id")),
    )
    var legalRegistrationIdentifier: LegalRegistrationIdentifier? = null

    /** BT-49 — buyer electronic address (NOT [routingId]/BT-10 — distinct per Compliance Matrix). */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "electronic_address_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "electronic_address_scheme_id")),
    )
    var electronicAddress: ElectronicAddress? = null

    @Convert(converter = CustomerTypeConverter::class)
    @Column(name = "customer_type")
    var customerType: CustomerType? = null

    /**
     * PO order 04.09.2026 — "German invoice or international invoice?" as a property of the
     * CUSTOMER: which document family this customer is billed with, and therefore which PDF
     * layout variant their invoices get.
     *
     * Preselected from the billing country in [de.littletrotter.ui.view.masterdata.RecipientView]
     * — DE/AT/CH ([Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES]) get [InvoiceFormat.GERMAN] — via
     * [defaultInvoiceFormatFor], but never forced: a deliberate deviation survives every later
     * country edit (PO decision 04.09.2026).
     *
     * Deliberately NOT copied onto [de.littletrotter.domain.invoice.Invoice.invoiceFormat]: that
     * field is the legal marker for "invoiced under a foreign seller VAT registration" and the DB
     * CHECK `chk_invoice_format_requires_seller_vat_registration` ties INTERNATIONAL there to a
     * non-null `seller_vat_registration_country`. The template choice this field governs has no
     * such precondition — see [InvoiceFormat]'s own KDoc on why the two are separate decisions.
     */
    @Convert(converter = InvoiceFormatConverter::class)
    @Column(name = "invoice_format", nullable = false)
    var invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN

    /**
     * The one flag that decides whether every document for this recipient (invoice, quote, later
     * reports) is rendered German (`de`) or international (`en`). Preselected from the billing
     * country when the recipient is created, freely editable afterwards. An invoice copies it
     * once at creation and freezes it, so changing it later never alters existing invoices.
     */
    @field:Size(max = 5)
    @Column(name = "document_language", nullable = false, length = 5)
    var documentLanguage: String = DocumentLanguage.DE.code

    @Convert(converter = ReverseChargePreferenceConverter::class)
    @Column(name = "reverse_charge_preference", nullable = false)
    var reverseChargePreference: ReverseChargePreference = ReverseChargePreference.ASK

    @Convert(converter = VatIdVerificationStatusConverter::class)
    @Column(name = "vat_id_verification_status")
    var vatIdVerificationStatus: VatIdVerificationStatus? = null

    @Column(name = "vat_id_verified_at")
    var vatIdVerifiedAt: OffsetDateTime? = null

    /** Free-text detail for the manual "USt-IdNr. gilt als validiert" checkbox on
     *  [de.littletrotter.ui.view.masterdata.RecipientView] — e.g. how/when the customer's VAT id
     *  was confirmed outside the automated VIES check (qualified confirmation request reference,
     *  date, contact). Always user-entered free text, unlike [vatIdVerificationStatus]/
     *  [vatIdVerifiedAt], which are set programmatically — hence a plain `String`, not an enum. */
    @field:Size(max = 255)
    @Column(name = "vat_id_verification_note", length = 255)
    var vatIdVerificationNote: String? = null

    // ── Ardennes-X: French customer SIRET/NAF identifiers ──────────────────────

    /** SIRET (14 digits: 9-digit SIREN + 5-digit NIC). Edition-neutral (stored even in Community,
     *  stays empty without Ardennes-X bean). Format-checked only; no checksum/INPI validation. */
    @field:Size(max = 14)
    @Column(name = "siret", length = 14)
    var siret: String? = null

    /** NAF code (4 digits + 1 letter, e.g., `6201Z`). Recommended, not required; never blocking. */
    @field:Size(max = 5)
    @Column(name = "naf_code", length = 5)
    var nafCode: String? = null

    /**
     * The standing B2B field-set default for invoices billed to this
     * customer. `null` = no B2B profile, i.e. Community/no-B2B behavior for every invoice that
     * still inherits it. Moved here from [SenderProfile] (removed without replacement,
     * greenfield): which B2B catalog applies is a property of the customer relationship, not of
     * whichever seller happens to invoice them — a seller with several demanding customers can
     * serve each one's catalog without touching master data per invoice.
     *
     * Only a *default*: [de.littletrotter.domain.invoice.Invoice.b2bProfile] is the invoice's own,
     * frozen field — derived from this one when a recipient is (re-)assigned
     * ([de.littletrotter.application.invoice.InvoiceLifecycleService]), then editable and
     * snapshot-stable independent of later edits here.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "b2b_profile_id")
    var b2bProfile: de.littletrotter.domain.reference.B2bProfile? = null

    @OneToMany(mappedBy = "recipient", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true)
    override var addresses: MutableList<Address> = mutableListOf()

    override fun ownsAddress(address: Address): Boolean = address.isOwnedByRecipient(this)
    override fun createDefaultBillingAddress(): Address = Address.forRecipient(this, Address.AddressType.BILLING, "", "", "", Country.DE, true)
    override fun ownerLabel(): String = "recipient"

    val deliveryAddress: Address? get() = deliveryAddresses.firstOrNull { it.isDefault } ?: deliveryAddresses.firstOrNull()

    @Version
    var version: Long? = 0

    /**
     * VAT ID not universally required: [CustomerType.CONSUMER] can omit it. [CustomerType.BUSINESS]
     * still expects it (or null [customerType] defaults to old stricter rule for backward compat).
     * [iban] (BT-91) never required here: system bills by credit transfer (seller IBAN only).
     */
    fun validate() {
        if (customerNumber == null) throw InvoiceValidationException(InvoiceValidationError.RECIPIENT_CUSTOMER_NUMBER_BLANK, message = "Kundennummer darf nicht leer sein.")
        if (name.isBlank()) throw InvoiceValidationException(InvoiceValidationError.RECIPIENT_NAME_BLANK, message = "Empfängername darf nicht leer sein.")
        if (email?.value.isNullOrBlank()) throw InvoiceValidationException(InvoiceValidationError.RECIPIENT_EMAIL_MISSING, message = "E-Mail im Kundenprofil muss gesetzt sein.")
        if (customerType != CustomerType.CONSUMER && vatId == null) {
            throw InvoiceValidationException(InvoiceValidationError.RECIPIENT_VAT_ID_MISSING, message = "USt-IdNr. im Kundenprofil muss gesetzt sein.")
        }
    }

    /** Assigns a short hash on save if none exists yet. */
    fun ensureShortHash(): Boolean {
        if (shortHash == null) {
            shortHash = de.littletrotter.domain.shared.ShortHash.random()
            return true
        }
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Recipient) return false
        if (id != null && other.id != null) return id == other.id
        return false
    }

    /** IMP-5 — constant instead of `id?.hashCode() ?: System.identityHashCode(this)`.
     *  Full rationale at the equivalent spot in [SenderProfile]: the old value changed as soon as
     *  a transient instance got its `id` on save, and an entity already inserted into a `HashSet`
     *  could no longer be found afterward. */
    override fun hashCode(): Int = Recipient::class.java.hashCode()

    companion object {
        /** SIRET: 14 digits (9-digit SIREN + 5-digit NIC). Format check only,
         *  deliberately without a Luhn-like checksum: a formally valid but mistyped SIRET must
         *  not be blocked by a stricter check that the legal basis does not require. */
        val SIRET_PATTERN: Regex = Regex("^\\d{14}$")

        /** NAF code: 4 digits + 1 letter, e.g. `6201Z`. */
        val NAF_PATTERN: Regex = Regex("^\\d{4}[A-Za-z]$")

        fun isValidSiret(value: String): Boolean = SIRET_PATTERN.matches(value)
        fun isValidNafCode(value: String): Boolean = NAF_PATTERN.matches(value)
    }

    override fun toString(): String = "Recipient(id=$id, name=$name, customerNumber=$customerNumber)"
}
