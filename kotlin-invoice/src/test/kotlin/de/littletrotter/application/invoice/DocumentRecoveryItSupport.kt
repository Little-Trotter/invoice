package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** An issued invoice with one COMPLETED issued job whose PDF object is in the document store. */
class IssuedInvoiceFixture(
    val invoiceId: Long,
    val number: String,
    val jobId: Long,
    val pdfKey: String,
    val pdfBytes: ByteArray,
    val pdfSha: String,
    val documentId: Long,
)

/**
 * Builds the persisted state every document recovery integration test starts from, with synthetic
 * data only: an issued invoice, its completed issued job, the `invoice_document` row the
 * completion persisted, and the PDF object in the real document store.
 */
abstract class DocumentRecoveryItSupport {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var documentStore: DocumentStore

    private fun nextNumber(): String = "ABS3-2026-10-%05d".format(counter.incrementAndGet())

    private fun persistInvoice(number: String, sent: Boolean, kind: InvoiceKind): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = SenderProfile().apply {
            name = "Abschrift Muster GmbH"
            email = Email("billing@abschrift.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "AB${UUID.randomUUID().toString().take(8)}-"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-$number")
            name = "Abschrift Kunde AG"
            email = Email("kunde@abschrift.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "IT_ABS3_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            forceStatusForTest(kind.status)
            typeCode = kind.typeCode
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
            mailSent = sent
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
        }
        val item = InvoiceItem().apply {
            positionId = "1"
            description = "Beratung"
            quantity = BigDecimal("1.00")
            unitPriceNet = Money(BigDecimal("100.00"))
            netAmount = Money(BigDecimal("100.00"))
            vatAmount = Money(BigDecimal("19.00"))
            grossAmount = Money(BigDecimal("119.00"))
            taxRateRef = taxRate
        }
        invoice.itemList.asMutableForTest().add(item.also { it.invoice = invoice })
        em.persist(invoice)
        em.flush()
        invoice
    }

    /** [sent] is the invoice's mail projection; the PDF bytes are unique per call. */
    protected fun issuedInvoice(sent: Boolean, kind: InvoiceKind = InvoiceKind()): IssuedInvoiceFixture {
        val number = nextNumber()
        val invoice = persistInvoice(number, sent, kind)
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)
        val jobId = requireNotNull(job.id)
        val bytes = "%PDF-1.4 synthetic ${UUID.randomUUID()}".toByteArray()
        val key = jobKey(number, invoice.id, jobId.toString())
        documentStore.put(key, bytes, "application/pdf")
        val sha = Sha256Hex.of(bytes)
        generationJobResultHandler.markCompleted(job, """{"objectKey":"$key","sha256":"$sha"}""")
        val documentId = QuarkusTransaction.requiringNew().call<Long> {
            (em.createNativeQuery("SELECT id FROM invoice_document WHERE object_key = :k").setParameter("k", key).singleResult as Number).toLong()
        }
        return IssuedInvoiceFixture(requireNotNull(invoice.id), number, jobId, key, bytes, sha, documentId)
    }

    /** A voided invoice: terminal, never issued, so it has no job and no document. */
    protected fun voidedInvoice(): Long =
        requireNotNull(persistInvoice(nextNumber(), false, InvoiceKind(InvoiceStatus.VOIDED)).id)

    /** Puts an already persisted fixture invoice into the terminal VOIDED state, keeping its job and document rows. */
    protected fun markVoided(invoiceId: Long) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET status = :s WHERE id = :id")
                .setParameter("s", InvoiceStatus.VOIDED.ordinal).setParameter("id", invoiceId).executeUpdate()
        }
    }

    protected fun jobKey(number: String, invoiceId: Long?, jobId: String): String =
        DocumentKeyRules.deriveJobExportKeys(number, invoiceId, jobId, draft = false).pdfObjectKey

    protected fun documentRow(documentId: Long): DocumentRow = QuarkusTransaction.requiringNew().call<DocumentRow> {
        val r = em.createNativeQuery(
            "SELECT generation_status, reproduced, reproduction_of_sha256, sha256_checksum FROM invoice_document WHERE id = :id",
        ).setParameter("id", documentId).singleResult as Array<*>
        DocumentRow((r[0] as Number).toInt(), r[1] as Boolean, r[2] as String?, r[3] as String?)
    }

    protected fun documentIdByKey(key: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        (em.createNativeQuery("SELECT id FROM invoice_document WHERE object_key = :k").setParameter("k", key).singleResult as Number).toLong()
    }

    protected fun jobsOf(invoiceId: Long, trigger: Int): List<Long> = QuarkusTransaction.requiringNew().call<List<Long>> {
        em.createNativeQuery("SELECT id FROM generation_job WHERE invoice_id = :id AND trigger_type = :t ORDER BY id")
            .setParameter("id", invoiceId).setParameter("t", trigger).resultList.map { (it as Number).toLong() }
    }

    /** Journal rows of the invoice for [action], oldest first: action context plus actor. */
    protected fun journal(invoiceId: Long, action: de.littletrotter.domain.compliance.InvoiceAuditAction): List<JournalRow> =
        QuarkusTransaction.requiringNew().call<List<JournalRow>> {
            em.createNativeQuery(
                "SELECT actor, detail->'_context' FROM invoice_audit_journal WHERE invoice_id = :id AND action = :a ORDER BY seq",
            ).setParameter("id", invoiceId).setParameter("a", action.ordinal).resultList.map {
                val r = it as Array<*>
                JournalRow(r[0] as String, r[1]?.toString().orEmpty())
            }
        }

    /** Status and gross total as stored, read outside the test's own persistence context. */
    protected fun storedState(invoiceId: Long): Pair<Int, BigDecimal> = QuarkusTransaction.requiringNew().call<Pair<Int, BigDecimal>> {
        val r = em.createNativeQuery("SELECT status, gross_amount FROM invoice WHERE id = :id").setParameter("id", invoiceId).singleResult as Array<*>
        (r[0] as Number).toInt() to (r[1] as BigDecimal)
    }

    /** Status and type code of a fixture invoice; the default is a plain sent invoice. */
    class InvoiceKind(val status: InvoiceStatus = InvoiceStatus.SENT, val typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE)

    class DocumentRow(val statusOrdinal: Int, val reproduced: Boolean, val reproductionOf: String?, val sha: String?)

    class JournalRow(val actor: String, val contextJson: String)

    private companion object {
        val counter = AtomicInteger((System.currentTimeMillis() % 90_000).toInt())
    }
}
