package de.littletrotter.e2e.journal

import de.littletrotter.application.invoice.AuditJournalQueryService
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.JournalGlobalFilter
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The two journal views' read model ([AuditJournalQueryService], consumed
 * directly by [de.littletrotter.ui.view.invoice.InvoiceJournalPanel]/[de.littletrotter.ui.view
 * .system.JournalPanel]) against a real, trigger-instrumented Postgres: a full invoice lifecycle
 * (create → edit → item add/update/delete → send → storno) produces the exact ordered history the
 * per-invoice tab shows, the global view's filters (actor, date range) and pagination behave as
 * documented, and both views' "Zeitpunkt" sort — ASC/DESC, combined with paging — is
 * server-side and stable. The happy path additionally checks that each lifecycle step
 * lands as exactly ONE business action, correctly labelled, and that rows of different
 * transactions never merge into one line (a lesson from an earlier capture IT). Runs as an
 * ordinary failsafe IT (`@QuarkusTestResource(PostgresTestResource::class)` provisions its own
 * Postgres container) whenever Docker is available.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class AuditJournalViewsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var changeInvoiceStatus: ChangeInvoiceStatusUseCase

    @Inject
    lateinit var auditJournalQueryService: AuditJournalQueryService

    @Inject
    lateinit var receiptService: ReceiptLinkService

    // ── fixtures — same shape as InvoiceDetailFacadeStornoE2EIT/InvoiceAnnulmentE2EIT ──────────

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    // ── happy path: full lifecycle, per-invoice history is the complete ordered story ──────────

    @Test
    fun `a full lifecycle with edits and a storno produces the complete ordered per-invoice history`() {
        val tag = "AJ1"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }

        // CREATE an item, then UPDATE it, then ADD a second, then DELETE the first — exercises
        // every one of the three invoice_item events in one lifecycle.
        val firstItem = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"), taxRateId = parties.taxRateId,
                ),
            ).itemList.maxByOrNull { it.sortOrder }!!
        }
        val firstItemId = firstItem.id!!
        QuarkusTransaction.requiringNew().run {
            itemMutationService.updateItem(
                invoiceId,
                InvoiceItemCommand(
                    itemId = firstItemId, category = InvoiceItemCategory.SERVICE, description = "$tag Beratung (angepasst)",
                    quantity = BigDecimal("3"), unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"), taxRateId = parties.taxRateId,
                ),
            )
        }
        // The second position exists for its journal rows alone — it is never addressed again, so
        // its id is not kept (an unused private property is a detekt finding, and a misleading one:
        // it reads as if a later assertion still needed it).
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Zweitposition",
                    quantity = BigDecimal("1"), unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("50.00"), taxRateId = parties.taxRateId,
                ),
            )
        }
        // deleteItemByPosition wants the business-level positionId (e.g. "1"), not the database id
        // — passing firstItemId.toString() here (a numeric primary key, e.g. "10") threw
        // InvoicePositionNotFoundException, since no item's positionId ever equals a raw db id.
        // Found via a live-Postgres run of this IT (2026-09-04).
        QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, firstItem.positionId) }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId).deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        val creditNoteId = QuarkusTransaction.requiringNew().call {
            changeInvoiceStatus.createCreditNote(invoiceId, "$tag Kulanz-Storno").id
        }

        // The per-invoice tab's default is DESC (newest first); ASC is requested explicitly
        // here to check chronological order the easy way (oldest event first in the list).
        // The query service now groups by business action; flattening every group's own
        // technical rows (ascending by seq within a group, and groups are ordered by their own
        // min(seq)) reconstructs the exact same row-level history checked directly above.
        val history = auditJournalQueryService.findActionsByInvoice(invoiceId, 0, 100, SortDirection.ASC)
            .flatMap { it.rows }.sortedBy { it.seq }

        // Ascending by seq (oldest first) when ASC is requested.
        assertEquals(history.map { it.seq }.sorted(), history.map { it.seq })
        assertTrue(history.first().event == InvoiceAuditEvent.INVOICE_CREATED, "the very first entry must be the creation")

        // DESC (the tab's actual default) is the exact reverse of ASC over the same rows,
        // not a differently-filtered or re-ordered set.
        val historyDesc = auditJournalQueryService.findActionsByInvoice(invoiceId, 0, 100, SortDirection.DESC)
            .flatMap { it.rows }.sortedByDescending { it.seq }
        assertEquals(history.map { it.seq }.reversed(), historyDesc.map { it.seq })
        assertEquals(InvoiceAuditEvent.INVOICE_CREATED, historyDesc.last().event, "DESC must still end on the creation, the oldest event")
        // The full event vocabulary this lifecycle exercises must all be present, in some order:
        // invoice CREATED + UPDATED (delivery date/status changes), item CREATED x2/UPDATED/DELETED.
        val events = history.map { it.event }.toSet()
        assertTrue(InvoiceAuditEvent.INVOICE_CREATED in events)
        assertTrue(InvoiceAuditEvent.INVOICE_UPDATED in events)
        assertTrue(InvoiceAuditEvent.INVOICE_ITEM_CREATED in events)
        assertTrue(InvoiceAuditEvent.INVOICE_ITEM_UPDATED in events)
        assertTrue(InvoiceAuditEvent.INVOICE_ITEM_DELETED in events)
        // The storno creates a NEW invoice (the credit note) — its own history is separate, not
        // mixed into the original's.
        assertTrue(history.none { it.invoiceId == creditNoteId })
        val creditNoteHistory = auditJournalQueryService.findActionsByInvoice(creditNoteId, 0, 10, SortDirection.ASC)
            .flatMap { it.rows }.sortedBy { it.seq }
        assertTrue(creditNoteHistory.isNotEmpty())
        assertTrue(creditNoteHistory.first().event == InvoiceAuditEvent.INVOICE_CREATED)
    }

    // ── Happy path: one action line per lifecycle step, correctly labelled ─────────────────────

    /** Spec's own happy path: create → add item → change item → recalculate →
     *  DRAFT→IN_REVIEW → assign + detach a receipt → send → storno. Filters by the action under
     *  test (a lesson from an earlier capture IT: the journal is shared table-wide, so a naive
     *  "the last line" assertion would break the moment another test's transaction interleaves). */
    @Test
    fun `the happy path produces exactly one line per step, each with the right action`() {
        val tag = "AJ5"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }

        val item = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"), taxRateId = parties.taxRateId,
                ),
            ).itemList.maxByOrNull { it.sortOrder }!!
        }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.updateItem(
                invoiceId,
                InvoiceItemCommand(
                    itemId = item.id!!, category = InvoiceItemCategory.SERVICE, description = "$tag Beratung (angepasst)",
                    quantity = BigDecimal("3"), unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"), taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.recalculate(invoiceId) }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId).deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }

        val receiptId = QuarkusTransaction.requiringNew().call {
            val receipt = Receipt().apply {
                fileName = "$tag-beleg.pdf"; description = "Testbeleg $tag"
                objectKey = "e2e/journal/$tag/beleg.pdf"; documentDate = LocalDate.now()
                // The invoice document budget refuses a receipt of unknown size.
                sizeBytes = 1024L; sha256Checksum = "e2e-journal-$tag-checksum"
            }
            em.persist(receipt); em.flush(); receipt.id!!
        }
        QuarkusTransaction.requiringNew().run { receiptService.addReceiptsToInvoice(invoiceId, listOf(receiptId)) }
        QuarkusTransaction.requiringNew().run { receiptService.removeReceiptsFromInvoice(invoiceId, listOf(receiptId)) }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        QuarkusTransaction.requiringNew().call { changeInvoiceStatus.createCreditNote(invoiceId, "$tag Kulanz-Storno") }

        val lines = auditJournalQueryService.findActionsByInvoice(invoiceId, 0, 100, SortDirection.ASC)

        fun linesFor(action: InvoiceAuditAction): List<List<InvoiceAuditJournalEntry>> =
            lines.filter { it.action == action }.map { it.rows }

        assertEquals(1, linesFor(InvoiceAuditAction.INVOICE_CREATED).size, "exactly one INVOICE_CREATED line")
        assertEquals(1, linesFor(InvoiceAuditAction.ITEM_ADDED).size, "exactly one ITEM_ADDED line")
        assertEquals(1, linesFor(InvoiceAuditAction.ITEM_CHANGED).size, "exactly one ITEM_CHANGED line")
        assertEquals(1, linesFor(InvoiceAuditAction.RECALCULATED).size, "exactly one RECALCULATED line")
        // The delivery date is set by poking the entity directly — no application service, so no
        // declared action: that write is journaled as UNSPECIFIED, the same signal a write from
        // outside the application gives. It is its own transaction, hence its own line.
        assertEquals(0, linesFor(InvoiceAuditAction.HEADER_CHANGED).size, "no service call changed the header in this scenario")
        assertEquals(1, linesFor(InvoiceAuditAction.UNSPECIFIED).size, "the direct entity write bypasses every annotated service")
        assertTrue(linesFor(InvoiceAuditAction.STATUS_CHANGED).size >= 2, "at least IN_REVIEW and SENT each get their own STATUS_CHANGED line")
        assertEquals(1, linesFor(InvoiceAuditAction.RECEIPT_ASSIGNED).size, "exactly one RECEIPT_ASSIGNED line")
        assertEquals(1, linesFor(InvoiceAuditAction.RECEIPT_DETACHED).size, "exactly one RECEIPT_DETACHED line")
        assertEquals(1, linesFor(InvoiceAuditAction.STORNO).size, "exactly one STORNO line on this invoice's own side")

        // Every business_tx is genuinely distinct — no two of these steps' rows share one action.
        val businessTxs = lines.map { it.businessTx }
        assertEquals(businessTxs.size, businessTxs.toSet().size, "every step must be its own business_tx, none shared")
    }

    // ── global view: actor and date-range filters ───────────────────────────────────────────────

    @Test
    fun `the global view's actor filter narrows to writes made by that actor`() {
        // %test.auth.enabled=false (application.properties) -> OIDC tenant disabled -> anonymous
        // SecurityIdentity -> OidcAuditActorProvider falls back to "system" for every write this
        // suite makes, same reasoning as that provider's own KDoc.
        val tag = "AJ2"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }

        val systemActorPage = auditJournalQueryService.findGlobalActions(
            JournalGlobalFilter(actor = "system"), pageIndex = 0, pageSize = 500,
        )
        assertTrue(systemActorPage.flatMap { it.rows }.any { it.invoiceId == invoiceId }, "the just-created invoice must show up under actor=system")

        val noSuchActorPage = auditJournalQueryService.findGlobalActions(
            JournalGlobalFilter(actor = "no-such-actor-$tag"), pageIndex = 0, pageSize = 500,
        )
        assertTrue(noSuchActorPage.isEmpty(), "an actor nobody ever wrote as must return nothing")
    }

    @Test
    fun `the global view's date range filter includes today and excludes a range entirely in the past`() {
        val tag = "AJ3"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }

        val today = LocalDate.now()
        val todayPage = auditJournalQueryService.findGlobalActions(
            JournalGlobalFilter(occurredFrom = today, occurredTo = today), pageIndex = 0, pageSize = 500,
        )
        assertTrue(todayPage.flatMap { it.rows }.any { it.invoiceId == invoiceId }, "a row written today must be included by a from=today/to=today range")

        val longAgo = today.minusYears(5)
        val longAgoPage = auditJournalQueryService.findGlobalActions(
            JournalGlobalFilter(occurredFrom = longAgo.minusDays(1), occurredTo = longAgo), pageIndex = 0, pageSize = 500,
        )
        assertTrue(longAgoPage.flatMap { it.rows }.none { it.invoiceId == invoiceId }, "a row written today must NOT show up under a range entirely five years in the past")
    }

    // ── global view: pagination is stable and non-overlapping, in both sort directions ─────────

    private fun buildFiveInvoicesWithItems(tag: String, parties: Parties): List<Long> =
        (1..5).map { i ->
            QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }.also { id ->
                QuarkusTransaction.requiringNew().run {
                    itemMutationService.addItem(
                        id,
                        InvoiceItemCommand(
                            category = InvoiceItemCategory.SERVICE, description = "$tag-$i Beratung",
                            quantity = BigDecimal.ONE, unit = InvoiceItemUnit.HOUR,
                            unitPriceNet = Money.of("10.00"), taxRateId = parties.taxRateId,
                        ),
                    )
                }
            }
        }

    /** Server-side sorting composes correctly with paging, in either [direction]: pages
     *  are stable (each page's ACTIONS come strictly after/before the previous page's, matching
     *  [direction]), non-overlapping, and together cover every matching action exactly once.
     *  Identity/boundary now key off `business_tx`/`min(seq)` (the group's own ordering column)
     *  rather than a bare row `seq` — see [InvoiceAuditActionLine]. */
    private fun assertPagingIsStableAndComplete(filter: JournalGlobalFilter, direction: SortDirection) {
        val total = auditJournalQueryService.countGlobalActions(filter)
        val pageSize = 3
        val pageCount = ((total + pageSize - 1) / pageSize).toInt()

        val seenBusinessTx = mutableSetOf<Long>()
        var previousPageBoundarySeq: Long? = null
        for (pageIndex in 0 until pageCount) {
            val page = auditJournalQueryService.findGlobalActions(filter, pageIndex, pageSize, direction)
            assertTrue(page.none { it.businessTx in seenBusinessTx }, "page $pageIndex ($direction) overlaps an earlier page")
            seenBusinessTx += page.map { it.businessTx }

            val minSeqOf: (de.littletrotter.application.invoice.InvoiceAuditActionLine) -> Long = { it.rows.minOf { row -> row.seq } }
            // DESC: every action on this page has a min(seq) <= every action on the previous page.
            // ASC: the reverse — page 1 genuinely starts with the globally oldest action.
            val boundary = if (direction == SortDirection.DESC) page.maxOfOrNull(minSeqOf) else page.minOfOrNull(minSeqOf)
            if (previousPageBoundarySeq != null && boundary != null) {
                val inOrder = if (direction == SortDirection.DESC) boundary <= previousPageBoundarySeq!! else boundary >= previousPageBoundarySeq!!
                assertTrue(inOrder, "page $pageIndex ($direction) is not correctly ordered relative to the previous page")
            }
            previousPageBoundarySeq = (if (direction == SortDirection.DESC) page.minOfOrNull(minSeqOf) else page.maxOfOrNull(minSeqOf)) ?: previousPageBoundarySeq
        }
        assertEquals(total, seenBusinessTx.size.toLong(), "paging through every page ($direction) must cover every action exactly once")
    }

    @Test
    fun `global pagination returns stable, non-overlapping pages covering every row exactly once, both ascending and descending`() {
        val tag = "AJ4"
        val parties = buildParties(tag)
        // Five invoices, several rows each (create + item + delivery-date edit) — comfortably more
        // rows than one page, without depending on the exact count another test left behind.
        val invoiceIds = buildFiveInvoicesWithItems(tag, parties)
        val filter = JournalGlobalFilter(actor = "system")

        assertPagingIsStableAndComplete(filter, SortDirection.DESC)
        assertPagingIsStableAndComplete(filter, SortDirection.ASC)

        // Page 1 ascending starts with the globally oldest matching action, not the oldest of
        // whatever page ended up first under the default (DESC) order.
        val total = auditJournalQueryService.countGlobalActions(filter)
        val everythingDesc = auditJournalQueryService.findGlobalActions(filter, 0, total.toInt().coerceAtLeast(1), SortDirection.DESC)
        val globallyOldestSeq = everythingDesc.minOf { line -> line.rows.minOf { it.seq } }
        val firstAscendingPage = auditJournalQueryService.findGlobalActions(filter, 0, 3, SortDirection.ASC)
        assertEquals(globallyOldestSeq, firstAscendingPage.first().rows.minOf { it.seq })

        // Sanity: this suite's own invoices actually contributed rows to what was paged through.
        assertTrue(invoiceIds.all { id -> everythingDesc.any { line -> line.rows.any { it.invoiceId == id } } })
    }
}
