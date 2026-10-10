package de.littletrotter.e2e.mail

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.ResolveInvoiceDocumentUseCase
import de.littletrotter.application.mail.InvoiceMailAttachment
import de.littletrotter.application.mail.InvoiceMailComposeMapper
import de.littletrotter.application.mail.MailDispatchPort
import de.littletrotter.application.mail.port.inbound.MailComposeDraftQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendAttachment
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendUseCase
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.MailModuleTestProfile
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Document recovery against the real mail module: an invoice is handed to `email-app` for
 * dispatch (compose, then send), its PDF object is deleted from the document store, and the next
 * access restores the exact attachment bytes from the module's mail journal.
 *
 * Nothing about the module is stubbed — the compose handover, the attachment fetch with checksum
 * verification, the SMTP delivery into Mailpit and the journal lookup are the module's own code.
 * The only substitution is where the module downloads the attachment from, see
 * [publishAttachment]. The module publishes its `mail.send.result` on the same
 * NATS server the invoicing application listens on, so the test also waits for the invoice to be
 * marked as mailed by that projection. Synthetic data only.
 */
@QuarkusTest
@TestProfile(MailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class InvoiceDocumentRecoveryMailE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var resolver: ResolveInvoiceDocumentUseCase

    @Inject
    lateinit var dispatchPort: Instance<MailDispatchPort>

    @Inject
    lateinit var draftQuery: MailComposeDraftQueryPort

    @Inject
    lateinit var sendUseCase: MailSendUseCase

    @Test
    fun `a sent invoice whose PDF object is gone is restored byte-identical from the mail journal`() {
        val number = "MAILE2E-2026-10-%05d".format(System.nanoTime() % 100_000)
        val pdfBytes = "%PDF-1.4 synthetic mail recovery ${UUID.randomUUID()}".toByteArray()
        val pdfSha = sha256Hex(pdfBytes)
        val invoiceId = persistIssuedInvoice(number)
        val pdfKey = completeIssuedJob(invoiceId, number, pdfBytes, pdfSha)

        val sentSubject = sendThroughMailModule(invoiceId, number, pdfBytes, pdfSha)
        awaitMailpitMessage(sentSubject)
        assertNotNull(awaitMailedAt(invoiceId), "the mail.send.result projection marks the invoice as mailed")

        documentStore.delete(pdfKey)
        assertFalse(documentStore.exists(pdfKey), "precondition: the PDF object is gone")

        val result = resolver.resolveForInvoice(invoiceId)

        assertEquals(DocumentResolution.Restored(pdfKey, RestoreSource.MAIL_JOURNAL), result)
        assertArrayEquals(pdfBytes, documentStore.get(pdfKey), "restored bytes equal the sent attachment")
        val context = restoredJournalContext(invoiceId)
        assertTrue(context.contains("MAIL_JOURNAL"), context)
    }

    /** Compose and send exactly as the compose view does: handover, read the staged draft back, confirm. */
    private fun sendThroughMailModule(invoiceId: Long, number: String, pdfBytes: ByteArray, pdfSha: String): String {
        val fileName = "Rechnung-$number.pdf"
        val handover = QuarkusTransaction.requiringNew().call<String> {
            val invoice = requireNotNull(em.find(Invoice::class.java, invoiceId))
            val attachment = InvoiceMailAttachment(
                filename = fileName,
                contentType = "application/pdf",
                sizeBytes = pdfBytes.size.toLong(),
                sha256 = pdfSha,
                downloadUrl = publishAttachment("$number/$fileName", pdfBytes),
            )
            dispatchPort.get().compose(InvoiceMailComposeMapper.toDraft(invoice, listOf(attachment))).draftId
        }
        val draft = requireNotNull(draftQuery.findDraft(handover)) { "draft $handover is unknown to the mail module" }
        val subject = "Rechnung $number"
        sendUseCase.send(
            MailSendCommand(
                kind = MailTemplateKind.INVOICE_DISPATCH,
                documentFamily = InvoiceFormat.GERMAN,
                recipientAddress = draft.recipientAddress,
                referenceKey = draft.referenceKey,
                subject = subject,
                body = "Anbei die Rechnung $number.",
                placeholderValues = draft.placeholderValues,
                bodyEditedByUser = true,
                attachments = draft.attachments.map { MailSendAttachment(it.s3Key, it.filename, it.contentType, it.sha256) },
                draftId = draft.draftId,
            ),
        )
        return subject
    }

    /** Writes the file where the resource's host-side server serves it and returns the URL the mail module fetches. */
    private fun publishAttachment(relativePath: String, bytes: ByteArray): String {
        val target = Path.of(config(EmailAppTestResource.ATTACHMENT_DIR_PROPERTY)).resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        return "${config(EmailAppTestResource.ATTACHMENT_BASE_URL_PROPERTY)}/$relativePath"
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    /** The send is asynchronous: the message must be in the SMTP sink before the journal is asked for a copy. */
    private fun awaitMailpitMessage(subject: String) {
        val search = "${config(EmailAppTestResource.MAILPIT_API_URL_PROPERTY)}/api/v1/search?query=subject:%22${subject.replace(" ", "%20")}%22"
        val client = HttpClient.newHttpClient()
        val deadline = System.nanoTime() + Duration.ofSeconds(MAIL_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            val body = client.send(HttpRequest.newBuilder(URI.create(search)).build(), HttpResponse.BodyHandlers.ofString()).body()
            if (!body.contains("\"messages_count\":0")) return
            Thread.sleep(POLL_MILLIS)
        }
        error("mail '$subject' did not reach Mailpit within $MAIL_TIMEOUT_SECONDS s")
    }

    /** The result event travels module -> central NATS -> projection, so the flag appears shortly after the SMTP delivery. */
    private fun awaitMailedAt(invoiceId: Long): Any? {
        val deadline = System.nanoTime() + Duration.ofSeconds(MAIL_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            val mailedAt = QuarkusTransaction.requiringNew().call<Any?> {
                em.createNativeQuery("SELECT mail_sent_at FROM invoice WHERE id = :id")
                    .setParameter("id", invoiceId).resultList.single()
            }
            if (mailedAt != null) return mailedAt
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun restoredJournalContext(invoiceId: Long): String = QuarkusTransaction.requiringNew().call<String> {
        em.createNativeQuery(
            "SELECT detail->'_context' FROM invoice_audit_journal WHERE invoice_id = :id AND action = :a ORDER BY seq",
        ).setParameter("id", invoiceId).setParameter("a", InvoiceAuditAction.DOCUMENT_RESTORED.ordinal)
            .resultList.single().toString()
    }

    private fun persistIssuedInvoice(number: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        val sender = SenderProfile().apply {
            name = "Mail Recovery Muster GmbH"
            email = Email("billing@mailrecovery.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "MR${UUID.randomUUID().toString().take(8)}-"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-$number")
            name = "Mail Recovery Kunde AG"
            email = Email("kunde@mailrecovery.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "E2E_MAILREC_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice.withInitialStatus(InvoiceStatus.SENT).apply {
            invoiceNumber = InvoiceNumber(number)
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
        }
        invoice.addItem(
            InvoiceItem().apply {
                positionId = "1"
                description = "Beratung"
                quantity = BigDecimal("1.00")
                unitPriceNet = Money(BigDecimal("100.00"))
                netAmount = Money(BigDecimal("100.00"))
                vatAmount = Money(BigDecimal("19.00"))
                grossAmount = Money(BigDecimal("119.00"))
                taxRateRef = taxRate
            },
        )
        em.persist(invoice)
        em.flush()
        requireNotNull(invoice.id)
    }

    /** The issued job's completion persists the document row with its checksum, as the worker callback does. */
    private fun completeIssuedJob(invoiceId: Long, number: String, pdfBytes: ByteArray, pdfSha: String): String {
        val invoice = QuarkusTransaction.requiringNew().call<Invoice> { requireNotNull(em.find(Invoice::class.java, invoiceId)) }
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)
        val key = DocumentKeyRules.deriveJobExportKeys(number, invoiceId, requireNotNull(job.id).toString(), draft = false).pdfObjectKey
        documentStore.put(key, pdfBytes, "application/pdf")
        generationJobResultHandler.markCompleted(job, """{"objectKey":"$key","sha256":"$pdfSha"}""")
        return key
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAIL_TIMEOUT_SECONDS = 60L
        const val POLL_MILLIS = 500L
    }
}
