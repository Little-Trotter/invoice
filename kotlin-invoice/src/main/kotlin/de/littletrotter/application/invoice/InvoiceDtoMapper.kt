package de.littletrotter.application.invoice

import de.littletrotter.application.export.TripWorkbookExportPort
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.cashDiscount
import de.littletrotter.domain.invoice.toDto
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * The complete `Invoice` → [InvoiceDto] mapping, extracted out of `DocumentJobPublisher` so
 * that class stays focused on NATS envelope/send concerns. `kotlin-xpdf` deserializes against this
 * exact type on the receiving end; a typed producer means a renamed or removed field fails to
 * compile here instead of silently reaching the worker as a default.
 *
 * Orchestration only: per-position mapping is [InvoiceItemDtoMapper]'s, `TRIP` export is
 * [TripExportDtoMapper]'s, free B2B values
 * are [B2bWireValueMapper]'s, party/ship-to shaping is [PartyDtoMapper]'s and header sub-DTOs
 * (dates/references/totals/meta) are [InvoiceHeaderDtoMapper]'s. This class still does the one
 * thing none of those can: assemble the final [InvoiceDto] from an `Invoice` it owns loading/reload
 * of (see [attachedForMapping]).
 */
@ApplicationScoped
class InvoiceDtoMapper {
    private var receiptRepository: ReceiptRepository? = null
    /** HARDEN — lazy-association hardening: [mapToDto] reloads its own `Invoice` argument by id off
     *  this repository before reading anything else off it (see [attachedForMapping]). Null in
     *  plain unit tests; then [mapToDto] reads the passed-in `Invoice` exactly as it always did —
     *  safe there because a plain unit test's `Invoice` is always a plain in-memory instance, never
     *  Hibernate-managed, so it carries no lazy associations to begin with. */
    private var invoiceRepository: InvoiceRepository? = null
    /** PO decision 2026-09-28 — the EXTENDED-profile escalation is an admin opt-in
     *  (`FeatureFlag.EXTENDED_PROFILE`); this gate is what [buildMeta]'s call reads it from.
     *  Null in the plain-unit-test convenience constructor below, where [isExtendedProfileEnabled]
     *  falls back to `false` — the same off-by-default the flag itself declares
     *  ([FeatureFlag.EXTENDED_PROFILE.defaultEnabled]), so an unwired test never silently escalates. */
    private var featureSettingsGate: FeatureSettingsGate? = null
    private lateinit var b2bWireValueMapper: B2bWireValueMapper
    private lateinit var invoiceItemDtoMapper: InvoiceItemDtoMapper

    @Inject
    constructor(
        receiptRepository: ReceiptRepository,
        invoiceRepository: InvoiceRepository,
        b2bWireValueMapper: B2bWireValueMapper,
        invoiceItemDtoMapper: InvoiceItemDtoMapper,
        featureSettingsGate: FeatureSettingsGate,
    ) {
        this.receiptRepository = receiptRepository
        this.invoiceRepository = invoiceRepository
        this.b2bWireValueMapper = b2bWireValueMapper
        this.invoiceItemDtoMapper = invoiceItemDtoMapper
        this.featureSettingsGate = featureSettingsGate
    }

    /**
     * Plain-unit-test convenience constructor: every collaborator is optional so a test can
     * wire only the ones it cares about, by name — kept byte-for-byte compatible with every existing
     * call site (`InvoiceDtoMapper(receiptRepository = ...)` etc.). Deliberately **not** just the
     * `@Inject` constructor above with `?`/defaults added — Kotlin auto-generates an extra public
     * no-arg constructor whenever every parameter of a *primary* constructor has a default value,
     * and copies any annotations (including `@Inject`) onto it too; Quarkus ARC then sees two
     * `@Inject` candidates and fails bean discovery. Declaring this as its own secondary constructor
     * (not the primary one) avoids that entirely; it is not CDI-visible, so building the four
     * collaborators above with plain `new` here (never in the `@Inject` constructor) does not bypass
     * their own CDI lifecycle in production.
     */
    constructor(
        payloadExchange: PayloadExchange? = null,
        b2bFieldValueService: B2bFieldValueService? = null,
        receiptRepository: ReceiptRepository? = null,
        articleManagementService: ArticleManagementService? = null,
        invoiceRepository: InvoiceRepository? = null,
        tripWorkbookExportPort: TripWorkbookExportPort? = null,
        featureSettingsGate: FeatureSettingsGate? = null,
    ) {
        this.receiptRepository = receiptRepository
        this.invoiceRepository = invoiceRepository
        this.featureSettingsGate = featureSettingsGate
        this.b2bWireValueMapper = B2bWireValueMapper(b2bFieldValueService)
        this.invoiceItemDtoMapper = InvoiceItemDtoMapper(
            articleManagementService,
            b2bWireValueMapper,
            TripExportDtoMapper(tripWorkbookExportPort, payloadExchange),
        )
    }

