
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
import jakarta.persistence.CascadeType
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
import de.littletrotter.domain.reference.RecipientB2bFieldValue
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
    protected set

    @Column
    var customerNumber: CustomerNumber? = null
    protected set

    // ── N3-09: identifiers (short hash + customer number parts) independently settable ──

    /** Technical handle; never semantic, never changes. */
    @field:Size(max = 10)
    @Column(name = "short_hash", length = 10, unique = true)
    var shortHash: String? = null
    protected set

    /** Customer number prefix (separate column for reporting). */
    @field:Size(max = 30)
    @Column(name = "customer_number_prefix")
    var customerNumberPrefix: String? = null
    protected set

    /** Customer number counter (as number, not text pattern). */
    @Column(name = "customer_number_sequence")
    var customerNumberSequence: Long? = null
    protected set

    @field:NotBlank
    @field:Size(max = 255)
    var name: String = ""
    protected set

    var vatId: VatId? = null
    protected set

    /** BT-10 — default buyer reference (for German authorities, often the Leitweg-ID).
     *  Column name `routing_id` kept for historical compat (property is `routingId`). */
    @field:Size(max = 50)
    var routingId: String? = null
    protected set

    var email: Email? = null
    protected set

    @field:Size(max = 50)
    var phone: String? = null
    protected set

    @field:Size(max = 255)
    var contactPerson: String? = null
    protected set

    @Transient
    var globalId: String? = null
    protected set

    @Convert(converter = IbanConverter::class)
    @Column(name = "iban")
    var iban: Iban? = null
    protected set

    // ── P1 — BT-46/BT-47/BT-49, customer type, VAT-ID verification ────────────

    /** BT-46 — buyer identifier. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "buyer_identifier_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "buyer_identifier_scheme_id")),
    )
    var buyerIdentifier: PartyIdentifier? = null
    protected set

    /** BT-47 — legal buyer registration identifier. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "legal_registration_identifier")),
        AttributeOverride(name = "schemeId", column = Column(name = "legal_registration_scheme_id")),
    )
    var legalRegistrationIdentifier: LegalRegistrationIdentifier? = null
    protected set

    /** BT-49 — buyer electronic address (NOT [routingId]/BT-10 — distinct per Compliance Matrix). */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "electronic_address_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "electronic_address_scheme_id")),
    )
    var electronicAddress: ElectronicAddress? = null
    protected set

    @Convert(converter = CustomerTypeConverter::class)
    @Column(name = "customer_type")
    var customerType: CustomerType? = null
    protected set

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
    protected set

    /**
     * The one flag that decides whether every document for this recipient (invoice, quote, later
     * reports) is rendered German (`de`) or international (`en`). Preselected from the billing
     * country when the recipient is created, freely editable afterwards. An invoice copies it
     * once at creation and freezes it, so changing it later never alters existing invoices.
     */
    @field:Size(max = 5)
    @Column(name = "document_language", nullable = false, length = 5)
    var documentLanguage: String = DocumentLanguage.DE.code
    protected set

    @Convert(converter = ReverseChargePreferenceConverter::class)
    @Column(name = "reverse_charge_preference", nullable = false)
    var reverseChargePreference: ReverseChargePreference = ReverseChargePreference.ASK
    protected set

    @Convert(converter = VatIdVerificationStatusConverter::class)
    @Column(name = "vat_id_verification_status")
    var vatIdVerificationStatus: VatIdVerificationStatus? = null
    protected set

    @Column(name = "vat_id_verified_at")
    var vatIdVerifiedAt: OffsetDateTime? = null
    protected set

    /** Free-text detail for the manual "USt-IdNr. gilt als validiert" checkbox on
     *  [de.littletrotter.ui.view.masterdata.RecipientView] — e.g. how/when the customer's VAT id
     *  was confirmed outside the automated VIES check (qualified confirmation request reference,
     *  date, contact). Always user-entered free text, unlike [vatIdVerificationStatus]/
     *  [vatIdVerifiedAt], which are set programmatically — hence a plain `String`, not an enum. */
    @field:Size(max = 255)
    @Column(name = "vat_id_verification_note", length = 255)
    var vatIdVerificationNote: String? = null
    protected set

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
    protected set

    @OneToMany(mappedBy = "recipient", cascade = [CascadeType.ALL], orphanRemoval = true)
    // A var, not a val: bytecode enhancement skips final fields, and a lazily loaded owner
    // (association proxy) only initializes its collections through the instrumented field.
    private var addresses: MutableList<Address> = mutableListOf()

    /** Per-customer values of the B2B profile fields placed on the recipient. The join column is
     *  written through each row's own `recipientId`, so it is read-only here. */
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "recipient_id", insertable = false, updatable = false)
    private var b2bFieldValues: MutableList<RecipientB2bFieldValue> = mutableListOf()

    /** Read-only view of [b2bFieldValues] for callers outside this class. */
    val b2bFieldValueList: List<RecipientB2bFieldValue> get() = b2bFieldValues

    /**
     * Stores [value] for the profile field [profileFieldId], or drops the stored value when
     * [value] is blank. Returns the stored row, `null` when none is left.
     */
    fun recordB2bFieldValue(profileFieldId: Long, value: String?): RecipientB2bFieldValue? {
        val existing = b2bFieldValues.firstOrNull { it.profileFieldId == profileFieldId }
        if (value.isNullOrBlank()) {
            existing?.let { b2bFieldValues.remove(it) }
            return null
        }
        val target = existing ?: RecipientB2bFieldValue.forRecipient(
            checkNotNull(id) { "A recipient must be saved before it can hold field values" },
            profileFieldId,
        )
        target.changeValue(value)
        target.validate()
        if (existing == null) b2bFieldValues.add(target)
        return target
    }

    override val addressList: List<Address> get() = addresses

    override val addressBook: AddressBook get() = AddressBook(this, addresses)

    override fun ownsAddress(address: Address): Boolean = address.isOwnedByRecipient(this)
    override fun createDefaultBillingAddress(): Address = Address.forRecipient(this, Address.AddressType.BILLING, "", "", "", Country.DE, true)
    override fun ownerLabel(): String = "recipient"

    val deliveryAddress: Address? get() = deliveryAddresses.firstOrNull { it.isDefault } ?: deliveryAddresses.firstOrNull()

    @Version
    var version: Long? = 0
    protected set

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

    /** Writes every field the customer form edits, in one step. The billing address, the
     *  VAT-id verification outcome ([recordVatIdVerification]) and the electronic address
     *  ([changeElectronicAddress]) have their own intents. */
    fun update(details: RecipientDetails) {
        customerNumber = details.customerNumber
        name = details.name
        vatId = details.vatId
        vatIdVerificationNote = details.vatIdVerificationNote
        iban = details.iban
        routingId = details.routingId
        email = details.email
        phone = details.phone
        contactPerson = details.contactPerson
        customerType = details.customerType
        invoiceFormat = details.invoiceFormat
        documentLanguage = details.documentLanguage
        reverseChargePreference = details.reverseChargePreference
        buyerIdentifier = details.buyerIdentifier
        legalRegistrationIdentifier = details.legalRegistrationIdentifier
        b2bProfile = details.b2bProfile
    }

    /** Stores the outcome of a VAT-id check; `verifiedAt` is `null` for a reset to unverified. */
    fun recordVatIdVerification(status: VatIdVerificationStatus, verifiedAt: OffsetDateTime?) {
        vatIdVerificationStatus = status
        vatIdVerifiedAt = verifiedAt
    }

    /** Stores the allocated customer number together with the range parts it was built from. */
    fun assignCustomerNumber(prefix: String?, sequence: Long?, number: CustomerNumber?) {
        customerNumberPrefix = prefix
        customerNumberSequence = sequence
        customerNumber = number
    }

    /** Drops the customer number so a fresh one can be allocated; prefix and counter are
     *  overwritten by that allocation. */
    fun clearCustomerNumber() {
        customerNumber = null
    }

    /** BT-49: replaces (or with `null` clears) the buyer's electronic address. */
    fun changeElectronicAddress(address: ElectronicAddress?) {
        electronicAddress = address
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

    override fun toString(): String = "Recipient(id=$id, name=$name, customerNumber=$customerNumber)"
}
