package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.TempDocumentStoreResource
import de.littletrotter.application.dto.InvoiceFromQuoteResult
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceFromQuoteUseCase
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.QuoteAttachmentQueryService
import de.littletrotter.application.quote.port.inbound.QuoteB2bValue
import de.littletrotter.application.quote.QuoteDetailQueryService
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.QuoteDocumentFileQueryService
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.QuoteDocumentQueryService
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.ui.view.quote.quoteDetail
import de.littletrotter.ui.view.quote.quoteItem
import java.math.BigDecimal
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepted offer to invoice draft against a real Postgres: numbering from the active range,
 * parties and references from the offer rather than from master data, receipts with the right
 * checksums, the change journal naming the accepting person, idempotency and the two refusals
 * that must leave no trace. The offer service itself is replaced by mocks of the four read ports.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class CreateInvoiceFromQuoteIT {

    companion object {
        private val counter = AtomicInteger(0)
    }

    @Inject
    lateinit var useCase: CreateInvoiceFromQuoteUseCase

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoices: InvoiceRepository

    @Inject
    lateinit var journal: InvoiceAuditJournalRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var senderProfiles: SenderProfileRepository

    @Inject
    lateinit var customers: CustomerManagementService

    @Inject
    lateinit var b2bValues: B2bFieldValueService

    private var previousDefaultId: Long? = null

    private val details: QuoteDetailQueryService = mock()
    private val documents: QuoteDocumentQueryService = mock()
    private val documentFiles: QuoteDocumentFileQueryService = mock()
    private val attachments: QuoteAttachmentQueryService = mock()

    @BeforeEach
    fun installOfferMocks() {
        QuarkusMock.installMockForType(details, QuoteDetailQueryService::class.java)
        QuarkusMock.installMockForType(documents, QuoteDocumentQueryService::class.java)
        QuarkusMock.installMockForType(documentFiles, QuoteDocumentFileQueryService::class.java)
        QuarkusMock.installMockForType(attachments, QuoteAttachmentQueryService::class.java)
        // Other ITs share this database and may have left a default sender without IBAN or VAT id,
        // so the offer-to-invoice flow gets its own complete default and hands the old one back.
        previousDefaultId = QuarkusTransaction.requiringNew().call<Long?> { senderProfiles.findDefault()?.id }
        val own = QuarkusTransaction.requiringNew().call<SenderProfile> {
            SenderProfile().apply {
                name = "Angebots-Absender ${System.nanoTime()}"
                invoicePrefix = "QDEF${counter.incrementAndGet()}${System.nanoTime() % 1_000_000}-"
                email = Email("default${System.nanoTime()}@example.invalid")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban("DE89370400440532013000")
                registerCourt = "Amtsgericht Test"
                phone = "+49 30 1234567"
            }.let { sellerProfileService.save(it, defaults = SellerDefaultSelections()) }
        }
        makeDefault(own.id!!)
    }

    @AfterEach
    fun restorePreviousDefaultSender() {
        val previousId = previousDefaultId ?: return
        makeDefault(previousId)
    }

    /** Detached on purpose: a managed profile flagged as default would be flushed before the old default is revoked. */
    private fun makeDefault(id: Long) {
        val profile = checkNotNull(sellerProfileService.findByIdForEdit(id)) { "sender profile $id vanished" }
        profile.isDefault = true
        sellerProfileService.save(profile, defaults = SellerDefaultSelections())
    }

    private class Offer(val quoteId: UUID, val customerId: Long, val offerPdf: ByteArray, val attachmentPdf: ByteArray)

    private fun pdf(tag: String) = "%PDF-1.4\n% $tag ${UUID.randomUUID()}\n".toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun freeHeaderTerm() = B2bTerm.entries.first { it.binding == B2bTermBinding.FREE && it.level == B2bTermLevel.HEADER }

    /** An accepted offer for a freshly created customer whose master data name differs from the offer's party name. */
    private fun givenOffer(
        withDocument: Boolean = true,
        customerVatId: String = "DE987654321",
        itemCategories: List<VatCategory> = listOf(VatCategory.STANDARD, VatCategory.STANDARD),
    ): Offer {
        val n = counter.incrementAndGet()
        val foreign = !customerVatId.startsWith("DE")
        val customer = QuarkusTransaction.requiringNew().call<Recipient> {
            Recipient().apply {
                name = "Stammdaten Kunde $n"
                email = Email("kunde$n${System.nanoTime()}@example.invalid")
                customerType = if (foreign) CustomerType.BUSINESS else CustomerType.CONSUMER
                vatId = VatId(customerVatId)
                if (foreign) vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            }.also { customers.save(it) }
        }
        val quoteId = UUID.randomUUID()
        val id = quoteId.toString()
        val offerPdf = pdf("angebot")
        val attachmentPdf = pdf("anhang")
        val detail = quoteDetail(id = id) {
            it.copy(
                party = it.party.copy(customerId = customer.id.toString(), name = "Angebots Kunde $n", vatId = customerVatId),
                items = itemCategories.mapIndexed { index, category ->
                    val rate = if (category == VatCategory.STANDARD) BigDecimal("19.00") else BigDecimal.ZERO.setScale(2)
                    quoteItem(index + 1).copy(vatCategory = category, vatRate = rate)
                },
                b2bValues = listOf(QuoteB2bValue(11, "PRJ-$n"), QuoteB2bValue(freeHeaderTerm().code, "frei-$n")),
            )
        }
        whenever(details.find(id)).thenReturn(detail)
        whenever(documents.documents(id)).thenReturn(
            if (withDocument) {
                listOf(QuoteDocument("d1", QuoteDocumentMark.DISPATCH, QuoteDocumentStatus.COMPLETED, Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T10:00:05Z")))
            } else {
                emptyList()
            },
        )
        whenever(documentFiles.pdf(id, "d1")).thenReturn(QuoteDocumentContent("angebot.pdf", offerPdf))
        whenever(attachments.attachments(id)).thenReturn(
            listOf(
                QuoteAttachment("a1", QuoteAttachmentKind.CUSTOMER, "datenblatt.pdf", "application/pdf", 10, "x", Instant.parse("2026-10-02T09:00:00Z")),
                QuoteAttachment("a2", QuoteAttachmentKind.CUSTOMER, "foto.png", "image/png", 10, "x", Instant.parse("2026-10-02T09:00:00Z")),
                QuoteAttachment("a3", QuoteAttachmentKind.INTERNAL, "kalkulation.pdf", "application/pdf", 10, "x", Instant.parse("2026-10-02T09:00:00Z")),
            ),
        )
        whenever(attachments.content(id, "a1")).thenReturn(QuoteAttachmentContent("datenblatt.pdf", "application/pdf", attachmentPdf))
        return Offer(quoteId, customer.id!!, offerPdf, attachmentPdf)
    }

    private fun command(offer: Offer, orderReference: String? = "BESTELL-7") = CreateInvoiceFromQuoteCommand(
        quoteId = offer.quoteId,
        quoteNumber = "AN-2026-0042",
        acceptedOn = LocalDate.of(2026, 10, 5),
        customerOrderReference = orderReference,
        actor = "angebots-sachbearbeiter",
        orderId = UUID.randomUUID(),
    )

    private fun assertCreated(result: InvoiceFromQuoteResult): InvoiceFromQuoteResult.Created {
        val reason = (result as? InvoiceFromQuoteResult.Rejected)?.reason
        return assertInstanceOf(InvoiceFromQuoteResult.Created::class.java, result, "expected Created but was Rejected: $reason")
    }

    private fun count(sql: String, vararg params: Pair<String, Any>): Long =
        (em.createNativeQuery(sql).also { q -> params.forEach { q.setParameter(it.first, it.second) } }.singleResult as Number).toLong()

    private fun invoiceCountFor(quoteId: UUID) = count("SELECT count(*) FROM invoice WHERE source_quote_id = :q", "q" to quoteId)

    private fun replyCount(subject: String, quoteId: UUID) =
        count("SELECT count(*) FROM outbox WHERE subject = :s AND aggregate_id = :a", "s" to subject, "a" to quoteId.toString())

    @Test
    fun `an accepted offer becomes a numbered draft that states what the offer said`() {
        val offer = givenOffer()

        val result = useCase.create(command(offer)).let(::assertCreated)

        assertTrue(!result.alreadyExisted)
        val invoice = invoices.findByIdWithItems(result.invoiceId)!!
        assertEquals(InvoiceStatus.DRAFT, invoice.status)
        assertTrue(invoice.invoiceNumber.isAssigned(), "the number comes from the active range")
        assertEquals(result.invoiceNumber, invoice.invoiceNumber.value)
        assertEquals(offer.quoteId, invoice.sourceQuoteId)
        assertEquals(2, invoice.itemList.size)
        // the snapshot carries the offer's party, not what master data says today
        assertTrue(invoice.buyerSnapshot()!!.name.startsWith("Angebots Kunde"))
        assertEquals("AN-2026-0042", invoice.references.orderReference)
        assertEquals(LocalDate.of(2026, 10, 5), invoice.references.orderReferenceDate)
        assertEquals("AN-2026-0042", invoice.references.offerReference)
        assertEquals(LocalDate.of(2026, 10, 1), invoice.references.offerReferenceDate)
        assertEquals("BESTELL-7", invoice.references.purchaseOrderReference)
        assertTrue(invoice.references.projectNumber!!.startsWith("PRJ-"))
        val stored = b2bValues.findFieldValues(B2bFieldOwnerType.INVOICE, result.invoiceId)
        assertTrue(stored[freeHeaderTerm().officialCode]!!.startsWith("frei-"))
    }

    @Test
    fun `an offer of reverse charge positions for a customer from another EU country becomes a reverse charge draft`() {
        val offer = givenOffer(customerVatId = "ATU12345678", itemCategories = listOf(VatCategory.REVERSE_CHARGE, VatCategory.REVERSE_CHARGE))

        val result = useCase.create(command(offer)).let(::assertCreated)

        val invoice = invoices.findByIdWithItems(result.invoiceId)!!
        assertTrue(invoice.reverseCharge)
        assertEquals(2, invoice.itemList.size)
    }

    @Test
    fun `an offer that mixes reverse charge with other categories for a customer from another EU country is refused`() {
        val offer = givenOffer(customerVatId = "ATU12345678", itemCategories = listOf(VatCategory.REVERSE_CHARGE, VatCategory.STANDARD))

        val result = useCase.create(command(offer))

        assertTrue(result is InvoiceFromQuoteResult.Rejected)
        assertEquals(0, invoiceCountFor(offer.quoteId))
        assertEquals(1, replyCount("invoice.creation-from-quote.rejected", offer.quoteId))
    }

    @Test
    fun `the change journal records one creation from the offer in the name of the accepting person`() {
        val offer = givenOffer()

        val result = useCase.create(command(offer)).let(::assertCreated)

        val rows = journal.findByInvoiceId(result.invoiceId, 0, 200, SortDirection.ASC)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.action == InvoiceAuditAction.CREATED_FROM_QUOTE }, "every row of the creation carries the offer action: ${rows.map { it.action }}")
        assertEquals(1, rows.map { it.businessTx }.toSet().size, "the whole draft is one business transaction")
        assertTrue(rows.all { it.actor == "angebots-sachbearbeiter" }, "actor: ${rows.map { it.actor }}")
    }

    @Test
    fun `the offer document and the customer pdf become receipts with matching checksums, other files stay behind`() {
        val offer = givenOffer()

        val result = useCase.create(command(offer)).let(::assertCreated)

        val checksums = em.createNativeQuery(
            "SELECT r.sha256_checksum FROM receipt r JOIN invoice_receipt ir ON ir.receipt_id = r.id WHERE ir.invoice_id = :i",
        ).setParameter("i", result.invoiceId).resultList.map { it as String }
        assertEquals(setOf(sha256(offer.offerPdf), sha256(offer.attachmentPdf)), checksums.toSet())
    }

    @Test
    fun `a repeated acceptance creates nothing and answers the earlier draft again`() {
        val offer = givenOffer()
        val first = useCase.create(command(offer)).let(::assertCreated)

        val second = useCase.create(command(offer)).let(::assertCreated)

        assertTrue(second.alreadyExisted)
        assertEquals(first.invoiceId, second.invoiceId)
        assertEquals(1, invoiceCountFor(offer.quoteId))
        assertEquals(2, replyCount("invoice.created-from-quote", offer.quoteId))
    }

    @Test
    fun `an offer without a finished document is refused and leaves no invoice and no receipt`() {
        val offer = givenOffer(withDocument = false)
        val receiptsBefore = count("SELECT count(*) FROM receipt")

        val result = useCase.create(command(offer))

        assertTrue(result is InvoiceFromQuoteResult.Rejected)
        assertEquals(0, invoiceCountFor(offer.quoteId))
        assertEquals(receiptsBefore, count("SELECT count(*) FROM receipt"))
        assertEquals(1, replyCount("invoice.creation-from-quote.rejected", offer.quoteId))
    }

    @Test
    fun `without an active invoice number range the offer is refused and no draft survives`() {
        val offer = givenOffer()
        val receiptsBefore = count("SELECT count(*) FROM receipt")
        val activeRanges = QuarkusTransaction.requiringNew().call<List<Long>> {
            em.createNativeQuery("SELECT id FROM number_range WHERE subject = 0 AND is_active").resultList.map { (it as Number).toLong() }
        }
        QuarkusTransaction.requiringNew().run { em.createNativeQuery("UPDATE number_range SET is_active = FALSE WHERE subject = 0").executeUpdate() }
        try {
            val result = useCase.create(command(offer))

            assertNotNull(result as? InvoiceFromQuoteResult.Rejected)
            assertEquals(0, invoiceCountFor(offer.quoteId))
            assertEquals(receiptsBefore, count("SELECT count(*) FROM receipt"))
            assertEquals(1, replyCount("invoice.creation-from-quote.rejected", offer.quoteId))
        } finally {
            QuarkusTransaction.requiringNew().run {
                activeRanges.forEach { em.createNativeQuery("UPDATE number_range SET is_active = TRUE WHERE id = :id").setParameter("id", it).executeUpdate() }
            }
        }
    }
}