    /**
     * HARDEN: the very first line below reassigns `inv` to [Invoice.attachedForMapping]'s result
     * before anything else touches it (see [attachedForMapping]'s own KDoc). `@Transactional` is
     * what makes the reload actually useful standalone, not just from within an already-open
     * transaction: called with none active, it opens a fresh one for the duration of this method;
     * called from within one (every current production caller), it simply joins it.
     */
    @Transactional
    fun mapToDto(
        inv: Invoice,
        wordingProfile: WordingProfile = WordingProfile.DEFAULT,
        issued: Boolean = false,
    ): InvoiceDto {
        val inv = inv.attachedForMapping()
        val sender = inv.selectedSeller ?: inv.senderProfile
        val recipient = inv.recipient

        // belege-improve A4 / R10 — receipts must reach the worker as real data (fileName +
        // objectKey). The invoice passed here may be a detached UI copy whose receipts are id-only
        // stubs (dtoToInvoice), or a managed entity with unfetched lazy collections — in both cases
        // the passed-in collections are not trustworthy. The wire value is therefore always derived
        // from the receipt pool (source of truth) when a repository is available; without one
        // (plain unit tests) the entity's own data is used as before.
        val repo = receiptRepository
        val invoiceId = inv.id
        val itemReceiptsByItemId: Map<Long, List<ReceiptDto>>? = if (repo != null && invoiceId != null) {
            repo.findItemReceiptsForInvoice(invoiceId)
                .mapValues { (_, rs) -> rs.map { ReceiptDto(fileName = it.fileName, objectKey = it.objectKey) } }
        } else {
            null
        }
        val invoiceLevelReceipts = invoiceLevelReceiptsOf(inv)

        val items = inv.itemList.map { item -> invoiceItemDtoMapper.toDto(item, inv, itemReceiptsByItemId, wordingProfile) }

        // BG-13. `recipientBillingAddress` is exactly the fallback chain
        // the recipient mapping below is called with — one expression, so a delivery address that
        // only steps in *as* the billing-address fallback never produces a ship-to.
        val recipientBillingAddress = recipient?.let { it.billingAddress ?: inv.deliveryAddress ?: it.deliveryAddress }
        val deliveryAddress = inv.deliveryAddress ?: recipient?.deliveryAddress
        val shipTo = recipient?.let { PartyDtoMapper.buildShipTo(it, recipientBillingAddress, deliveryAddress) }

        val dto = InvoiceDto(
            id = inv.id,
            invoiceNumber = InvoiceNumberDto(inv.invoiceNumber.value),
            typeCode = inv.typeCode.code,
            senderProfile = sender?.let { PartyDtoMapper.senderToDto(it, inv.billingAddress ?: it.billingAddress, inv.invoiceNote) },
            recipient = recipient?.let { PartyDtoMapper.recipientToDto(it, recipientBillingAddress) },
            shipTo = shipTo,
            items = items,
            receipts = invoiceLevelReceipts,
            dates = InvoiceHeaderDtoMapper.buildDates(inv),
            cashDiscount = inv.cashDiscount()?.toDto(),
            references = InvoiceHeaderDtoMapper.buildReferences(inv),
            totals = InvoiceHeaderDtoMapper.buildTotals(inv),
            meta = InvoiceHeaderDtoMapper.buildMeta(
                inv, sender, recipient,
                InvoiceHeaderDtoMapper.MetaSettings(wordingProfile, isExtendedProfileEnabled()),
            ),
            // The resolved value of every RECIPIENT-placed field (invoice > recipient >
            // profile default) plus every INVOICE-owner value (b2bValuesFor's entries win on overlap
            // — Map addition below keeps the last argument's keys), plus every SELLER-placed
            // values appended last so they never displace a genuine RECIPIENT-/INVOICE-level value
            // for the same term (MustangExportModelMapper.b2bValue()'s "first entry wins" contract).
            b2bValues = (
                b2bWireValueMapper.recipientPlacedValues(inv) +
                    b2bWireValueMapper.valuesFor(B2bFieldOwnerType.INVOICE, inv.id).associateBy { it.term.officialCode }
                ).values.toList() +
                b2bWireValueMapper.valuesFor(B2bFieldOwnerType.SENDER_PROFILE, sender?.id),
            // The draft watermark applies to everything not yet finalized: DRAFT/IN_REVIEW, and
            // VOIDED too (defense in depth for a void racing an already-queued job); false for
            // SENT/CANCELLED.
            // [issued] = the generation job produces the issued document (GenerationJob.issued, e.g.
            // the SEND_ISSUE job that runs while the invoice is still IN_REVIEW): never a draft, so
            // no watermark and the archive package is built. VOIDED stays a draft regardless.
            draft = inv.status == InvoiceStatus.VOIDED ||
                (!issued && (inv.status == InvoiceStatus.DRAFT || inv.status == InvoiceStatus.IN_REVIEW)),
            // The storno document itself is type code 384, independent of its (also CANCELLED)
            // status.
            storno = inv.typeCode == InvoiceTypeCode.CORRECTED_INVOICE,
        )
        return dto
    }

    /**
     * HARDEN: the reload [mapToDto] itself is built on — see [mapToDto]'s own KDoc
     * for the full reasoning. [invoiceRepository] unset, or the argument not yet persisted
     * (`id == null`), falls back to the argument itself unchanged.
     */
    private fun Invoice.attachedForMapping(): Invoice = id?.let { invoiceRepository?.findByIdWithItems(it) } ?: this

    /** PO decision 2026-09-28 — off (`false`) whenever no gate is wired, matching
     *  `FeatureFlag.EXTENDED_PROFILE.defaultEnabled`: a plain unit test that never cared about the
     *  flag keeps producing EN16931, not a silently-escalated EXTENDED. */
    private fun isExtendedProfileEnabled(): Boolean = featureSettingsGate?.isEnabled(FeatureFlag.EXTENDED_PROFILE) ?: false

    /** Invoice-level receipts from the receipt pool when a repository is available; without one
     *  (plain unit tests) the entity's own data. */
    private fun invoiceLevelReceiptsOf(inv: Invoice): List<ReceiptDto> {
        val repo = receiptRepository
        val invoiceId = inv.id
        return if (repo != null && invoiceId != null) {
            repo.findForInvoice(invoiceId, 0, Int.MAX_VALUE)
                .map { ReceiptDto(fileName = it.fileName, objectKey = it.objectKey) }
        } else {
            try { inv.receipts.map { ReceiptDto(fileName = it.fileName, objectKey = it.objectKey) } } catch (_: Exception) { emptyList() }
        }
    }
}
