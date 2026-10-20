package de.littletrotter.domain.invoice

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceItemAdded
import de.littletrotter.domain.event.InvoiceItemRemoved
import de.littletrotter.domain.event.InvoiceStatusChanged
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceValidationStatusTransitionException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.converters.InvoiceFormatConverter
import de.littletrotter.domain.invoice.converters.InvoiceStatusConverter
import de.littletrotter.domain.invoice.converters.InvoiceTypeCodeConverter
import de.littletrotter.shared.Currency
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.shared.sum
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.shared.converters.CurrencyConverter
import de.littletrotter.domain.shared.converters.DocumentLanguageConverter
import de.littletrotter.domain.shared.converters.ValidationStatusConverter
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
import jakarta.persistence.ManyToOne
import jakarta.persistence.ManyToMany
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@Entity
@Table(name = "invoice")
class Invoice : de.littletrotter.domain.shared.AuditableEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column
    @Convert(converter = InvoiceNumberConverter::class)
    var invoiceNumber: InvoiceNumber = InvoiceNumber.UNASSIGNED
        protected set

    /**
     * Prefix and counter kept separate from the composed [invoiceNumber]: reporting and a later
     * gap check work on [invoiceNumberSequence] as a number, not on a text pattern. Both are set
     * **once**, at assignment (`InvoiceLifecycleService.transition` on the transition to `ISSUED`)
     * and never changed afterward — exactly like [invoiceNumber] itself. `null` as long as the
     * invoice is a draft.
     */
    @field:Size(max = 30)
    @Column(name = "invoice_number_prefix")
    var invoiceNumberPrefix: String? = null
        protected set

    @Column(name = "invoice_number_sequence")
    var invoiceNumberSequence: Long? = null
        protected set

    /**
     * The currency this invoice is billed in. One currency per invoice — all items and
     * totals share it. Persisted as ordinal via [CurrencyConverter]; the `V001` baseline declares
     * the column as INTEGER. [de.littletrotter.shared.Money] itself remains currency-agnostic; this is the
     * single source of truth that flows into `ram:InvoiceCurrencyCode` during ZUGFeRD export.
     */
    @Convert(converter = CurrencyConverter::class)
    @Column(name = "currency", nullable = false)
    var currency: Currency = Currency.EUR
        protected set

    @Convert(converter = InvoiceStatusConverter::class)
    @Column(nullable = false)
    final var status: InvoiceStatus = InvoiceStatus.DRAFT
        private set

    @ManyToOne(fetch = FetchType.LAZY)
    var senderProfile: SenderProfile? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var recipient: Recipient? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var billingAddress: Address? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var deliveryAddress: Address? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var selectedSeller: SenderProfile? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    var paymentTerm: PaymentTerm? = null
        protected set

    /**
     * The frozen payment-term values, set once at the `ISSUED` transition via
     * [freezePaymentTermSnapshot]. See [InvoicePaymentTermSnapshot]'s own KDoc. `null` for a draft
     * or for an invoice finalized before this snapshot existed; readers fall back to the live
     * [paymentTerm] association in that case.
     */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "name", column = Column(name = "payment_term_snapshot_name", length = 150)),
        AttributeOverride(name = "dueInDays", column = Column(name = "payment_term_snapshot_due_in_days")),
        AttributeOverride(name = "discountPercent", column = Column(name = "payment_term_snapshot_discount_percent", precision = 5, scale = 2)),
        AttributeOverride(name = "discountDays", column = Column(name = "payment_term_snapshot_discount_days")),
    )
    var paymentTermSnapshot: InvoicePaymentTermSnapshot? = null
        protected set

    /**
     * This invoice's own, **frozen** B2B field-set choice: which
     * [de.littletrotter.domain.reference.B2bProfile] [de.littletrotter.application.invoice
     * .B2bProfileRuleSet] resolves against, and what
     * [de.littletrotter.application.reference.B2bProfileFieldDefinitionService.missingMandatorySellerFields]
     * checks at finalization. `null` = Community/no-B2B behavior — the wire (`b2bValues`) stays
     * empty.
     *
     * Derived from [de.littletrotter.domain.masterdata.Recipient.b2bProfile] whenever [recipient]
     * is (re-)assigned (`InvoiceLifecycleService.create`/`updateHeader`) — a recipient *change*
     * always overwrites whatever was chosen here before, even a manual override: changing the
     * customer is the bigger event, and silently keeping the previous customer's catalog would be
     * the surprising outcome. A manual override is possible afterwards, any time while
     * the invoice is still a draft. Like every other compliance-relevant snapshot, this is never
     * re-derived once [status] leaves `DRAFT` — a later edit to the recipient's own default must
     * not move an already-finalized invoice's field set.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "b2b_profile_id")
    var b2bProfile: de.littletrotter.domain.reference.B2bProfile? = null
        protected set

    /**
     * The BT-10/BT-13/BT-15/BT-17 reference fields plus the two app-level references
     * (project number, cost center), grouped into one value object. See [InvoiceReferences]'s own
     * KDoc for the BT-10/BT-13/BT-15 disambiguation table and the migration notes
     * (`purchase_order_reference` in `V001__baseline.sql`, BT-13's catalog binding in
     * `seed/V903__seed_b2b.sql`).
     * `@AttributeOverride` keeps every column name exactly as it was before this grouping — a
     * zero-schema-change refactor.
     */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "buyerReference", column = Column(name = "buyer_reference", length = 100)),
        AttributeOverride(name = "purchaseOrderReference", column = Column(name = "purchase_order_reference", length = 100)),
        AttributeOverride(name = "orderReference", column = Column(name = "order_reference", length = 100)),
        AttributeOverride(name = "orderReferenceDate", column = Column(name = "order_reference_date")),
        AttributeOverride(name = "offerReference", column = Column(name = "offer_reference", length = 100)),
        AttributeOverride(name = "offerReferenceDate", column = Column(name = "offer_reference_date")),
        AttributeOverride(name = "projectNumber", column = Column(name = "project_number", length = 100)),
        AttributeOverride(name = "costCenter", column = Column(name = "cost_center", length = 100)),
    )
    private var referenceFields: InvoiceReferences? = null

    /**
     * The persisted field is
     * NULLABLE and this accessor is what hands out a non-null value. Not a style choice: it is the
     * fix for a defect that reached production behaviour.
     *
     * All eight columns behind [InvoiceReferences] are individually nullable, so Hibernate applies
     * its "every column null ⇒ the composite itself is null" rule and hydrates the field with
     * `null` — which is the case for every plain draft. The previous shape declared the field
     * non-null and repaired it in a `@PostLoad` callback. But Hibernate takes its dirty-check
     * snapshot from the LOADED state, where the composite is still `null`, and `ComponentType`
     * treats "one side null, the other not" as a change without ever comparing contents. The
     * repair therefore made every freshly loaded draft dirty, and every flush wrote the whole row
     * back with identical values and a bumped `@Version`.
     *
     * Measured on a live stack 05.09.2026: invoice 649 climbed from version 0 to 5 while merely
     * being read on the way to a print job; `invoice_audit_journal` recorded those writes with
     * `detail = {}` (the trigger saw no changed column); and the job's own versioned UPDATE then
     * hit a row a reader had moved underneath it — three attempts, then the DLQ.
     * The invariant is pinned by `InvoiceCleanReadIT` — a test source, so it stays plain text:
     * a KDoc link from main cannot resolve into the test source set.
     *
     * Reading must therefore never assign. Assigning on read looked harmless ("one avoidable
     * write") but was not: the write stores eight NULLs, the next load hydrates `null` again, and
     * every reader (detail DTO, PDF preview, reload after a conflict) bumped the version once
     * more, so the next edit from the UI always failed its version check. For a plain draft the
     * accessor hands out an empty composite that attaches itself to this invoice on its first
     * write ([InvoiceReferences.attachOnFirstWrite]); until then the field stays `null` and the
     * dirty check has nothing to see.
     *
     * `hibernate.create_empty_composites.enabled`, which used to make Hibernate hydrate an empty
     * composite instead of `null`, does not exist in Hibernate 7.4 any more — verified in the
     * bundled jar before choosing this route.
     *
     * The backing field's name is the JPQL attribute name (field access, `@Id` sits on a field),
     * so queries address `i.referenceFields.buyerReference` — see
     * [de.littletrotter.infrastructure.persistence.InvoiceReadRepositoryImpl]. That is the one
     * place the split between stored field and accessor is visible, and it is why the field is
     * named for what it holds rather than something like `referencesOrNull`.
     */
    val references: InvoiceReferences
        get() = referenceFields ?: InvoiceReferences().attachOnFirstWrite { referenceFields = it }

    /**
     * Which of the seller's VAT registrations this invoice is billed under.
     * `null` (default) = the mandatory German [SenderProfile.vatId], today's behavior,
     * unchanged export/PDF path. A non-null value selects one of the seller's additional,
     * non-domestic registrations ([Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES]);
     * an artikelgebundene position then derives its MWST from
     * [de.littletrotter.domain.masterdata.Article.taxVariantFor] instead of the German
     * [de.littletrotter.shared.tax.TaxRuleResolver] path — a second, independent path that
     * leaves the German-seller path untouched and DE-seller-only.
     */
    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "seller_vat_registration_country")
    var sellerVatRegistrationCountry: Country? = null
        protected set

    /**
     * The legal invoice format (German vs. international text-block set), changeable
     * while [sellerVatRegistrationCountry] is non-null (the seller-registration path) or while
     * [reverseCharge] is the invoice's decision (a cross-border EU B2B Reverse-Charge
     * invoice has no seller VAT registration by construction, see
     * [de.littletrotter.domain.compliance.reverseChargeEligibility], but is exactly as valid a
     * candidate for an English document); any other state forces GERMAN. Enforced in
     * `InvoiceLifecycleService.updateHeader`, not here (this entity has no application-service
     * access). See [InvoiceFormat]'s own KDoc for the distinction from [documentLanguage] and the
     * XML export profile.
     */
    @Convert(converter = InvoiceFormatConverter::class)
    @Column(name = "invoice_format", nullable = false)
    var invoiceFormat: InvoiceFormat = InvoiceFormat.GERMAN
        protected set

    @Transient
    val languageId: String? = null

    /**
     * The document language ("de"/"en"), resolved once at creation
     * ([de.littletrotter.application.invoice.InvoiceLifecycleService.create]) and never
     * automatically re-derived afterward: a finalized invoice must render identically years
     * later even if the recipient's country or other master data changes in the meantime.
     * Distinct from [languageId], which is a per-export XML `LanguageID` override and stays
     * untouched.
     *
     * The allowed set is a real foreign key onto
     * [de.littletrotter.domain.reference.Language.code] (`fk_invoice_document_language`), not a
     * `CHECK` IN-list; the column itself is unchanged (still the human-readable code, not the
     * language's numeric id).
     */
    @Convert(converter = DocumentLanguageConverter::class)
    @Column(name = "document_language", length = 5, nullable = false)
    var documentLanguage: DocumentLanguage = DocumentLanguage.DE
        protected set

    @Transient
    val documentStatusReasonCode: String? = null

    // date invariants — every invoice carries an invoice date from the moment it
    // exists, not only at finalization; every creation path (InvoiceLifecycleService.create,
    // InvoiceCancellationService's credit-note construction, DevDataFixtures) sets this explicitly from
    // TimeSource.today(). A domain class may not read the clock itself
    // (TimeSourceArchitectureTest), so this no-arg-constructor placeholder is a fixed marker, never
    // `now()` — it is never observed on a real invoice.
    var invoiceDate: LocalDate = INVOICE_DATE_PLACEHOLDER
        protected set

    /** BT-73 — service period start (BG-14). */
    var servicePeriodFrom: LocalDate? = null
        protected set

    /** BT-74 — service period end (BG-14). */
    var servicePeriodTo: LocalDate? = null
        protected set

    /**
     * BT-72 — the actual delivery/performance date, its own field since V031. Before that,
     * export mapped BT-72 onto [servicePeriodTo] (BT-74), i.e. the two terms were structurally
     * the same. That fallback still exists and lives in
     * `MustangExportModelMapper`/`PdfRendererFactoryImpl`, not here: `null` means "no delivery
     * date of its own captured", not "same as the period end" — the difference matters for
     * [de.littletrotter.domain.compliance.PeriodConsistencyRules].
     */
    var deliveryDate: LocalDate? = null
        protected set

    var dueDate: LocalDate? = null
        protected set
    var paymentTermsDays: Int = DEFAULT_PAYMENT_TERMS_DAYS
        protected set

    /**
     * The free text, the editable seller note, the private-customer notice and the BT codes
     * already copied into the free text, grouped into one value object. See [InvoiceTexts]'s own
     * KDoc. Every column name is unchanged; JPQL addresses the fields as `i.texts.freeText`.
     */
    @Embedded
    val texts: InvoiceTexts = InvoiceTexts()

    @Convert(converter = InvoiceTypeCodeConverter::class)
    var typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE
        protected set

    /**
     * The invoice header's own, **leading** Reverse-Charge decision, set by the user at
     * the header (`InvoiceDetailViewOverview`'s toggle, backed by [de.littletrotter.application
     * .invoice.InvoiceLifecycleService.setReverseChargeDecision]). This is a semantic flip from
     * the field's earlier meaning: it used to be a column *derived* from the items
     * (`recalculateTotals` set it from [hasReverseChargeItems] on every save), read-only
     * everywhere else. Now it is the other way round — this decision is authoritative and
     * positions must follow it, not the reverse. No schema change: the column already existed and
     * historical data stays consistent (the old derivation always matched the items it was
     * derived from). [validateReverseChargeItemConsistency] (positions vs. this flag) and
     * [de.littletrotter.domain.compliance.assertReverseChargeRecipientEligibility] (this flag vs.
     * the recipient's own Reverse-Charge eligibility) are the two hard gates `→ ISSUED` now enforces
     * instead of the old, purely derivation-based invariant.
     */
    var reverseCharge: Boolean = false
        protected set

    /** BT-25 — number of the document this one cancels (BG-3). Set only on a credit note. */
    @Convert(converter = InvoiceNumberConverter::class)
    @Column(name = "original_invoice_number")
    var originalInvoiceNumber: InvoiceNumber? = null
        protected set

    /**
     * BT-26 — issue date of the preceding document (BG-3), its own field since V033.
     *
     * Copied from the original invoice at cancellation time, not looked up via [originalInvoiceNumber]
     * at export time: the credit note carries its own reference and therefore stays readable
     * even once the original is archived. `null` means "not captured" (every credit note
     * created before V033) — export then omits the element rather than guessing a date.
     */
    var originalInvoiceDate: LocalDate? = null
        protected set

    /**
     * The three monetary totals grouped into one value object. See [InvoiceTotals]'s own
     * KDoc. `@AttributeOverride` keeps every column name (`net_amount`/`vat_amount`/`gross_amount`)
     * exactly as it was before this grouping — a zero-schema-change refactor. [recalculateTotals]
     * remains the sole writer.
     */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "netAmount", column = Column(name = "net_amount", precision = 14, scale = 2)),
        AttributeOverride(name = "vatAmount", column = Column(name = "vat_amount", precision = 14, scale = 2)),
        AttributeOverride(name = "grossAmount", column = Column(name = "gross_amount", precision = 14, scale = 2)),
    )
    var totals: InvoiceTotals = InvoiceTotals()
        protected set

    // validation-status-truthful-pending.md — NOT_VALIDATED, not PENDING: a freshly created
    // invoice has never been near an e-invoice run. PENDING is only set once a generation job is
    // actually published (DocumentJobPublisher.publish).
    @Convert(converter = ValidationStatusConverter::class)
    @Column(nullable = false)
    var validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED
        protected set

    /**
     * Whether totals were last set by [recalculateTotals] itself rather than written around it
     * (the demo-data bulk SQL insert bakes net/vat/gross in directly). Read by
     * `InvoiceRepository.findAllForRecalculation` so the background recalculation job
     * ([de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService.performRecalculateTotals])
     * only touches invoices that actually need it, instead of sweeping the whole table on every
     * run.
     */
    @Column(name = "needs_recalculation", nullable = false)
    var needsRecalculation: Boolean = true
        protected set

    /**
     * **An event-fed read model, not a second truth.**
     *
     * The single source of truth for "was this document mailed, to whom, when, with which
     * attachments" is the mail component's own append-only journal
     * (`little-trotter-email`, its own database and bucket). These two columns hold only what the
     * invoicing context needs to *show* on the document and to filter on, projected from
     * [de.littletrotter.shared.mail.MailSendResultEvent] by
     * [de.littletrotter.application.mail.MailSendResultProjection]. Nothing in this repository
     * writes them from any other source, and nothing reads them as evidence — for that, follow the
     * mail journal.
     *
     * Deliberately a flag plus a timestamp, not a new
     * [InvoiceStatus]. `ISSUED` is a lifecycle decision the user makes; "an email went out" is an
     * observation, and folding the two would make the state machine depend on whether an optional
     * component is installed at all.
     *
     * Nullable, and `null` genuinely means "never observed": an instance that runs without the
     * component leaves every row `null` forever, and an instance that later removes the component
     * keeps the flags it already collected — they stay historically true either way.
     */
    @Column(name = "mail_sent")
    var mailSent: Boolean? = null
        protected set

    /** When the component recorded the terminal ISSUED transition (UTC). See
     *  [mailSent] for why these two columns are a read model and not a status. */
    @Column(name = "mail_sent_at")
    var mailSentAt: OffsetDateTime? = null
        protected set

    /**
     * The JPA-mapped field itself stays private and genuinely `MutableList`-typed, so
     * Hibernate's field-access strategy still installs its own persistent collection wrapper here
     * at hydration time (attribute name `items`, unchanged for `i.items`/`"items"` JPQL and
     * Criteria navigation), while [itemList] is the read-only public view and [addItem]/
     * [removeItem] the only mutation path — no unchecked cast needed on either side.
     */
    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private val items: MutableList<InvoiceItem> = mutableListOf()

    /** Read-only view of [items] for callers outside this class. */
    val itemList: List<InvoiceItem> get() = items

    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    private val llmAnalyses: MutableList<InvoiceLlmAnalysis> = mutableListOf()

    /** Read-only view of [llmAnalyses] for callers outside this class. */
    val llmAnalysisList: List<InvoiceLlmAnalysis> get() = llmAnalyses

    /** The analysis runs of this invoice, appended after finalization (no editability guard). */
    internal val llmAnalysisRows: GuardedRows<InvoiceLlmAnalysis> get() = GuardedRows(llmAnalyses) {}

    /** The archived artifacts (PDF, XML, archive package) of the generation jobs of this invoice. */
    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    private val documents: MutableList<InvoiceDocument> = mutableListOf()

    /** Read-only view of [documents] for callers outside this class. */
    val documentList: List<InvoiceDocument> get() = documents

    /** The archived artifacts of this invoice, registered after issuance (no editability guard). */
    internal val documentRows: GuardedRows<InvoiceDocument> get() = GuardedRows(documents) {}

    /** One row per [PartyRole], frozen at creation (and on an explicit "Stammdaten erneut
     *  übernehmen"), never auto-synced to a later [senderProfile]/[recipient] master-data edit.
     *  The JPA-mapped field stays private and genuinely `MutableList`-typed (attribute name
     *  `partySnapshots` unchanged); [partySnapshotList] is the read-only public view and
     *  [applyPartySnapshots] the only mutation path — no unchecked cast needed on either side. */
    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    private val partySnapshots: MutableList<InvoicePartySnapshot> = mutableListOf()

    /** Read-only view of [partySnapshots] for callers outside this class. */
    val partySnapshotList: List<InvoicePartySnapshot> get() = partySnapshots

    /** A4 — receipts attached to the invoice **as a whole** (purchase order confirmation,
     *  contract, timesheet, cover letter). Parallel to the per-item association on
     *  [InvoiceItem.receipts] via the additive `invoice_receipt` table. A receipt may be both —
     *  on the invoice AND on a line item. Writable only while the invoice is not
     *  `ISSUED`/`CANCELLED`/`VOIDED` (read-only guard in the service layer, not the UI). */
    @ManyToMany
    @JoinTable(
        name = "invoice_receipt",
        joinColumns = [JoinColumn(name = "invoice_id")],
        inverseJoinColumns = [JoinColumn(name = "receipt_id")],
    )
    private val receipts: MutableList<Receipt> = mutableListOf()

    /** Read-only view of [receipts] for callers outside this class. */
    val receiptList: List<Receipt> get() = receipts

    /** The receipts attached to the invoice as a whole; changes run the editability guard. */
    internal val receiptRows: GuardedRows<Receipt> get() = GuardedRows(receipts, ::assertEditable)

    /** The editability guard the positions of this invoice run before changing their own child rows. */
    internal val childEditGuard: () -> Unit get() = ::assertEditable

    @Version
    var version: Long? = 0
        protected set

    /** The accepted offer this invoice was drafted from; `null` for every other invoice. The
     *  unique column makes a repeated acceptance event find the existing draft instead of
     *  creating a second one. */
    @Column(name = "source_quote_id")
    var sourceQuoteId: java.util.UUID? = null
        protected set

    @Transient
    private val domainEvents: MutableList<DomainEvent> = mutableListOf()

    // ── Domain logic ──────────────────────────────────────────────────────────

    /**
     * [totals] needs no equivalent treatment: `net_amount`, `vat_amount` and `gross_amount` are
     * all NOT NULL in the schema, so Hibernate's null-composite rule never applies to it and the
     * field always arrives hydrated. The former `@PostLoad` repair covered both fields; it is
     * gone together with the defect it caused — see [references].
     */

    /**
     * Records the number this invoice is issued under, together with the prefix and counter it was
     * composed from. Allowed exactly **once**: a number that has been handed out is never changed or
     * reused, so a second assignment is a programming error, not a recoverable condition.
     */
    fun assignNumber(number: InvoiceNumber, prefix: String?, sequence: Long?) {
        check(!invoiceNumber.isAssigned()) { "Invoice number $invoiceNumber is already assigned and never changes." }
        invoiceNumber = number
        invoiceNumberPrefix = prefix
        invoiceNumberSequence = sequence
    }

    /**
     * Replaces the header dates, the service period, the payment terms in days, the currency, the
     * document format and the seller VAT registration with the values of [change]. The references
     * and texts have their own value objects; the parties go through [assignParties].
     */
    fun updateHeader(change: InvoiceHeaderChange) {
        assertEditable()
        applyHeader(change)
    }

    private fun applyHeader(change: InvoiceHeaderChange) {
        invoiceDate = change.invoiceDate
        servicePeriodFrom = change.servicePeriodFrom
        servicePeriodTo = change.servicePeriodTo
        deliveryDate = change.deliveryDate
        dueDate = change.dueDate
        paymentTermsDays = change.paymentTermsDays
        currency = change.currency
        invoiceFormat = change.invoiceFormat
        sellerVatRegistrationCountry = change.sellerVatRegistrationCountry
    }

    /**
     * Replaces the sender, the recipient, both addresses, the B2B profile, the payment term and the
     * document language with the values of [parties]. Does not touch the frozen party snapshots;
     * [applyPartySnapshots] is the separate step that refreshes those.
     */
    fun assignParties(parties: InvoiceParties) {
        assertEditable()
        applyParties(parties)
    }

    private fun applyParties(parties: InvoiceParties) {
        senderProfile = parties.senderProfile
        recipient = parties.recipient
        billingAddress = parties.billingAddress
        deliveryAddress = parties.deliveryAddress
        b2bProfile = parties.b2bProfile
        paymentTerm = parties.paymentTerm
        documentLanguage = DocumentLanguage.fromCode(parties.documentLanguage)
    }

    /** Records the header's leading Reverse-Charge decision; see [reverseCharge]. */
    fun decideReverseCharge(enabled: Boolean) {
        assertEditable()
        reverseCharge = enabled
    }

    /** Records that the mail component sent this invoice at [at]; see [mailSent]. */
    fun recordMailSent(at: OffsetDateTime) {
        mailSent = true
        mailSentAt = at
    }

    /** Links this draft to the accepted offer it was created from; an invoice is linked at most once. */
    fun linkToQuote(quoteId: java.util.UUID) {
        assertEditable()
        check(sourceQuoteId == null) { "Invoice is already linked to quote $sourceQuoteId." }
        sourceQuoteId = quoteId
    }

    /**
     * W7 — freezes [paymentTerm] onto [paymentTermSnapshot], called once at the
     * `ISSUED` transition. A no-op when [paymentTerm] is `null` (no payment term chosen at
     * all — nothing to freeze, readers fall back to [paymentTermsDays] as before) and a no-op
     * when a snapshot already exists (an already-`ISSUED` invoice never re-enters `transition`'s
     * `ISSUED` branch, RV-02, but this keeps the function itself idempotent regardless).
     */
    fun freezePaymentTermSnapshot() {
        if (paymentTermSnapshot != null) return
        val term = paymentTerm ?: return
        paymentTermSnapshot = InvoicePaymentTermSnapshot.of(term, documentLanguage.code)
    }

    /**
     * finding-fixes (2026-08-31, PO decision) — keeps [dueDate] consistent with the
     * `invoiceDate` stamp [de.littletrotter.application.invoice.InvoiceLifecycleService.transition]
     * applies at the `→ ISSUED` transition (overwriting whatever the draft carried with "today"). Left
     * untouched, a draft's dueDate — computed against its OLD invoiceDate, potentially months earlier
     * — can end up BEFORE the just-stamped invoiceDate. That is no longer rejected (the due date is a
     * plausibility rule, not a statutory one), but it is still wrong for any invoice whose draft
     * predates its actual send day, and it is what `NumberJournalReportServiceIT` reproduced.
     *
     * Called right after [freezePaymentTermSnapshot] so [paymentTermSnapshot] already reflects
     * whatever [paymentTerm] this invoice carries, if any. Three cases, in this order:
     *
     * 1. [previousDueDate] is `null` — this invoice never had a due date at all; stays `null`, nothing
     *    to keep consistent.
     * 2. [paymentTermSnapshot] carries a `dueInDays` — the exact day-count the entry pane's own
     *    `InvoiceDetailViewOverview.computeDueDate` uses to derive dueDate from a payment-term
     *    selection, so re-deriving from it here reproduces what the pane would show for "today".
     *    [paymentTermsDays] is deliberately NOT used as a fallback source here despite the
     *    similar name: it is a `create()`-time-only default (14) that is never written again once a
     *    payment term is actually chosen (verified — no other call site assigns it), so it would
     *    silently disagree with the payment term this document actually carries.
     * 3. No payment term was ever chosen (no snapshot) — the only fact this invoice still carries about
     *    its own due-date policy is the day-gap the draft itself had between [previousDueDate] and
     *    [previousInvoiceDate]; that gap is preserved onto the new invoiceDate. If [previousInvoiceDate]
     *    itself was never set either, [previousDueDate] is kept as-is rather than an arbitrary guess.
     *
     * date invariants (PO 2026-09-02) — [invoiceDate] is no longer nullable, so the "this
     * invoice never had an invoiceDate at all" edge case removed here can no longer occur through the
     * domain layer; [previousInvoiceDate] (a caller-captured snapshot of a *prior* call, not this
     * field) stays independently nullable and is still handled by case 3 above.
     */
    fun recomputeDueDateForSend(previousInvoiceDate: LocalDate?, previousDueDate: LocalDate?) {
        if (previousDueDate == null) return
        val dueInDays = paymentTermSnapshot?.dueInDays
        dueDate = when {
            dueInDays != null -> invoiceDate.plusDays(dueInDays.toLong())
            previousInvoiceDate != null -> invoiceDate.plusDays(ChronoUnit.DAYS.between(previousInvoiceDate, previousDueDate))
            else -> previousDueDate
        }
    }

    /** Replaces both snapshot rows atomically; used both at creation and by an explicit
     *  "Stammdaten erneut übernehmen". Never partial: a role without both rows set would leave
     *  [sellerSnapshot]/[buyerSnapshot] inconsistent. */
    fun applyPartySnapshots(seller: InvoicePartySnapshot, buyer: InvoicePartySnapshot) {
        assertEditable()
        replacePartySnapshots(seller, buyer)
    }

    private fun replacePartySnapshots(seller: InvoicePartySnapshot, buyer: InvoicePartySnapshot) {
        // Existing rows are overwritten in place: clear()+add() would make Hibernate flush the
        // INSERTs before the orphan DELETEs and collide on (invoice_id, party_role), which also
        // breaks when two refreshes of the same invoice race.
        for (fresh in listOf(seller, buyer)) {
            val existing = partySnapshots.firstOrNull { it.partyRole == fresh.partyRole }
            if (existing != null) {
                existing.copyIdentityFrom(fresh)
            } else {
                fresh.attachTo(this)
                partySnapshots.add(fresh)
            }
        }
    }

    /**
     * Every edit of an issued, cancelled or cancellation document is refused here, inside the aggregate,
     * so no caller can reach a mutation without passing the same check. The transitions after
     * issuing (mail marker, status, validation status, number, payment-term freeze) do not call it.
     */
    private fun assertEditable() {
        if (isReadOnly()) throw InvoiceImmutableException(invoiceNumber.value)
    }

    /**
     * Entry point for editing flows that change a position or receipt link on a loaded invoice:
     * refuses a read-only document first, then rejects a stale [expectedVersion] so the first
     * committed writer wins. `null` skips the version check.
     */
    fun beginEdit(expectedVersion: Long?) {
        assertEditable()
        // One read of the mutable property: a second read would add a null re-check on a value that
        // the first check already ruled out.
        val current = version
        if (expectedVersion != null && current != null && current != expectedVersion) {
            throw jakarta.persistence.OptimisticLockException(
                "Invoice $id was modified concurrently (expected version $expectedVersion, found $current)",
            )
        }
    }

    fun transitionTo(target: InvoiceStatus) {
        InvoiceStateMachine.assertLegal(status, target)
        val previous = status
        status = target
        domainEvents += InvoiceStatusChanged(id!!, invoiceNumber.value, previous, target)
        if (target == InvoiceStatus.ISSUED) {
            domainEvents += InvoiceFinalized(id!!, invoiceNumber.value)
        }
    }

    /**
     * The single place that changes [validationStatus] once an [Invoice] instance is already
     * loaded, following the same pattern as [GenerationJob.transitionTo]. Asserts the
     * edge is legal via [ValidationStatus.canTransitionTo] (see that method's own KDoc for the
     * full edge table, derived from the real write sites, and throws
     * [InvoiceValidationStatusTransitionException] on an illegal one — a programming error, not a
     * recoverable runtime condition, so callers are expected to let it propagate. No domain event
     * is raised here (unlike [transitionTo]): nothing in the codebase currently reacts to a
     * validation-status change as its own event, only to the [de.littletrotter.domain.event
     * .DocumentGenerated] event that usually triggers the write.
     *
     * Does NOT cover [de.littletrotter.infrastructure.persistence.PanacheInvoiceRepository
     * .updateValidationStatus]'s bulk JPQL `UPDATE` — that call site structurally cannot go
     * through a loaded entity (see [GenerationJobStateMachine]'s own KDoc for why); it always
     * writes [ValidationStatus.PENDING], which every other status may legally transition to
     * (including PENDING itself, see [ValidationStatus.canTransitionTo]'s KDoc), so its
     * unrestricted `WHERE id = :id` clause cannot produce an illegal edge and needs no further
     * guard.
     */
    fun transitionValidationStatusTo(target: ValidationStatus) {
        if (!validationStatus.canTransitionTo(target)) {
            throw InvoiceValidationStatusTransitionException(validationStatus.name, target.name)
        }
        validationStatus = target
    }

    /**
     * Returns all accumulated domain events and clears the internal list.
     * Should be called by the application layer after persisting the aggregate.
     */
    fun pullDomainEvents(): List<DomainEvent> {
        val events = domainEvents.toList()
        domainEvents.clear()
        return events
    }

    /**
     * Position and sort order are only **filled in** when the caller leaves them
     * unset, and they count up from the highest existing value instead of from `items.size`.
     *
     * Before, this had `item.sortOrder = nextIndex` **unconditionally**. That had two
     * consequences:
     *
     * 1. A `sortOrder` set by the caller was silently discarded — including the one the UI
     *    computes itself (correctly) via `InvoiceDetailView.nextSortOrder(invoice)`, and the
     *    calculation in `InvoiceLifecycleService.addItem`, which was dead code as a result.
     * 2. `items.size + 1` collides after deleting a position: with sort values 1, 2, 3 and
     *    position 2 deleted, `size == 2` remains, so the next position would get 3 again.
     *    Because the display sorts via `items.sortedBy { it.sortOrder }`
     *    (`InvoiceDetailViewItems`), the line order on the invoice and the PDF would then
     *    become ambiguous — no small matter on a tax document.
     *
     * `<= 0` as "not set": `InvoiceItem.sortOrder` is an `Int` with default `0`, and a
     * deliberately set `0` cannot be distinguished from that. Counting is therefore
     * consistently 1-based, as it already was for the first position.
     */
    fun addItem(item: InvoiceItem) {
        assertEditable()
        appendItem(item)
    }

    /**
     * Fills a document that is still being composed in memory (a credit note copy, a fixture born
     * in a later status) with its positions and totals. Rejected once the invoice has been
     * persisted: from then on every position change goes through [addItem].
     */
    fun addInitialItems(initialItems: List<InvoiceItem>) {
        check(id == null) { "Initial items can only be added before the invoice is persisted." }
        initialItems.forEach { appendItem(it) }
        computeTotals()
    }

    private fun appendItem(item: InvoiceItem) {
        val nextIndex = items.size + 1
        val positionId = item.positionId.ifBlank {
            ((items.mapNotNull { it.positionId.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        }
        if (item.description.isBlank()) {
            item.applyPositionText("Position $nextIndex", item.itemDescription, item.deliveryNoteReference)
        }
        val sortOrder = if (item.sortOrder <= 0) (items.maxOfOrNull { it.sortOrder } ?: 0) + 1 else item.sortOrder
        item.attachTo(this, positionId, sortOrder)
        items.add(item)
        if (id != null && invoiceNumber.isAssigned()) {
            domainEvents += InvoiceItemAdded(id!!, invoiceNumber.value, item.positionId)
        }
    }

    fun removeItem(item: InvoiceItem) {
        assertEditable()
        val removedPositionId = item.positionId
        items.remove(item)
        item.detach()
        if (id != null && invoiceNumber.isAssigned() && removedPositionId.isNotBlank()) {
            domainEvents += InvoiceItemRemoved(id!!, invoiceNumber.value, removedPositionId)
        }
    }

    fun recalculateTotals() {
        assertEditable()
        computeTotals()
    }

    /** Recomputes the totals of an open invoice and leaves a read-only document's stored totals alone. */
    fun recalculateTotalsWhileEditable() {
        if (!isReadOnly()) computeTotals()
    }

    private fun computeTotals() {
        items.forEach { it.calculateAmounts() }
        // items' amounts are already Money at Money.SCALE, so plain + (which itself normalizes
        // to that scale) is exact here — no separate setScale needed after the fold.
        val net = items.map { it.netAmount }.sum()
        val vat = items.map { it.vatAmount }.sum()
        totals = InvoiceTotals(net, vat, net + vat)
        // reverseCharge is no longer touched here: it is the invoice's own leading
        // decision now (see the field's own KDoc), not something recalculateTotals derives from
        // the items on every save.
        needsRecalculation = false
    }

    /**
     * [requireItems] defaults to `true` (every pre-
     * existing caller keeps requiring at least one position, unchanged): document generation
     * ([de.littletrotter.application.invoice.GenerationJobService.createJob]) and the general
     * [de.littletrotter.domain.compliance.DomainConstraints.validateInvoice] utility both still
     * reject an item-less invoice via this default. Only the draft-editing entrypoints that must
     * stay saveable *before* the first position exists —
     * [de.littletrotter.application.invoice.InvoiceLifecycleService.save]/`updateHeader`/
     * `setReverseChargeDecision` — call with `false`: a hard NO_ITEMS throw here made the header
     * itself impossible to save right after
     * [de.littletrotter.application.invoice.InvoiceLifecycleService.create], when zero items is
     * the normal starting state.
     */
    fun validate(requireItems: Boolean = true) {
        InvoiceValidation.validate(this, requireItems)
    }

    companion object {
        /** The class default for [paymentTermsDays], also the fallback day-count of a new draft. */
        const val DEFAULT_PAYMENT_TERMS_DAYS = 14

        /**
         * Constructs a new [Invoice] already in [initialStatus], bypassing
         * [InvoiceStateMachine]'s transition legality check. [transitionTo] is the only way to
         * change the status of an invoice that already exists; this factory exists for the two
         * cases where a document is genuinely *born* somewhere other than [InvoiceStatus.DRAFT]
         * and never passes through the states in between: a cancellation document
         * ([de.littletrotter.application.invoice.InvoiceCancellationService.createCreditNote], born
         * `CANCELLED`) and synthetic demo/test data
         * ([de.littletrotter.infrastructure.dev.DevDataFixtures], which must produce invoices in
         * every status without replaying a full lifecycle). Callable only from inside this class
         * (and its companion) because [status]'s setter is private.
         */
        fun withInitialStatus(initialStatus: InvoiceStatus): Invoice = Invoice().apply { status = initialStatus }

        /**
         * Creates an invoice that is complete from its first moment: the parties, header values,
         * the two note snapshots and the buyer reference of [draft], born in [InvoiceDraft.status].
         * The number, the party snapshots and the items follow through the aggregate's own steps.
         */
        fun draft(draft: InvoiceDraft): Invoice = withInitialStatus(draft.status).apply {
            applyParties(draft.parties)
            applyHeader(draft.header)
            texts.applyInvoiceNote(draft.invoiceNote)
            texts.applyConsumerNote(draft.consumerNote)
            texts.changeFreeText(draft.freeText)
            references.assignBuyerReference(draft.buyerReference)
            typeCode = draft.typeCode
            originalInvoiceNumber = draft.originalInvoiceNumber?.let { InvoiceNumber(it) }
            validationStatus = draft.validationStatus
            reverseCharge = draft.reverseCharge
        }

        /**
         * The credit-note invariant as a named domain step: a Gutschrift that names a
         * different party or a different tax case than the document it cancels does not cancel it.
         * The document is born `CANCELLED` as type 384 (never 381) and carries BT-25 ([originalNumber])
         * and BT-26 (the original's issue date). It adopts both parties (with the caller-resolved
         * [sender] profile), the delivery/billing addresses, currency, [documentLanguage],
         * [invoiceFormat], [sellerVatRegistrationCountry] and — the leading decision, no longer
         * derived by recalculateTotals() — [reverseCharge]: the items copied from the original carry
         * its taxCategory and would otherwise disagree with a left-at-default `false` and trip the
         * reverse-charge consistency check at finalization. It carries no payment terms at all:
         * neither a reference to the original's payment term nor a due date, and 0 payment days.
         *
         * The original's frozen notes, references, service period and delivery date travel along;
         * the document's own issue date is [issueDate] and its free text [freeText]. The items are
         * full copies with negated prices, so the totals are recalculated here.
         *
         * Qodana flags the assignment run below as duplicated with the item copy helpers.
         * Kept on purpose for the same reason they are: the explicit field
         * list is what makes "which context crosses into the Gutschrift" reviewable in a diff, and a
         * field added to [Invoice] must not start being adopted silently.
         */
        fun creditNoteFor(original: Invoice, sender: SenderProfile, details: CreditNoteDetails): Invoice =
            withInitialStatus(InvoiceStatus.CANCELLED).apply {
                typeCode = InvoiceTypeCode.CORRECTED_INVOICE
                originalInvoiceNumber = InvoiceNumber(details.originalNumber)
                // BT-26 — no guess: the original is in hand and its issue date is exactly known.
                // Stays null if the original itself carries none.
                originalInvoiceDate = original.invoiceDate

                senderProfile = sender
                recipient = original.recipient
                billingAddress = original.billingAddress
                deliveryAddress = original.deliveryAddress
                selectedSeller = original.selectedSeller
                currency = original.currency
                documentLanguage = original.documentLanguage
                invoiceFormat = original.invoiceFormat
                sellerVatRegistrationCountry = original.sellerVatRegistrationCountry
                reverseCharge = original.reverseCharge

                texts.applyInvoiceNote(original.texts.invoiceNoteSnapshot())
                texts.applyConsumerNote(original.texts.consumerNoteSnapshot())
                texts.changeFreeText(details.freeText)
                references.copyFrom(original.references)

                // Dates: the cancellation carries its own issue date (BT-2 = today, it may never precede the
                // original), but the original's service period and delivery date (BT-72/73/74) — it
                // annuls exactly that service, no other.
                invoiceDate = details.issueDate
                servicePeriodFrom = original.servicePeriodFrom
                servicePeriodTo = original.servicePeriodTo
                deliveryDate = original.deliveryDate
                // No payment terms on a cancellation: dueDate stays null (the Invoice default), and
                // paymentTermsDays is explicitly 0, not the entity default of 14.
                paymentTermsDays = 0

                // The order is the original's (items are sorted by sortOrder) and so are the
                // position ids — document and counter-document can be compared line by line.
                // addItem() assigns sortOrder afresh and only fills in a positionId where the
                // original had none.
                original.itemList.forEach { appendItem(InvoiceItem.copyForCorrection(it)) }
                computeTotals()
                // The credit note freezes its own party snapshots from master data, not from the
                // original's: it is its own document, issued today, and must carry today's address.
                recipient?.let { buyer ->
                    replacePartySnapshots(
                        InvoicePartySnapshot.forSeller(sender),
                        InvoicePartySnapshot.forBuyer(buyer, references.buyerReference),
                    )
                    freezeRegistrationTaxNumber()
                }
            }

        /** [invoiceDate]'s no-arg-constructor placeholder — see that field's own KDoc. */
        private val INVOICE_DATE_PLACEHOLDER: LocalDate = LocalDate.of(1970, 1, 1)
    }
}

/** The values a credit note takes from the caller instead of the original invoice: the number it
 *  cancels (BT-25), its own issue date and the free text composed for it. */
data class CreditNoteDetails(
    val originalNumber: String,
    val issueDate: LocalDate,
    val freeText: String,
)
