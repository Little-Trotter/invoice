package de.littletrotter.domain.invoice

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceItemAdded
import de.littletrotter.domain.event.InvoiceItemRemoved
import de.littletrotter.domain.event.InvoiceStatusChanged
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationStatusTransitionException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.CountryAttributeConverter
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.invoice.converters.DocumentEngineConverter
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.converters.InvoiceFormatConverter
import de.littletrotter.domain.invoice.converters.InvoiceStatusConverter
import de.littletrotter.domain.invoice.converters.InvoiceTypeCodeConverter
import de.littletrotter.shared.Currency
import de.littletrotter.shared.sum
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.shared.converters.CurrencyConverter
import de.littletrotter.domain.shared.converters.ValidationStatusConverter
import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

@Entity
@Table(name = "invoice")
class Invoice : de.littletrotter.domain.shared.AuditableEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column
    @Convert(converter = InvoiceNumberConverter::class)
    var invoiceNumber: InvoiceNumber = InvoiceNumber.UNASSIGNED

    /**
     * Prefix and counter kept separate from the composed [invoiceNumber]: reporting and a later
     * gap check work on [invoiceNumberSequence] as a number, not on a text pattern. Both are set
     * **once**, at assignment (`InvoiceLifecycleService.transition` on the transition to `SENT`)
     * and never changed afterward — exactly like [invoiceNumber] itself. `null` as long as the
     * invoice is a draft.
     */
    @field:Size(max = 30)
    @Column(name = "invoice_number_prefix")
    var invoiceNumberPrefix: String? = null

    @Column(name = "invoice_number_sequence")
    var invoiceNumberSequence: Long? = null

    /**
     * The currency this invoice is billed in. One currency per invoice — all items and
     * totals share it. Persisted as ordinal via [CurrencyConverter]; the `V001` baseline declares
     * the column as INTEGER. [de.littletrotter.shared.Money] itself remains currency-agnostic; this is the
     * single source of truth that flows into `ram:InvoiceCurrencyCode` during ZUGFeRD export.
     */
    @Convert(converter = CurrencyConverter::class)
    @Column(name = "currency", nullable = false)
    var currency: Currency = Currency.EUR

    @Convert(converter = InvoiceStatusConverter::class)
    @Column(nullable = false)
    final var status: InvoiceStatus = InvoiceStatus.DRAFT
        private set

    /**
     * Which engine renders this invoice's document, Mustang (default, unchanged behavior) or
     * the Factur-X engine. `null`-safe default keeps every existing invoice exactly as before.
     * Read by [de.littletrotter.domain.compliance.ArdennesXRules] (SIRET gate) and, once a
     * print-dialog engine selector lands, set from there.
     */
    @Convert(converter = DocumentEngineConverter::class)
    @Column(name = "document_engine", nullable = false)
    var documentEngine: DocumentEngine = DocumentEngine.MUSTANG

    @ManyToOne(fetch = FetchType.LAZY)
    var senderProfile: SenderProfile? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var recipient: Recipient? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var billingAddress: Address? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var deliveryAddress: Address? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var selectedSeller: SenderProfile? = null

    @ManyToOne(fetch = FetchType.LAZY)
    var paymentTerm: PaymentTerm? = null

    /**
     * The frozen payment-term values, set once at the `SENT` transition via
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
     * The accessor materialises on first touch and assigns, so the ~90 call sites that write
     * through it (`invoice.references.buyerReference = …`) keep working unchanged and their
     * writes still reach the columns. A caller that only READS a reference field on a draft
     * therefore still materialises the composite once — an avoidable write, no longer a
     * correctness problem; removing it is the encapsulation step the spec describes.
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
    var references: InvoiceReferences
        get() = referenceFields ?: InvoiceReferences().also { referenceFields = it }
        set(value) {
            referenceFields = value
        }

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

    @Transient
    var languageId: String? = null

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
    @field:Size(max = 5)
    @Column(name = "document_language", length = 5, nullable = false)
    var documentLanguage: String = "de"

    @Transient
    var documentStatusReasonCode: String? = null

    // date invariants — every invoice carries an invoice date from the moment it
    // exists, not only at finalization; every creation path (InvoiceLifecycleService.create,
    // InvoiceStornoService's credit-note construction, DevDataFixtures) sets this explicitly from
    // TimeSource.today(). A domain class may not read the clock itself
    // (TimeSourceArchitectureTest), so this no-arg-constructor placeholder is a fixed marker, never
    // `now()` — it is never observed on a real invoice.
    var invoiceDate: LocalDate = INVOICE_DATE_PLACEHOLDER

    /** BT-73 — service period start (BG-14). */
    var servicePeriodFrom: LocalDate? = null

    /** BT-74 — service period end (BG-14). */
    var servicePeriodTo: LocalDate? = null

    /**
     * BT-72 — the actual delivery/performance date, its own field since V031. Before that,
     * export mapped BT-72 onto [servicePeriodTo] (BT-74), i.e. the two terms were structurally
     * the same. That fallback still exists and lives in
     * `MustangExportModelMapper`/`PdfRendererFactoryImpl`, not here: `null` means "no delivery
     * date of its own captured", not "same as the period end" — the difference matters for
     * [de.littletrotter.domain.compliance.PeriodConsistencyRules].
     */
    var deliveryDate: LocalDate? = null

    var dueDate: LocalDate? = null
    var paymentTermsDays: Int = 14

    @Column(columnDefinition = "TEXT")
    var freeText: String? = null

    /** INV-B — the invoice-owned, editable copy of the seller's language-specific note. */
    @Column(name = "invoice_note", columnDefinition = "TEXT")
    var invoiceNote: String? = null

    /** Explicit provenance makes an intentionally cleared note distinguishable from an untouched
     * empty seller default. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_note_origin", nullable = false, length = 20)
    var invoiceNoteOrigin: InvoiceNoteOrigin = InvoiceNoteOrigin.SELLER_DEFAULT

    @Column(name = "invoice_note_source_sender_id")
    var invoiceNoteSourceSenderProfileId: Long? = null

    @field:Size(max = 5)
    @Column(name = "invoice_note_source_language", nullable = false, length = 5)
    var invoiceNoteSourceLanguage: String = "de"

    @Column(name = "invoice_note_source_default", columnDefinition = "TEXT")
    var invoiceNoteSourceDefaultValue: String? = null

    /** Invoice-owned copy of the private-customer notice; `null` when none applies. */
    @Column(name = "consumer_note", columnDefinition = "TEXT")
    var consumerNote: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "consumer_note_origin", nullable = false, length = 20)
    var consumerNoteOrigin: InvoiceNoteOrigin = InvoiceNoteOrigin.SELLER_DEFAULT

    @Column(name = "consumer_note_source_sender_id")
    var consumerNoteSourceSenderProfileId: Long? = null

    @field:Size(max = 5)
    @Column(name = "consumer_note_source_language", nullable = false, length = 5)
    var consumerNoteSourceLanguage: String = "de"

    /** Recipient type the default was resolved for. */
    @field:Size(max = 20)
    @Column(name = "consumer_note_source_context", nullable = false, length = 20)
    var consumerNoteSourceContext: String = ""

    @Column(name = "consumer_note_source_default", columnDefinition = "TEXT")
    var consumerNoteSourceDefaultValue: String? = null

    /** INV-J — comma-separated official BT codes already copied into [freeText]. Codes are stored
     * separately so later manual edits/removal of generated text remain authoritative. */
    @Column(name = "b2b_free_text_imported_codes", columnDefinition = "TEXT")
    private var b2bFreeTextImportedCodes: String? = null

    fun invoiceNoteSnapshot(): InvoiceNoteSnapshot = InvoiceNoteSnapshot(
        invoiceNote,
        invoiceNoteOrigin,
        invoiceNoteSourceSenderProfileId,
        invoiceNoteSourceLanguage,
        invoiceNoteSourceDefaultValue,
    )

    fun applyInvoiceNoteSnapshot(snapshot: InvoiceNoteSnapshot) {
        invoiceNote = snapshot.value
        invoiceNoteOrigin = snapshot.origin
        invoiceNoteSourceSenderProfileId = snapshot.sourceSenderProfileId
        invoiceNoteSourceLanguage = snapshot.sourceLanguage
        invoiceNoteSourceDefaultValue = snapshot.sourceDefaultValue
    }

    fun importedB2bFreeTextCodes(): Set<String> = b2bFreeTextImportedCodes
        ?.split(',')
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        ?.toSet()
        .orEmpty()

    fun replaceImportedB2bFreeTextCodes(codes: Set<String>) {
        b2bFreeTextImportedCodes = codes.sorted().joinToString(",").ifEmpty { null }
    }

    @Convert(converter = InvoiceTypeCodeConverter::class)
    var typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE

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
     * the recipient's own Reverse-Charge eligibility) are the two hard gates `→ SENT` now enforces
     * instead of the old, purely derivation-based invariant.
     */
    var reverseCharge: Boolean = false

    /** BT-25 — number of the document this one cancels (BG-3). Set only on a credit note. */
    @field:Size(max = 255)
    var originalInvoiceNumber: String? = null

    /**
     * BT-26 — issue date of the preceding document (BG-3), its own field since V033.
     *
     * Copied from the original invoice at storno time, not looked up via [originalInvoiceNumber]
     * at export time: the credit note carries its own reference and therefore stays readable
     * even once the original is archived. `null` means "not captured" (every credit note
     * created before V033) — export then omits the element rather than guessing a date.
     */
    var originalInvoiceDate: LocalDate? = null

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

    // validation-status-truthful-pending.md — NOT_VALIDATED, not PENDING: a freshly created
    // invoice has never been near an e-invoice run. PENDING is only set once a generation job is
    // actually published (DocumentJobPublisher.publish).
    @Convert(converter = ValidationStatusConverter::class)
    @Column(nullable = false)
    var validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED

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
     * [InvoiceStatus]. `SENT` is a lifecycle decision the user makes; "an email went out" is an
     * observation, and folding the two would make the state machine depend on whether an optional
     * component is installed at all.
     *
     * Nullable, and `null` genuinely means "never observed": an instance that runs without the
     * component leaves every row `null` forever, and an instance that later removes the component
     * keeps the flags it already collected — they stay historically true either way.
     */
    @Column(name = "mail_sent")
    var mailSent: Boolean? = null

    /** When the component recorded the terminal SENT transition (UTC). See
     *  [mailSent] for why these two columns are a read model and not a status. */
    @Column(name = "mail_sent_at")
    var mailSentAt: OffsetDateTime? = null

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
    var llmAnalyses: MutableList<InvoiceLlmAnalysis> = mutableListOf()

    /** Read-only for the same reason as [items]; nothing currently needs to add to
     *  this collection from the owning side (a checksum row is persisted directly through
     *  `InvoiceDocumentRepository`, which sets the owning [InvoiceDocument.invoice] reference). */
    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val documents: List<InvoiceDocument> = mutableListOf()

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
     *  `SENT`/`CANCELLED`/`VOIDED` (read-only guard in the service layer, not the UI). */
    @ManyToMany
    @JoinTable(
        name = "invoice_receipt",
        joinColumns = [JoinColumn(name = "invoice_id")],
        inverseJoinColumns = [JoinColumn(name = "receipt_id")],
    )
    var receipts: MutableList<Receipt> = mutableListOf()

    @Version
    var version: Long? = 0

    /** The accepted offer this invoice was drafted from; `null` for every other invoice. The
     *  unique column makes a repeated acceptance event find the existing draft instead of
     *  creating a second one. */
    @Column(name = "source_quote_id")
    var sourceQuoteId: java.util.UUID? = null

    @Transient
    private val domainEvents: MutableList<DomainEvent> = mutableListOf()

    // ── Domain logic ──────────────────────────────────────────────────────────

    /**
     * [totals] needs no equivalent treatment: `net_amount`, `vat_amount` and `gross_amount` are
     * all NOT NULL in the schema, so Hibernate's null-composite rule never applies to it and the
     * field always arrives hydrated. The former `@PostLoad` repair covered both fields; it is
     * gone together with the defect it caused — see [references].
     */

    // A storno document is read-only from the moment it exists, regardless
    // of its own status: it is the system's automatic 1:1 copy of the original invoice's items and
    // documents, not a fresh draft someone is supposed to keep editing.
    //
    // This predicate keys on CORRECTED_INVOICE (`384`), not CREDIT_NOTE (`381`), along with the
    // storno flow itself (InvoiceStornoService.createCreditNote); CREDIT_NOTE is reserved for a
    // genuine credit note and carries no read-only invariant of its own today, since nothing in
    // this codebase produces one.
    //
    // Read-only is not the same predicate as terminal: SENT keeps an outgoing edge (the
    // storno transition to CANCELLED) but is still immutable in every other respect, so this
    // spells out InvoiceStatus.isReadOnly explicitly rather than InvoiceStateMachine.isTerminal.
    fun isReadOnly(): Boolean = status.isReadOnly || typeCode == InvoiceTypeCode.CORRECTED_INVOICE

    /**
     * Whether this invoice carries a Reverse-Charge (`AE`) item, derived purely from
     * each item's tax snapshot/category, independent of [reverseCharge] (the header decision is
     * leading, not a derivation of this one). Still the wire-export truth
     * ([de.littletrotter.application.invoice.InvoiceDtoMapper] keeps reading this, not the raw
     * column) and used by [validateReverseChargeItemConsistency] to find items that disagree with
     * the header decision.
     */
    fun hasReverseChargeItems(): Boolean = items.any { it.taxCategoryEnum == VatCategory.REVERSE_CHARGE }

    /** The frozen seller identity a finalized invoice must read instead of the live
     *  [senderProfile] association. `null` only for an invoice created before this snapshot
     *  existed. */
    fun sellerSnapshot(): InvoicePartySnapshot? = partySnapshots.firstOrNull { it.partyRole == PartyRole.SELLER }

    /** The frozen buyer identity, BT-10 included. See [sellerSnapshot]. */
    fun buyerSnapshot(): InvoicePartySnapshot? = partySnapshots.firstOrNull { it.partyRole == PartyRole.BUYER }

    /** Replaces both snapshot rows atomically; used both at creation and by an explicit
     *  "Stammdaten erneut übernehmen". Never partial: a role without both rows set would leave
     *  [sellerSnapshot]/[buyerSnapshot] inconsistent. */
    fun applyPartySnapshots(seller: InvoicePartySnapshot, buyer: InvoicePartySnapshot) {
        // Existing rows are overwritten in place: clear()+add() would make Hibernate flush the
        // INSERTs before the orphan DELETEs and collide on (invoice_id, party_role), which also
        // breaks when two refreshes of the same invoice race.
        for (fresh in listOf(seller, buyer)) {
            val existing = partySnapshots.firstOrNull { it.partyRole == fresh.partyRole }
            if (existing != null) {
                existing.copyIdentityFrom(fresh)
            } else {
                fresh.invoice = this
                partySnapshots.add(fresh)
            }
        }
    }

    fun canTransitionTo(target: InvoiceStatus): Boolean = InvoiceStateMachine.isLegal(status, target)

    fun transitionTo(target: InvoiceStatus) {
        InvoiceStateMachine.assertLegal(status, target)
        val previous = status
        status = target
        domainEvents += InvoiceStatusChanged(id!!, invoiceNumber.value, previous, target)
        if (target == InvoiceStatus.SENT) {
            domainEvents += InvoiceFinalized(id!!, invoiceNumber.value)
        }
    }

    /**
     * The credit-note invariant as a named domain step: a Gutschrift that names a
     * different party or a different tax case than the document it cancels does not cancel it.
     * Adopts both parties (with the caller-resolved [sender] profile), the delivery/billing
     * addresses, payment term, currency, [documentLanguage], [invoiceFormat],
     * [sellerVatRegistrationCountry] and — the leading decision, no longer
     * derived by recalculateTotals() — [reverseCharge]: the items the caller copies via
     * `copyForCreditNote()` carry the original's taxCategory and would otherwise disagree with a
     * left-at-default `false` and trip `validateReverseChargeItemConsistency` at finalization.
     *
     * Qodana flags the assignment run below as duplicated with the `copyForCreditNote()` helpers.
     * Kept on purpose for the same reason they are: the explicit field
     * list is what makes "which context crosses into the Gutschrift" reviewable in a diff, and a
     * field added to [Invoice] must not start being adopted silently.
     */
    fun adoptPartiesAndTaxContextFrom(original: Invoice, sender: SenderProfile) {
        senderProfile = sender
        recipient = original.recipient
        billingAddress = original.billingAddress
        deliveryAddress = original.deliveryAddress
        selectedSeller = original.selectedSeller
        // A storno carries NO payment terms at all: not even a
        // reference to the original's PaymentTerm master-data row. This method has exactly one
        // caller (InvoiceStornoService.createCreditNote), so the original's line here is simply
        // dropped rather than adopted and later cleared again.
        currency = original.currency
        documentLanguage = original.documentLanguage
        invoiceFormat = original.invoiceFormat
        sellerVatRegistrationCountry = original.sellerVatRegistrationCountry
        reverseCharge = original.reverseCharge
    }

    /** The [validationStatus] counterpart to [canTransitionTo]. */
    fun canTransitionValidationStatusTo(target: ValidationStatus): Boolean = validationStatus.canTransitionTo(target)

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
        if (!canTransitionValidationStatusTo(target)) {
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
        item.invoice = this
        val nextIndex = items.size + 1
        if (item.positionId.isBlank()) {
            item.positionId = ((items.mapNotNull { it.positionId.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        }
        if (item.description.isBlank()) item.description = "Position $nextIndex"
        if (item.sortOrder <= 0) {
            item.sortOrder = (items.maxOfOrNull { it.sortOrder } ?: 0) + 1
        }
        items.add(item)
        if (id != null && invoiceNumber.isAssigned()) {
            domainEvents += InvoiceItemAdded(id!!, invoiceNumber.value, item.positionId)
        }
    }

    fun removeItem(item: InvoiceItem) {
        val removedPositionId = item.positionId
        items.remove(item)
        item.invoice = null
        if (id != null && invoiceNumber.isAssigned() && removedPositionId.isNotBlank()) {
            domainEvents += InvoiceItemRemoved(id!!, invoiceNumber.value, removedPositionId)
        }
    }

    fun recalculateTotals() {
        items.forEach { it.calculateAmounts() }
        // items' amounts are already Money at Money.SCALE, so plain + (which itself normalizes
        // to that scale) is exact here — no separate setScale needed after the fold.
        totals.netAmount = items.map { it.netAmount }.sum()
        totals.vatAmount = items.map { it.vatAmount }.sum()
        totals.grossAmount = totals.netAmount + totals.vatAmount
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
        validateInvoiceNumber()
        validatePartiesAndAddresses()
        validateDatesAndPeriods()
        validateTotalsSign()
        validateItems(requireItems)
        validatePaymentTerm()
    }

    private fun validateInvoiceNumber() {
        if (status.isIssued && !invoiceNumber.isAssigned()) {
            throw InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING)
        }
    }

    private fun validatePartiesAndAddresses() {
        val sender = senderProfile ?: throw InvoiceValidationException(InvoiceValidationError.SENDER_PROFILE_MISSING)
        sender.validate()

        val rcpt = recipient ?: throw InvoiceValidationException(InvoiceValidationError.RECIPIENT_MISSING)
        rcpt.validate()

        effectiveSenderBillingAddress()?.validate()
        effectiveRecipientBillingAddress()?.validate()
        effectiveRecipientDeliveryAddress()?.validate()
    }

    private fun validateDatesAndPeriods() {
        if (servicePeriodFrom != null && servicePeriodTo != null && servicePeriodTo!!.isBefore(servicePeriodFrom)) {
            throw InvoiceValidationException(InvoiceValidationError.SERVICE_PERIOD_END_BEFORE_START)
        }
        if (dueDate != null && dueDate!!.isBefore(invoiceDate)) {
            throw InvoiceValidationException(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE)
        }
        if (paymentTermsDays < 0) {
            throw InvoiceValidationException(InvoiceValidationError.PAYMENT_TERM_DAYS_NEGATIVE)
        }
    }

    private fun validateTotalsSign() {
        if (typeCode != InvoiceTypeCode.CORRECTED_INVOICE) {
            if (totals.netAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.NET_AMOUNT_NEGATIVE)
            if (totals.vatAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.VAT_AMOUNT_NEGATIVE)
            if (totals.grossAmount.isNegative()) throw InvoiceValidationException(InvoiceValidationError.GROSS_AMOUNT_NEGATIVE)
        }
    }

    private fun validateItems(requireItems: Boolean) {
        if (requireItems && items.isEmpty()) throw InvoiceValidationException(InvoiceValidationError.NO_ITEMS)
        items.forEachIndexed { index, item ->
            try {
                item.validate()
            } catch (ex: InvoiceValidationException) {
                throw InvoiceValidationException(
                    InvoiceValidationError.ITEM_POSITION,
                    listOf(index + 1, ex.error.name),
                    cause = ex,
                )
            }
        }
    }

    private fun validatePaymentTerm() {
        paymentTerm?.validate()
    }

    companion object {
        /**
         * Constructs a new [Invoice] already in [initialStatus], bypassing
         * [InvoiceStateMachine]'s transition legality check. [transitionTo] is the only way to
         * change the status of an invoice that already exists; this factory exists for the two
         * cases where a document is genuinely *born* somewhere other than [InvoiceStatus.DRAFT]
         * and never passes through the states in between: a storno document
         * ([de.littletrotter.application.invoice.InvoiceStornoService.createCreditNote], born
         * `CANCELLED`) and synthetic demo/test data
         * ([de.littletrotter.infrastructure.dev.DevDataFixtures], which must produce invoices in
         * every status without replaying a full lifecycle). Callable only from inside this class
         * (and its companion) because [status]'s setter is private.
         */
        fun withInitialStatus(initialStatus: InvoiceStatus): Invoice = Invoice().apply { status = initialStatus }

        /** [invoiceDate]'s no-arg-constructor placeholder — see that field's own KDoc. */
        private val INVOICE_DATE_PLACEHOLDER: LocalDate = LocalDate.of(1970, 1, 1)
    }
}
