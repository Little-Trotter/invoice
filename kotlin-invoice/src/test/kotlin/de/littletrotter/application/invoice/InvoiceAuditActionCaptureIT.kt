package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.TempDocumentStoreResource
import de.littletrotter.application.compliance.ChainVerificationService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.test.FIXED_INSTANT
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The binding real-Postgres proof: for each application-declared
 * [InvoiceAuditAction], the trigger-written `invoice_audit_journal` rows carry that action, rows
 * of one business transaction share `business_tx`, outermost-wins holds for a method that
 * recalculates internally, a plain-SQL write with no GUC set journals `UNSPECIFIED`/the DB role,
 * and a Kotlin-side chain verification over these trigger-written rows is green. Fixture recipe
 * mirrors [InvoiceSendCompletionIT]'s own `createValidSenderProfile`/`createValidRecipient`/
 * `createMinimalItem`/`createSendableInvoice`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceAuditActionCaptureIT {

    companion object {
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceAuditJournalRepository: InvoiceAuditJournalRepository

    @Inject
    lateinit var tripService: TripService

    @Inject
    lateinit var receiptUploadService: ReceiptUploadService

    @Inject
    lateinit var receiptLinkService: ReceiptLinkService

    @Inject
    lateinit var invoiceCancellationService: InvoiceCancellationService

    @Inject
    lateinit var chainVerificationService: ChainVerificationService

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    private fun createValidSenderProfile(name: String): SenderProfile = aSenderProfile {
        this.name = name
        invoicePrefix = "IAC${uniqueCounter.incrementAndGet()}-"
        email = Email("sender${System.nanoTime()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
        registerCourt = "Amtsgericht Test"
        phone = "+49 30 1234567"
    }.also { sellerProfileService.save(it, defaults = SellerDefaultSelections()) }

    private fun createValidRecipient(name: String): Recipient = aRecipient {
        this.name = name
        email = Email("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
        vatId = VatId("DE987654321")
    }.also { customerManagementService.save(it) }

    private fun createMinimalItem(tag: String): de.littletrotter.domain.invoice.InvoiceItem {
        val taxRate = TaxRate().edit {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "IAC_TEST_19_$tag"
            category = "S"
            isSelectable = false
        }
        em.persist(taxRate)
        em.flush()
        return anInvoiceItem(
            description = "Test Item",
            quantity = BigDecimal("1.0"),
            unitPriceNet = Money.of("100.00"),
            taxRateRef = taxRate,
        )
    }

    private fun findTaxRateId(tag: String): Long =
        (em.createNativeQuery("SELECT id FROM tax_rate WHERE code = :code").setParameter("code", "IAC_TEST_19_$tag").singleResult as Number).toLong()

    /** Draft with one item, ready for [InvoiceLifecycleService.addItem] etc. Returns its id. */
    private fun createDraftInvoice(tag: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = createValidSenderProfile("ACME $tag")
        val recipient = createValidRecipient("Kunde $tag")
        val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
        invoice.id!!
    }

    /** Same recipe as [InvoiceSendCompletionIT.createSendableInvoice] — an IN_REVIEW invoice with
     *  one item, ready to reach ISSUED via the pre-existing `transition(..., overrideReason)` path. */
    private fun createSendableInvoice(tag: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = createValidSenderProfile("ACME $tag")
        val recipient = createValidRecipient("Kunde $tag")
        val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
        invoice.edit { references.buyerReference = "BUYER-REF-$tag" }
        invoice.addItem(createMinimalItem(tag))
        invoice.edit { deliveryDate = LocalDate.now() }
        invoiceRepository.saveAndFlush(invoice)
        invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        invoice.id!!
    }

    private fun rowsFor(invoiceId: Long) =
        invoiceAuditJournalRepository.findByInvoiceId(invoiceId, 0, 100, SortDirection.ASC)

    private fun sessionUser(): String = QuarkusTransaction.requiringNew().call<String> {
        em.createNativeQuery("SELECT session_user").singleResult as String
    }

    // ─── Outermost wins: addItem recalculates internally ────────────────────────

    @Test
    fun `addItem journals ITEM_ADDED on every row of the transaction, never RECALCULATED`() {
        val invoiceId = createDraftInvoice("B1")
        // The draft's own creation rows (INVOICE_CREATED, their own transaction) are not part of
        // the addItem action under test.
        val beforeAddItem = rowsFor(invoiceId).maxOf { it.seq }
        val taxRateId = QuarkusTransaction.requiringNew().call<Long> {
            createMinimalItem("B1")
            findTaxRateId("B1")
        }

        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
                    description = "Item 1",
                    quantity = BigDecimal.ONE,
                    unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.FLAT_RATE,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = taxRateId,
                ),
            )
        }

        val rows = rowsFor(invoiceId).filter { it.seq > beforeAddItem }
        assertTrue(rows.isNotEmpty(), "addItem must have written at least one journal row")
        rows.forEach { row ->
            assertEquals(InvoiceAuditAction.ITEM_ADDED, row.action, "row seq=${row.seq} carries the wrong action")
            assertNotEquals(InvoiceAuditAction.RECALCULATED, row.action, "the internal recalculateTotals() call must never win over the outer addItem action")
        }
        // Every row this one addItem call wrote (the invoice header UPDATE from recalculateTotals
        // plus the new invoice_item INSERT) shares one business_tx.
        assertEquals(1, rows.map { it.businessTx }.toSet().size, "all rows of one addItem call must share one business_tx")
    }

    // ─── A plain-SQL write with no GUC set ───────────────────────────────────────

    @Test
    fun `a plain-SQL write with no GUC set journals action 0 and actor equals the DB role`() {
        val invoiceId = QuarkusTransaction.requiringNew().call<Long> {
            (em.createNativeQuery("INSERT INTO invoice (invoice_date) VALUES ('2026-06-15') RETURNING id").singleResult as Number).toLong()
        }
        // No @SetsAuditGuc-annotated call anywhere in this transaction -- app.actor/app.audit_action
        // are never set, exactly the "outside the application" case the trigger's own fallback
        // (session_user / action 0) exists for.
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET free_text = 'plain-sql' WHERE id = :id")
                .setParameter("id", invoiceId).executeUpdate()
        }

        val row = rowsFor(invoiceId).last()
        assertEquals(InvoiceAuditAction.UNSPECIFIED, row.action)
        assertEquals(sessionUser(), row.actor)

        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice WHERE id = :id").setParameter("id", invoiceId).executeUpdate()
        }
    }

    // ─── occurred_at comes from the TimeSource, never the database clock ────────────────

    @Test
    fun `an app write journals occurred_at exactly equal to the mocked TimeSource reading`() {
        // AppNowConnectionInterceptor stamps app.now from the injected TimeSource on every pooled
        // connection; the trigger then reads occurred_at from that GUC. Fixing the TimeSource here
        // and comparing against it (never against a freshly-read wall clock) is what proves the
        // journal row's timestamp came from the application, not from Postgres's own clock.
        QuarkusMock.installMockForType(ClockTimeSource.fixedAt(FIXED_INSTANT), TimeSource::class.java)

        val invoiceId = createDraftInvoice("APPNOW")

        val row = rowsFor(invoiceId).first()
        assertEquals(FIXED_INSTANT, row.occurredAt.toInstant())
    }

    // ─── per-action rows + shared business_tx ─────────────────────────────────────────

    @Test
    fun `TripService addTrip journals TRIP_SAVED, ReceiptLinkService assign+detach journal RECEIPT_ASSIGNED and RECEIPT_DETACHED`() {
        val invoiceId = createDraftInvoice("TRIP")
        val creationRows = rowsFor(invoiceId)
        creationRows.forEach { assertEquals(InvoiceAuditAction.INVOICE_CREATED, it.action) }
        val beforeTrip = creationRows.maxOf { it.seq }

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "Kundentermin",
                    purpose = "Beratung",
                    periodFrom = LocalDate.now(),
                    periodTo = LocalDate.now(),
                    fixedNetPrice = Money.of("25.00"),
                ),
            )
        }
        val tripRows = rowsFor(invoiceId).filter { it.seq > beforeTrip }
        assertTrue(tripRows.isNotEmpty())
        tripRows.forEach { assertEquals(InvoiceAuditAction.TRIP_SAVED, it.action) }
        assertEquals(1, tripRows.map { it.businessTx }.toSet().size)

        val itemId = QuarkusTransaction.requiringNew().call<Long> {
            invoiceRepository.findByIdWithItems(invoiceId)!!.itemList.first().id!!
        }
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val receiptId = QuarkusTransaction.requiringNew().call<Long> {
            receiptUploadService.upload("beleg.pdf", "Testbeleg", pdfBytes, LocalDate.now()).id!!
        }

        val beforeAssign = tripRows.maxOf { it.seq }
        QuarkusTransaction.requiringNew().run { receiptLinkService.addReceiptsToItem(itemId, listOf(receiptId)) }
        val assignRows = rowsFor(invoiceId).filter { it.seq > beforeAssign }
        assertTrue(assignRows.isNotEmpty(), "assigning a receipt must journal at least one row")
        assignRows.forEach { assertEquals(InvoiceAuditAction.RECEIPT_ASSIGNED, it.action) }

        val beforeDetach = assignRows.maxOf { it.seq }
        QuarkusTransaction.requiringNew().run { receiptLinkService.removeReceiptsFromItem(itemId, listOf(receiptId)) }
        val detachRows = rowsFor(invoiceId).filter { it.seq > beforeDetach }
        assertTrue(detachRows.isNotEmpty(), "detaching a receipt must journal at least one row")
        detachRows.forEach { assertEquals(InvoiceAuditAction.RECEIPT_DETACHED, it.action) }
    }

    @Test
    fun `a Cancellation journals CANCELLATION on both the original and the credit note under one business_tx`() {
        val invoiceId = createSendableInvoice("STORNO")
        QuarkusTransaction.requiringNew().run {
            invoiceLifecycleService.transition(invoiceId, InvoiceStatus.ISSUED, "IT override -- no validation pipeline here")
        }

        val creditNoteId = QuarkusTransaction.requiringNew().call<Long> {
            invoiceCancellationService.createCreditNote(invoiceId, "IT-Storno-Grund").id!!
        }

        val originalRows = rowsFor(invoiceId).filter { it.action == InvoiceAuditAction.CANCELLATION }
        val creditNoteRows = rowsFor(creditNoteId).filter { it.action == InvoiceAuditAction.CANCELLATION }
        assertTrue(originalRows.isNotEmpty(), "the original invoice must carry at least one STORNO row (its ISSUED -> CANCELLED transition)")
        assertTrue(creditNoteRows.isNotEmpty(), "the credit note must carry at least one STORNO row (its own creation)")

        val allBusinessTx = (originalRows + creditNoteRows).map { it.businessTx }.toSet()
        assertEquals(1, allBusinessTx.size, "one storno call touches two invoices in one transaction -- both sides must share one business_tx")
    }

    // ─── The last position of a Draft/Review invoice can be deleted ─────────────────────

    private fun unitFor(category: de.littletrotter.shared.catalogue.InvoiceItemCategory) = when (category) {
        de.littletrotter.shared.catalogue.InvoiceItemCategory.PRODUCT -> de.littletrotter.shared.catalogue.InvoiceItemUnit.PIECE
        de.littletrotter.shared.catalogue.InvoiceItemCategory.UNIT_PRICE -> de.littletrotter.shared.catalogue.InvoiceItemUnit.FLAT_RATE
        else -> de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR
    }

    @ParameterizedTest
    @EnumSource(value = de.littletrotter.shared.catalogue.InvoiceItemCategory::class, names = ["PRODUCT", "UNIT_PRICE", "SERVICE"])
    fun `deleting the only position of a DRAFT and of an IN_REVIEW invoice leaves an empty invoice with zero totals and an ITEM_DELETED row`(
        category: de.littletrotter.shared.catalogue.InvoiceItemCategory,
    ) {
        listOf(InvoiceStatus.DRAFT, InvoiceStatus.IN_REVIEW).forEach { status ->
            val tag = "T14${status.name.take(1)}${category.ordinal}"
            val invoiceId = createDraftInvoice(tag)
            val taxRateId = QuarkusTransaction.requiringNew().call<Long> {
                createMinimalItem(tag)
                findTaxRateId(tag)
            }
            QuarkusTransaction.requiringNew().run {
                itemMutationService.addItem(
                    invoiceId,
                    InvoiceItemCommand(
                        category = category,
                        description = "Only position",
                        quantity = BigDecimal.ONE,
                        unit = unitFor(category),
                        unitPriceNet = Money.of("100.00"),
                        taxRateId = taxRateId,
                    ),
                )
            }
            if (status == InvoiceStatus.IN_REVIEW) {
                QuarkusTransaction.requiringNew().run { invoiceLifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
            }
            val beforeDelete = rowsFor(invoiceId).maxOf { it.seq }

            QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, "1") }

            QuarkusTransaction.requiringNew().run {
                val reloaded = invoiceRepository.findByIdWithItems(invoiceId)!!
                assertTrue(reloaded.itemList.isEmpty(), "$category in $status: items must be empty")
                assertEquals(status, reloaded.status)
                assertEquals(0, reloaded.totals.netAmount.amount.compareTo(BigDecimal.ZERO), "$category in $status: net")
                assertEquals(0, reloaded.totals.vatAmount.amount.compareTo(BigDecimal.ZERO), "$category in $status: vat")
                assertEquals(0, reloaded.totals.grossAmount.amount.compareTo(BigDecimal.ZERO), "$category in $status: gross")
            }
            val rows = rowsFor(invoiceId).filter { it.seq > beforeDelete }
            assertTrue(rows.any { it.action == InvoiceAuditAction.ITEM_DELETED }, "$category in $status: ITEM_DELETED row, got ${rows.map { it.action }}")
        }
    }

    @Test
    fun `an ISSUED invoice still refuses to delete its position`() {
        val invoiceId = createSendableInvoice("T14S")
        QuarkusTransaction.requiringNew().run {
            invoiceLifecycleService.transition(invoiceId, InvoiceStatus.ISSUED, "IT override -- no validation pipeline here")
        }

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, "1") }
        }
    }

    @Test
    fun `an IN_REVIEW invoice without positions is refused at ISSUED with NO_ITEMS and stays IN_REVIEW`() {
        val invoiceId = createDraftInvoice("T14Z")
        QuarkusTransaction.requiringNew().run { invoiceLifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().run { invoiceLifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        }

        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.NO_ITEMS, ex.error)
        assertEquals(InvoiceStatus.IN_REVIEW, QuarkusTransaction.requiringNew().call<InvoiceStatus> { invoiceRepository.findById(invoiceId)!!.status })
    }

    // ─── Kotlin-side chain verification over trigger-written rows ────────────

    @Test
    fun `a clean run, including these action+business_tx rows, verifies with no chain break`() {
        val invoiceId = createDraftInvoice("CHAIN")
        val taxRateId = QuarkusTransaction.requiringNew().call<Long> {
            createMinimalItem("CHAIN")
            findTaxRateId("CHAIN")
        }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
                    description = "Chain item",
                    quantity = BigDecimal.ONE,
                    unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.FLAT_RATE,
                    unitPriceNet = Money.of("10.00"),
                    taxRateId = taxRateId,
                ),
            )
        }

        val outcome = chainVerificationService.verifyAllChains()

        assertFalse(outcome.hasBreak, "the invoice_audit_journal chain, action+business_tx included, must verify clean")
        assertTrue(outcome.invoiceAudit.checked > 0)
    }
}
