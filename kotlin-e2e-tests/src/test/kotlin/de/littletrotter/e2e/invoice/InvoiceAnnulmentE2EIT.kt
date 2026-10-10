package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * PO decision 2026-08-31 (Annullierung) — [InvoiceStatus.VOIDED] end-to-end: a `DRAFT`/
 * `IN_REVIEW` invoice discarded before it was ever sent gets no credit note (it never reaches
 * `SENT`, so [de.littletrotter.application.invoice.InvoiceStornoService] never applies), no
 * invoice number (one is only assigned at the `SENT` transition), and no more
 * "E-Rechnungs-Auftrag" ([GenerationJobService.createJob] rejects it — see that method's own
 * KDoc). Same suite shape as [InvoiceDetailFacadeStornoE2EIT]: drives the real, CDI-injected
 * [ChangeInvoiceStatusUseCase]/[InvoiceDetailQueryPort]/[InvoiceLifecycleService]/[GenerationJobService], not mocks. Component-level
 * button visibility is pinned by `InvoiceDetailViewTest`/`InvoiceDetailViewStornoActionTest` in
 * kotlin-invoice; the transition-matrix itself is pinned exhaustively by
 * [de.littletrotter.domain.invoice.InvoiceStatusTest].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceAnnulmentE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var changeInvoiceStatus: ChangeInvoiceStatusUseCase

    @Inject
    lateinit var invoiceDetailQuery: InvoiceDetailQueryPort

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var overviewQueryService: InvoiceOverviewQueryService

    @Inject
    lateinit var statisticsQuery: InvoiceStatisticsQueryPort

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

    /** A `DRAFT` invoice with one item, for [parties] — the same sender/recipient/tax rate may be
     *  reused across several invoices (e.g. to prove a gapless number sequence), unlike
     *  [draftInvoice] which creates a fresh, single-use sender per call. */
    private fun draftInvoiceFor(parties: Parties, tag: String): Long {
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        return invoiceId
    }

    /** Same fixture idiom as [InvoiceDetailFacadeStornoE2EIT.sentInvoice], minus the transitions
     *  all the way to `SENT` — this suite needs its invoices to stay `DRAFT`/`IN_REVIEW`. A
     *  fresh, single-use sender per call (its `invoicePrefix` is unique) — see [draftInvoiceFor]
     *  for the variant that reuses one sender across several invoices. */
    private fun draftInvoice(tag: String): Long = draftInvoiceFor(buildParties(tag), tag)

    private fun statusOf(invoiceId: Long): InvoiceStatus =
        QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, invoiceId).status }

    private fun invoiceNumberAssigned(invoiceId: Long): Boolean =
        QuarkusTransaction.requiringNew().call<Boolean> { em.find(Invoice::class.java, invoiceId).invoiceNumber.isAssigned() }

    // ── Happy paths ──────────────────────────────────────────────────────────

    @Test
    fun `a DRAFT invoice can be voided through the real input ports and becomes read-only`() {
        val invoiceId = draftInvoice("VOID1")
        assertEquals(InvoiceStatus.DRAFT, statusOf(invoiceId))

        val dto = changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        assertEquals(InvoiceStatus.VOIDED.ordinal, dto.status)
        assertEquals(InvoiceStatus.VOIDED, statusOf(invoiceId))
    }

    @Test
    fun `an IN_REVIEW invoice can be voided through the real input ports`() {
        val invoiceId = draftInvoice("VOID2")
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.IN_REVIEW)
        assertEquals(InvoiceStatus.IN_REVIEW, statusOf(invoiceId))

        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        assertEquals(InvoiceStatus.VOIDED, statusOf(invoiceId))
    }

    @Test
    fun `voiding keeps its create-time invoice number unchanged and never creates a credit note`() {
        val invoiceId = draftInvoice("VOID3")
        val numberAtCreation = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, invoiceId).invoiceNumber.value }

        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        // N5 — every invoice is numbered at create(), not at -> SENT; a voided invoice already had one before it was ever voided, and voiding neither draws a new one nor clears it — the row and its number are kept, never deleted, which is what keeps the sequence gapless now.
        val numberAfterVoid = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, invoiceId).invoiceNumber.value }
        assertTrue(numberAfterVoid.isNotBlank(), "N5 — a voided invoice still carries the number it drew at create()")
        assertEquals(numberAtCreation, numberAfterVoid, "voiding must not redraw or clear the number")
        assertFalse(
            invoiceDetailQuery.canCreateCreditNote(invoiceId),
            "a voided invoice was never SENT, so InvoiceStornoService must never offer a Storno for it",
        )
    }

    @Test
    fun `voiding blocks any further E-Rechnungs-Auftrag with a readable German message`() {
        val invoiceId = draftInvoice("VOID4")
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        val ex = assertThrows(InvoiceVoidedGenerationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        }
        assertTrue(ex.message!!.contains("annulliert"))
    }

    // ── Border cases ─────────────────────────────────────────────────────────

    @Test
    fun `a SENT invoice can no longer be voided`() {
        val invoiceId = draftInvoice("VOID5")
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.IN_REVIEW)
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId).deliveryDate = LocalDate.now() }
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.SENT)

        assertThrows(InvoiceStatusTransitionException::class.java) {
            changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)
        }
        assertEquals(InvoiceStatus.SENT, statusOf(invoiceId))
    }

    @Test
    fun `a voided invoice cannot transition anywhere else`() {
        val invoiceId = draftInvoice("VOID6")
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        listOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW, InvoiceStatus.SENT).forEach { target ->
            assertThrows(InvoiceStatusTransitionException::class.java) {
                changeInvoiceStatus.transitionInvoice(invoiceId, target)
            }
        }
    }

    // The assertion that catches a regression in the number service: a draft draws its number
    // at create() now, not at -> SENT, so voiding no longer means "never drew one" — it means
    // "the drawn number is never orphaned." Gaplessness comes from InvoiceLifecycleService no
    // longer offering a hard delete:
    // every drawn number ends up on exactly one persisted row (SENT or VOIDED alike), so the
    // counter's total advance always equals the number of invoices actually created, no more no
    // less — regardless of how many of them were later voided. Asserted RELATIVELY against the
    // number_counter total (see the original comment this replaces for why: the seeded
    // INVOICE_GAPLESS range is scope=GLOBAL, so absolute sequence values are suite-order-dependent).
    @Test
    fun `voided invoices leave no gap - every draft draws a number at create(), voiding never orphans it`() {
        val tag = "VOID8"
        val parties = buildParties(tag)

        // Scoped to the invoice range: a SUM over ALL counters would also move whenever a fixture
        // draws a customer/article/seller number, smuggling the order-dependence back in sideways.
        fun counterTotal(): Long = QuarkusTransaction.requiringNew().call {
            (
                em.createNativeQuery(
                    "SELECT COALESCE(SUM(nc.last_value), 0) FROM number_counter nc " +
                        "JOIN number_range nr ON nr.id = nc.number_range_id WHERE nr.subject = 0",
                ).singleResult as Number
                ).toLong()
        }
        val counterBeforeVoids = counterTotal()

        val firstDraftId = draftInvoiceFor(parties, tag)
        val firstNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, firstDraftId).invoiceNumber.value }
        changeInvoiceStatus.transitionInvoice(firstDraftId, InvoiceStatus.VOIDED)
        assertTrue(invoiceNumberAssigned(firstDraftId), "N5 — the number was drawn at create(), voiding does not clear it")

        val secondDraftId = draftInvoiceFor(parties, tag)
        val secondNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, secondDraftId).invoiceNumber.value }
        changeInvoiceStatus.transitionInvoice(secondDraftId, InvoiceStatus.IN_REVIEW)
        changeInvoiceStatus.transitionInvoice(secondDraftId, InvoiceStatus.VOIDED)
        assertTrue(invoiceNumberAssigned(secondDraftId), "N5 — the number was drawn at create(), voiding does not clear it")
        assertNotEquals(firstNumber, secondNumber, "two distinct drafts draw two distinct, gapless sequence values")

        val counterAfterVoids = counterTotal()
        assertEquals(
            counterBeforeVoids + 2, counterAfterVoids,
            "N5 — both drafts drew a number at create(); voiding neither of them moves the counter again",
        )

        val realInvoiceId = draftInvoiceFor(parties, tag)
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, realInvoiceId).deliveryDate = LocalDate.now() }
        changeInvoiceStatus.transitionInvoice(realInvoiceId, InvoiceStatus.IN_REVIEW)
        changeInvoiceStatus.transitionInvoice(realInvoiceId, InvoiceStatus.SENT)

        val counterAfterSend = counterTotal()
        assertEquals(
            counterAfterVoids + 1, counterAfterSend,
            "the third invoice's own create() draws exactly one more number; -> SENT draws none (already numbered)",
        )

        // Gaplessness on the entity itself: the recorded sequence is exactly the value the shared
        // GLOBAL counter advanced to — a drawn-but-unrecorded number would leave no trace on any
        // invoice row, which is what this second read pins. Holds for the two voided rows too:
        // neither was ever deleted, so neither drawn number is orphaned.
        val sequence = QuarkusTransaction.requiringNew().call<Long?> {
            em.find(Invoice::class.java, realInvoiceId).invoiceNumberSequence
        }
        assertEquals(
            counterAfterSend, sequence,
            "the entity's recorded sequence must match the counter the allocation advanced",
        )
    }

    // The invoice still appears in the list and in the statistics under its own status: both
    // InvoiceOverviewQueryService (the list page) and
    // InvoiceStatisticsQueryPort.getStatusDistribution (the statistics page) read a live
    // Postgres view, not a materialized/cached one, so a VOIDED invoice must show up in both the
    // moment it transitions — never hidden or dropped merely because it is terminal.
    @Test
    fun `a voided invoice appears in the overview list and in the status distribution under VOIDED`() {
        val invoiceId = draftInvoice("VOID9")
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        val listed = overviewQueryService.findOverview(0, 100, null, InvoiceStatus.VOIDED)
        assertTrue(listed.any { it.id == invoiceId }, "a VOIDED invoice must still appear in the overview list, filtered by its own status")

        val distribution = statisticsQuery.getStatusDistribution(StatisticsFilter.EMPTY)
        val voidedCount = distribution.firstOrNull { it.status == InvoiceStatus.VOIDED }?.count ?: 0L
        assertTrue(voidedCount >= 1L, "the status distribution must count this invoice under VOIDED")
    }

    @Test
    fun `a voided invoice can no longer be edited`() {
        val invoiceId = draftInvoice("VOID7")
        changeInvoiceStatus.transitionInvoice(invoiceId, InvoiceStatus.VOIDED)

        assertThrows(InvoiceImmutableException::class.java) {
            QuarkusTransaction.requiringNew().call {
                itemMutationService.addItem(
                    invoiceId,
                    InvoiceItemCommand(
                        category = InvoiceItemCategory.SERVICE,
                        description = "Nachtrag nach Annullierung",
                        quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.HOUR,
                        unitPriceNet = Money.of("50.00"),
                    ),
                )
            }
        }
    }
}
