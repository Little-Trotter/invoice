package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.ReconciliationStrategy
import de.littletrotter.domain.shared.reconcileOwnedChildren

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
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
import de.littletrotter.domain.masterdata.converters.LegalFormConverter
import jakarta.validation.constraints.NotBlank
import java.time.LocalDate
import jakarta.validation.constraints.Size
import de.littletrotter.domain.shared.AuditableEntity
import de.littletrotter.domain.shared.ShortHash
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.LocalizedTextPurpose
import jakarta.persistence.CascadeType
import jakarta.persistence.OneToOne

@Entity
@Table(name = "sender_profile")
class SenderProfile : AuditableEntity(), AddressOwner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @field:NotBlank
    @field:Size(max = 255)
    var name: String = ""

    var taxNumber: TaxNumber? = null

    var vatId: VatId? = null
    var iban: Iban? = null

    var bic: Bic? = null

    @field:Size(max = 255)
    var bankName: String? = null

    /**
     * The seller's own free-text notice box on the invoice ("HINWEIS"), per language.
     * **Not** the mandatory footer line (`buildFooterCol1..3`), which the renderer builds from
     * name/address/register data and never reads this group. `null` = no note, which is normal —
     * no empty group is ever created (see [setInvoiceNote]).
     *
     * `@OneToOne` rather than `@OneToMany`/`@ManyToOne`: exactly one owner per group, the owner
     * holds the foreign key ([de.littletrotter.domain.reference.LocalizedTextGroup]'s own KDoc —
     * "owned by exactly one master-data record ... never the other way round"). `cascade = ALL` +
     * `orphanRemoval = true` is what deletes the group row itself once [setInvoiceNote] removes
     * its last entry, or when this profile is deleted — `localized_text_group` has no `ON DELETE
     * CASCADE` back to `sender_profile` (that direction cannot exist, the FK points the other
     * way), so without this Hibernate-level cascade a deleted profile would leave an orphan group.
     */
    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_note_group_id")
    var invoiceNote: LocalizedTextGroup? = null

    /**
     * The resolved note text for [language] (a document language code, e.g. `"de"`/`"en"`);
     * see [LocalizedTextGroup.resolvedFor].
     */
    fun invoiceNoteFor(language: String): String? = invoiceNote?.resolvedFor(language)

    /**
     * Sets (or clears) [language]'s entry of [invoiceNote]. A blank [value] removes the
     * entry; once the last entry is removed, [invoiceNote] itself is cleared to `null` — no empty
     * group, no blank row (`localized_text`'s own `chk_localized_text_value_not_blank` CHECK would
     * reject a blank row anyway, but this keeps a would-be violation from ever being built).
     */
    fun setInvoiceNote(language: Language, value: String?) {
        invoiceNote = withLanguageEntry(invoiceNote, LocalizedTextPurpose.SELLER_INVOICE_NOTE, language, value)
    }

    /**
     * Notice for private customers on craft-trade invoices, per language. Same ownership and
     * orphan-removal rules as [invoiceNote]; `null` = no own text, the language-file default
     * applies.
     */
    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "consumer_note_group_id")
    var consumerNote: LocalizedTextGroup? = null

    /** Whether new private-customer invoices of this sender carry the notice at all. */
    @Column(name = "consumer_note_enabled", nullable = false)
    var consumerNoteEnabled: Boolean = true

    /**
     * The sender's own consumer-notice text for exactly [language], or `null` if none is
     * maintained for it; no fallback to another language, so the caller can use that language's
     * default wording instead.
     */
    fun consumerNoteFor(language: String): String? = consumerNote?.exactFor(language)

    /** Sets (or clears) [language]'s entry of [consumerNote]; same rules as [setInvoiceNote]. */
    fun setConsumerNote(language: Language, value: String?) {
        consumerNote = withLanguageEntry(consumerNote, LocalizedTextPurpose.SELLER_CONSUMER_NOTE, language, value)
    }

    /**
     * Returns the group to store after setting [language]'s entry to [value]: a blank value
     * removes the entry, the last removal yields `null` (no empty group), a first value creates
     * the group for [purpose].
     */
    private fun withLanguageEntry(
        group: LocalizedTextGroup?,
        purpose: LocalizedTextPurpose,
        language: Language,
        value: String?,
    ): LocalizedTextGroup? {
        val text = value?.trim()?.ifEmpty { null }
        if (text == null) {
            group?.entries?.removeIf { it.language?.id == language.id }
            return if (group?.entries?.isEmpty() == true) null else group
        }
        val target = group ?: LocalizedTextGroup().also { it.purpose = purpose }
        val existing = target.entries.firstOrNull { it.language?.id == language.id }
        if (existing != null) {
            existing.value = text
        } else {
            target.addEntry(language, text)
        }
        return target
    }

    @Transient
    var globalId: String? = null

    // The @Size(max = 10) this field carried before was itself wrong: the DB column
    // (`invoice_prefix VARCHAR(20)`, V1__baseline_schema.sql) is 20 wide, not 10. Caught by
    // EntityColumnSizeConsistencyIT reading the real column width instead of trusting this
    // annotation — exactly the drift that IT exists to prevent.
    @field:NotBlank
    @field:Size(max = 20)
    @Column(length = 20, nullable = false)
    var invoicePrefix: String = "IP-"

    // ── N3-14: identifiers (short hash + seller number) independently settable ──

    /** Technical handle; never semantic, never changes. */
    @field:Size(max = 10)
    @Column(name = "short_hash", length = 10, unique = true)
    var shortHash: String? = null

    /** Seller number from SELLER range. */
    @field:Size(max = 60)
    @Column(name = "seller_number")
    var sellerNumber: String? = null

    /** Seller number prefix (separate column for reporting). */
    @field:Size(max = 30)
    @Column(name = "seller_number_prefix")
    var sellerNumberPrefix: String? = null

    /** Seller number counter (as number, not text pattern). */
    @Column(name = "seller_number_sequence")
    var sellerNumberSequence: Long? = null

    var isDefault: Boolean = false

    @OneToMany(mappedBy = "senderProfile", cascade = [CascadeType.ALL], orphanRemoval = true)
    override var addresses: MutableList<Address> = mutableListOf()

    override fun ownsAddress(address: Address): Boolean = address.isOwnedBySender(this)
    override fun createDefaultBillingAddress(): Address = Address.forSender(this, Address.AddressType.BILLING, "", "", "", Country.DE, true)
    override fun ownerLabel(): String = "sender"

    fun markAsDefault() { isDefault = true }
    fun revokeDefault() { isDefault = false }

    fun validate() {
        if (name.isBlank()) throw InvoiceValidationException(InvoiceValidationError.SENDER_NAME_BLANK, message = "Absendername darf nicht leer sein.")
        if (invoicePrefix.isBlank()) throw InvoiceValidationException(InvoiceValidationError.SENDER_INVOICE_PREFIX_BLANK, message = "Invoice prefix must not be blank.")
        if (!InvoicePrefixRule.isValid(invoicePrefix)) throw InvoiceValidationException(InvoiceValidationError.SENDER_INVOICE_PREFIX_BLANK, message = "Invoice prefix must be 1-20 letters, digits or '-' and start with a letter or digit: $invoicePrefix")
        if (email?.value.isNullOrBlank()) throw InvoiceValidationException(InvoiceValidationError.SENDER_EMAIL_MISSING, message = "E-Mail des Anbieters muss gesetzt sein.")
        if (taxNumber == null) throw InvoiceValidationException(InvoiceValidationError.SENDER_TAX_NUMBER_MISSING, message = "Steuernummer des Anbieters muss gesetzt sein.")
        if (vatId == null) throw InvoiceValidationException(InvoiceValidationError.SENDER_VAT_ID_MISSING, message = "USt-IdNr. des Anbieters muss gesetzt sein.")
        if (iban == null) throw InvoiceValidationException(InvoiceValidationError.SENDER_IBAN_MISSING, message = "IBAN des Anbieters muss gesetzt sein.")
        if (sellerNumber != null && sellerNumber!!.isBlank())
            throw InvoiceValidationException(InvoiceValidationError.SENDER_NUMBER_BLANK, message = "Anbieternummer darf nicht leer sein, wenn gesetzt.")
    }

    /** Auto-assign short hash if missing; return true if assigned. */
    fun ensureShortHash(): Boolean {
        if (shortHash == null) {
            shortHash = ShortHash.random()
            return true
        }
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SenderProfile) return false
        if (id != null && other.id != null) return id == other.id
        return false
    }

    /**
     * IMP-5 — constant, not `id?.hashCode()`. Id-based hash is unstable across persist (id changes
     * from null→value in same transaction); entity would move to wrong bucket in HashSet/Map keys.
     * Constant hash is safe here since [equals] stays id-based.
     */
    override fun hashCode(): Int = SenderProfile::class.java.hashCode()

    override fun toString(): String = "SenderProfile(id=$id, name=$name)"

    @field:Size(max = 50)
    var phone: String? = null
    var email: Email? = null

    @field:Size(max = 255)
    var commercialRegisterNumber: String? = null

    @field:Size(max = 255)
    var managingDirector: String? = null

    @field:Size(max = 255)
    var courtOfJurisdiction: String? = null

    @field:Size(max = 255)
    var registerCourt: String? = null

    @field:Size(max = 255)
    var website: String? = null

    /** Sitz der Gesellschaft for the invoice footer. `null`/blank = the renderer prints
     *  the billing address's city instead ([showsCommercialRegister] does not gate this one —
     *  the seat line's own gate is "a commercial register number exists", applied by the renderer,
     *  not by this field's presence). */
    @field:Size(max = 255)
    var registeredSeat: String? = null

    // ── Firmierung: Rechtsform + Handwerkskammer ─────────────────────

    /** The seller's legal form ("Rechtsform"), captured on the "Firmierung" tab.
     *  `null` = "keine Angabe" (behavior unchanged from before this field existed). */
    @Convert(converter = LegalFormConverter::class)
    @Column(name = "legal_form")
    var legalForm: LegalForm? = null

    /** Whether this seller is registered with a Handwerkskammer (crafts chamber).
     *  Only meaningful together with [legalForm] == [LegalForm.SOLE_PROPRIETOR]; see
     *  [showsCommercialRegister]. */
    @Column(name = "crafts_chamber_registered", nullable = false)
    var craftsChamberRegistered: Boolean = false

    /**
     * Whether the invoice (PDF footer and e-invoice data) should show this seller's
     * commercial-register entry (Handelsregisternummer/Registergericht).
     *
     * A sole proprietor ([LegalForm.SOLE_PROPRIETOR]) who is registered with a Handwerkskammer
     * ([craftsChamberRegistered]) is not entered in the Handelsregister at all — Handwerker are
     * a craft trade, not a merchant under HGB §1 ff., so they have no Registergericht and no
     * HR number to begin with. Printing [registerCourt]/[commercialRegisterNumber] on such an
     * invoice would not merely be superfluous, it would be *wrong* data (a register entry that
     * does not exist). Every other combination — no legal form recorded, a legal form other than
     * sole proprietor, or a sole proprietor without a Handwerkskammer registration (who may well
     * be a registered merchant, e.g. an "eingetragener Kaufmann") — keeps showing the register
     * data exactly as before this rule existed.
     */
    fun showsCommercialRegister(): Boolean = !(legalForm == LegalForm.SOLE_PROPRIETOR && craftsChamberRegistered)

    // ── BT-29/BT-30/BT-34 ────────────────────────────────────────────────

    /** BT-29 — seller identifier. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "seller_identifier_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "seller_identifier_scheme_id")),
    )
    var sellerIdentifier: PartyIdentifier? = null

    /** Scheme for [commercialRegisterNumber] (BT-30). */
    @field:Size(max = 10)
    @Column(name = "legal_registration_scheme_id")
    var legalRegistrationSchemeId: String? = null

    /** BT-34 electronic seller address — do NOT confuse with [Recipient.routingId]/BT-10. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "value", column = Column(name = "electronic_address_value")),
        AttributeOverride(name = "schemeId", column = Column(name = "electronic_address_scheme_id")),
    )
    var electronicAddress: ElectronicAddress? = null

    /** This sender's own default payment term, adopted when a new invoice is created
     *  (`InvoiceLifecycleService.create()`). `null` = falls back to the global
     *  `PaymentTerm.isDefault` record — no forced backfill for existing profiles. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_payment_term_id")
    var defaultPaymentTerm: PaymentTerm? = null

    /**
     * The one editable-field copy the save path uses to move a detached edit onto
     * the managed entity (Hibernate dirty-tracking instead of em.merge with a possibly stale
     * @Version). MIRROR CONTRACT: every field the Anbieter form can write
     * (`SenderProfileFormData.applyTo`) must be copied here, or an accepted
     * edit is silently dropped on save — exactly the historical bug this method replaces (six
     * fields were once missing from the hand-maintained list in `SellerProfileService.save`).
     * `SenderProfileFormDataTest` pins the parity of the two field sets.
     *
     * [invoiceNote] is deliberately **not** part of this mirror, the same shape as
     * [additionalVatRegistrations]: a `LocalizedTextGroup` needs its own create/update/delete
     * handling ([setInvoiceNote]'s blank-removes-entry rule), not a plain field copy — see
     * `SellerProfileService.save`. [defaultPaymentTerm] likewise stays out of it:
     * `SenderProfileFormData` only ever holds the read-model row (`PaymentTermDto`), never the
     * relation itself, so `SellerProfileService.save` resolves and overwrites it from the
     * submitted id after this copy runs, the same as the two fields above.
     */
    fun copyEditableFieldsFrom(other: SenderProfile) {
        name = other.name
        taxNumber = other.taxNumber
        vatId = other.vatId
        iban = other.iban
        bic = other.bic
        bankName = other.bankName
        invoicePrefix = other.invoicePrefix
        phone = other.phone
        email = other.email
        managingDirector = other.managingDirector
        courtOfJurisdiction = other.courtOfJurisdiction
        registerCourt = other.registerCourt
        website = other.website
        registeredSeat = other.registeredSeat
        commercialRegisterNumber = other.commercialRegisterNumber
        sellerIdentifier = other.sellerIdentifier
        legalRegistrationSchemeId = other.legalRegistrationSchemeId
        electronicAddress = other.electronicAddress
        legalForm = other.legalForm
        craftsChamberRegistered = other.craftsChamberRegistered
        if (other.isDefault) markAsDefault() else revokeDefault()
    }

    /** 0..n additional, non-German VAT registrations (curated country set,
     *  [Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES]). Like [addresses], the table stays
     *  empty until a seller actually adds a registration via the maintenance UI. `EAGER` (unlike
     *  [addresses]) because the invoice-header UI and
     *  `InvoiceLifecycleService.updateHeader` both need this list off a `SenderProfile` that
     *  wasn't specially reloaded for editing (unlike [de.littletrotter.application.masterdata
     *  .SellerProfileService.findByIdForEdit]'s explicit-reload path for the maintenance form) —
     *  a bounded, at-most-4-row collection, so the eager join has no meaningful cost. */
    @OneToMany(mappedBy = "senderProfile", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    private var additionalVatRegistrationRows: MutableList<SellerVatRegistration> = mutableListOf()

    val additionalVatRegistrations: List<SellerVatRegistration>
        get() = java.util.Collections.unmodifiableList(additionalVatRegistrationRows)

    /** Replaces the full set of additional VAT registrations. The maintenance UI always submits
     *  the whole edited list rather than incremental add/remove operations, so this mirrors that
     *  shape instead of exposing separate add/remove methods. Match by country to retain managed
     *  row identities: delete-and-reinsert can violate the unique profile/country constraint
     *  because Hibernate inserts new rows before deleting collection orphans. */
    fun updateVatRegistrations(commands: List<SellerVatRegistrationCommand>, today: LocalDate) {
        val incoming = commands.map { SellerVatRegistration.of(this, it.country, it.vatId, today) }
        reconcileOwnedChildren(
            current = additionalVatRegistrationRows,
            proposed = incoming,
            strategy = ReconciliationStrategy(
                key = { it.country },
                validate = { SellerVatRegistration.assertUniqueCountries(it) },
                create = { SellerVatRegistration.of(this, it.country, it.vatId!!, today) },
                update = { retained, candidate -> retained.vatId = candidate.vatId },
            ),
        )
    }

    @Version
    var version: Long? = 0
}
