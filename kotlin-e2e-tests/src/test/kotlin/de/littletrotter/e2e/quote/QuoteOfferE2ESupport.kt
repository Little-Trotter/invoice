package de.littletrotter.e2e.quote

import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.mail.port.inbound.MailComposeDraftQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendAttachment
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendUseCase
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AcceptQuoteUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.persistence.EntityManager
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import de.littletrotter.application.quote.port.inbound.CreateQuoteUseCase
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderUseCase
import de.littletrotter.application.quote.port.inbound.EditQuoteItemsUseCase
import de.littletrotter.application.quote.port.inbound.QuoteCustomerLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.RequestQuoteInvoiceUseCase
import de.littletrotter.application.quote.port.inbound.SendQuoteUseCase
import de.littletrotter.application.quote.port.inbound.SubmitQuoteForReviewUseCase
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.documentjob.DocumentJobResult
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.QuoteDocumentJob
import de.littletrotter.shared.tax.VatCategory
import io.nats.client.Nats
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * What the offer fault tests share with the invoice order test: an offer that went through creation,
 * review and dispatch with the real offer and mail modules until it is `SENT`, and the reads on the
 * invoice the invoicing side drafts from it. The document worker is played by the test; synthetic
 * data only. Subclasses carry the Quarkus resources, so each scenario declares what it boots.
 */
abstract class QuoteOfferE2ESupport {

    private var previousDefaultId: Long? = null
    private var claimed = false

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var customerLookup: QuoteCustomerLookupQueryPort

    @Inject
    lateinit var createQuote: CreateQuoteUseCase

    @Inject
    lateinit var editHeader: EditQuoteHeaderUseCase

    @Inject
    lateinit var editItems: EditQuoteItemsUseCase

    @Inject
    lateinit var attachFile: AttachQuoteFileUseCase

    @Inject
    lateinit var submit: SubmitQuoteForReviewUseCase

    @Inject
    lateinit var sendQuote: SendQuoteUseCase

    @Inject
    lateinit var acceptQuote: AcceptQuoteUseCase

    @Inject
    lateinit var requestInvoice: RequestQuoteInvoiceUseCase

    @Inject
    lateinit var documents: QuoteDocumentQueryPort

    @Inject
    lateinit var documentFiles: QuoteDocumentFileQueryPort

    @Inject
    lateinit var detail: QuoteDetailQueryPort

    @Inject
    lateinit var log: QuoteLogQueryPort

    @Inject
    lateinit var draftQuery: MailComposeDraftQueryPort

    @Inject
    lateinit var sendUseCase: MailSendUseCase

    /** Creates, reviews and dispatches an offer for a fresh customer until the mail result marks it `SENT`. */
    protected fun sentOffer(description: String, quantity: String, unitPrice: String): SentOffer {
        val customerName = "Angebotskunde ${System.nanoTime()} GmbH"
        ensureBuckets()
        claimDefaultSender()
        createCustomer(customerName)
        val customerId = awaitReplica(customerName)
        val quoteId = createQuote.create(customerId)
        editHeader.editHeader(
            EditQuoteHeaderCommand(quoteId, LocalDate.now().plusDays(VALIDITY_DAYS), QuotePriceDisplay.AUTO, subject = "$description E2E"),
        )
        editItems.editItems(
            quoteId,
            listOf(
                QuoteItemInput(description, BigDecimal(quantity), InvoiceItemCategory.SERVICE, InvoiceItemUnit.HOUR, Money.of(unitPrice), VatCategory.STANDARD, BigDecimal("19.00")),
            ),
        )
        val attached = pdf("customer attachment ${UUID.randomUUID()}")
        attachFile.attach(quoteId, QuoteAttachmentUpload(QuoteAttachmentKind.CUSTOMER, "Leistungsbeschreibung.pdf", "application/pdf", attached))
        val number = submitWhenRangeKnown(quoteId)

        val pending = assertInstanceOf(QuoteDispatchHandover.DocumentPending::class.java, sendWhenSellerKnown(quoteId))
        val dispatchPdf = pdf("synthetic offer ${UUID.randomUUID()}")
        playDocumentWorker(quoteId, pending.documentId, dispatchPdf)
        awaitDocumentCompleted(quoteId, pending.documentId)
        val composed = assertInstanceOf(QuoteDispatchHandover.Composed::class.java, sendQuote.send(quoteId))
        sendDraft(composed.draftId, "Angebot $number", number)
        assertEquals(QuoteStatus.SENT, awaitStatus(quoteId, QuoteStatus.SENT), "the mail result marks the offer as sent")
        // The invoice takes over the stored dispatch copy; reading it back proves the worker's bytes are what the offer holds.
        val stored = documentFiles.pdf(quoteId, pending.documentId).bytes
        assertTrue(stored.contentEquals(dispatchPdf), "the offer serves the dispatch copy the worker stored")
        return SentOffer(quoteId, number, description, dispatchPdf, attached)
    }

    /** A valid PDF, because the receipt pool parses what it takes over. */
    protected fun pdf(label: String): ByteArray = ByteArrayOutputStream().use { out ->
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.documentInformation.title = label
            document.save(out)
        }
        out.toByteArray()
    }

    protected fun findInvoice(quoteId: String): InvoiceSnapshot? = QuarkusTransaction.requiringNew().call {
        invoiceRepository.findBySourceQuoteId(UUID.fromString(quoteId))?.let { invoice ->
            InvoiceSnapshot(
                id = invoice.id!!,
                status = invoice.status,
                number = invoice.invoiceNumber.value,
                orderReference = invoice.references.orderReference,
                offerReference = invoice.references.offerReference,
                purchaseOrderReference = invoice.references.purchaseOrderReference,
                itemDescriptions = invoice.itemList.map { it.description },
                quantities = invoice.itemList.map { it.quantity },
                netPrices = invoice.itemList.map { it.unitPriceNet.amount },
                receiptChecksums = invoice.receipts.map { it.sha256Checksum },
                deliveryDate = invoice.deliveryDate,
                servicePeriodFrom = invoice.servicePeriodFrom,
                servicePeriodTo = invoice.servicePeriodTo,
            )
        }
    }

    protected fun invoiceCountForOffer(quoteId: String): Long = QuarkusTransaction.requiringNew().call {
        (em.createNativeQuery("select count(*) from invoice where source_quote_id = cast(:id as uuid)").setParameter("id", quoteId).singleResult as Number).toLong()
    }

    /** A business action writes one journal row per touched table row; they share one business transaction. */
    protected fun journalTransactions(invoiceId: Long, action: Int): Long = QuarkusTransaction.requiringNew().call {
        (
            em.createNativeQuery("select count(distinct business_tx) from invoice_audit_journal where invoice_id = :id and action = :action")
                .setParameter("id", invoiceId)
                .setParameter("action", action)
                .singleResult as Number
            ).toLong()
    }

    protected fun messageCount(pattern: String): Long = QuarkusTransaction.requiringNew().call {
        (em.createNativeQuery("select count(*) from system_message where resolved_text like :p").setParameter("p", pattern).singleResult as Number).toLong()
    }

    protected fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("timed out after $WAIT_SECONDS s: $what")
    }

    protected class SentOffer(
        val quoteId: String,
        val number: String,
        val description: String,
        val dispatchPdf: ByteArray,
        val attachedPdf: ByteArray,
    )

    protected class InvoiceSnapshot(
        val id: Long,
        val status: InvoiceStatus,
        val number: String,
        val orderReference: String?,
        val offerReference: String?,
        val purchaseOrderReference: String?,
        val itemDescriptions: List<String>,
        val quantities: List<BigDecimal>,
        val netPrices: List<BigDecimal>,
        val receiptChecksums: List<String?>,
        val deliveryDate: LocalDate?,
        val servicePeriodFrom: LocalDate?,
        val servicePeriodTo: LocalDate?,
    )

    /** The offer module only writes into buckets that exist; in a deployment the object store is provisioned with them. */
    private fun ensureBuckets() {
        s3Client().use { s3 ->
            listOf(DOCUMENTS_BUCKET, ATTACHMENTS_BUCKET).forEach { bucket ->
                try {
                    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                } catch (_: BucketAlreadyOwnedByYouException) {
                    // already provisioned
                }
            }
        }
    }

    /** The offer letterhead comes from the replicated seller, so the offer module needs one before it renders anything. */
    private fun createSeller(): Long {
        val sender = SenderProfile().apply {
            name = "Angebot E2E Muster GmbH"
            email = Email("billing@angebot-e2e.example.test")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "AE${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}-"
            addAddress(Address.forSender(this, Address.AddressType.BILLING, "Musterweg 2", "12345", "Musterstadt", Country.DE, true))
        }
        sender.ensureShortHash()
        return QuarkusTransaction.requiringNew().call { senderProfileRepository.save(sender) }.id!!
    }

    /** Created invoices reference the seller, so it is kept for the run; only the default flag is claimed and returned. */
    private fun claimDefaultSender() {
        if (claimed) return
        claimed = true
        previousDefaultId = QuarkusTransaction.requiringNew().call<Long?> { senderProfileRepository.findDefault()?.id }
        val id = senderId ?: createSeller().also { senderId = it }
        makeDefault(id)
    }

    /** Takes the default flag from the test seller, so the invoicing side has no sender to draft with and refuses the order. */
    protected fun withoutDefaultSender() {
        makeDefault(checkNotNull(senderId) { "no test seller yet" }, default = false)
    }

    /** Gives the default flag back to the test seller after [withoutDefaultSender]. */
    protected fun withDefaultSender() {
        makeDefault(checkNotNull(senderId) { "no test seller yet" })
    }

    @AfterEach
    fun returnDefaultSender() {
        if (!claimed) return
        claimed = false
        handDefaultBack(senderId)
    }

    /**
     * Other E2E classes in the same database claim the default sender too and the database allows exactly
     * one, so the flag is handed back after each test. Detached on purpose: a managed profile flagged as
     * default would be flushed before the old default is revoked.
     */
    private fun makeDefault(id: Long, default: Boolean = true) {
        if (!default) return clearDefault(id)
        val profile = checkNotNull(sellerProfileService.findByIdForEdit(id)) { "sender profile $id vanished" }
        profile.isDefault = default
        sellerProfileService.save(profile, defaults = SellerDefaultSelections())
    }

    /** The profile service re-promotes a profile whenever no default would remain, so the flag is cleared at the repository. */
    private fun clearDefault(id: Long) {
        QuarkusTransaction.requiringNew().run {
            val profile = checkNotNull(senderProfileRepository.findById(id)) { "sender profile $id vanished" }
            profile.isDefault = false
            senderProfileRepository.save(profile)
        }
    }

    private fun handDefaultBack(ownId: Long?) {
        val previousId = previousDefaultId
        if (previousId != null) makeDefault(previousId) else if (ownId != null) makeDefault(ownId, default = false)
    }

    /** Replication is asynchronous: until the seeded number range arrived, submitting is refused. */
    private fun submitWhenRangeKnown(quoteId: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(REPLICATION_TIMEOUT_SECONDS).toNanos()
        while (true) {
            try {
                return submit.submit(quoteId)
            } catch (e: QuoteNoNumberRangeException) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    /** Replication is asynchronous: until the seller arrived, the offer module refuses to prepare a copy. */
    private fun sendWhenSellerKnown(quoteId: String): QuoteDispatchHandover {
        val deadline = System.nanoTime() + Duration.ofSeconds(REPLICATION_TIMEOUT_SECONDS).toNanos()
        while (true) {
            try {
                return sendQuote.send(quoteId)
            } catch (e: QuoteServiceException) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    private fun createCustomer(name: String) {
        val recipient = Recipient().apply {
            this.name = name
            customerNumber = CustomerNumber.ofNullable("KD-Q-${System.nanoTime() % CUSTOMER_NUMBER_MODULUS}")
            email = Email.ofNullable("angebot-e2e@example.test")
            vatId = VatId("DE222222222")
            documentLanguage = "de"
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Teststrasse 1", "12345", "Musterstadt", Country.DE, true))
        }
        recipient.ensureShortHash()
        QuarkusTransaction.requiringNew().call { recipientRepository.save(recipient) }
    }

    /** Replication is asynchronous: the customer shows up in the offer module's lookup shortly after it was created. */
    private fun awaitReplica(name: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(REPLICATION_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            customerLookup.customers(name, 5).firstOrNull { it.name == name }?.let { return it.id }
            Thread.sleep(POLL_MILLIS)
        }
        error("customer '$name' was not replicated to the offer module within $REPLICATION_TIMEOUT_SECONDS s")
    }

    /** Stores the dispatch copy on the run's object store and publishes the worker's completion for it. */
    private fun playDocumentWorker(quoteId: String, documentId: String, pdf: ByteArray) {
        val objectKey = "quotes/$quoteId/$documentId/quote.pdf"
        s3Client().use { s3 ->
            s3.putObject(
                PutObjectRequest.builder().bucket(DOCUMENTS_BUCKET).key(objectKey).contentType("application/pdf").build(),
                RequestBody.fromBytes(pdf),
            )
        }
        val result = DocumentJobResult(
            id = documentId,
            status = "COMPLETED",
            resultRefs = mapOf(QuoteDocumentJob.REF_OBJECT_KEY to objectKey, QuoteDocumentJob.REF_SHA256 to sha256Hex(pdf)),
            kositReportRef = null,
            correlationId = null,
        )
        Nats.connect(config("nats.url")).use { connection ->
            connection.jetStream().publish(QuoteDocumentJob.RESULT_SUBJECT, DocumentJobWireCodec.encodeResult(result).toByteArray(Charsets.UTF_8))
        }
    }

    private fun s3Client(): S3Client = S3Client.builder()
        .endpointOverride(URI.create(config("s3.endpoint")))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config("s3.access-key"), config("s3.secret-key"))))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .build()

    private fun awaitDocumentCompleted(quoteId: String, documentId: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            if (documents.documents(quoteId).any { it.id == documentId && it.status == QuoteDocumentStatus.COMPLETED }) return
            Thread.sleep(POLL_MILLIS)
        }
        error("document $documentId did not complete within $WAIT_SECONDS s: ${documents.documents(quoteId)}")
    }

    /** Reads the staged draft back and confirms it, as the compose view does. */
    private fun sendDraft(draftId: String, subject: String, number: String) {
        val draft = requireNotNull(draftQuery.findDraft(draftId)) { "draft $draftId is unknown to the mail module" }
        sendUseCase.send(
            MailSendCommand(
                kind = MailTemplateKind.QUOTE_DISPATCH,
                documentFamily = InvoiceFormat.GERMAN,
                recipientAddress = draft.recipientAddress,
                referenceKey = draft.referenceKey,
                subject = subject,
                body = "Anbei unser Angebot $number.",
                placeholderValues = draft.placeholderValues,
                bodyEditedByUser = true,
                attachments = draft.attachments.map { MailSendAttachment(it.s3Key, it.filename, it.contentType, it.sha256) },
                draftId = draft.draftId,
            ),
        )
    }

    /** The result travels module -> central NATS -> offer module, so the status changes shortly after the delivery. */
    private fun awaitStatus(quoteId: String, expected: QuoteStatus): QuoteStatus {
        val deadline = System.nanoTime() + Duration.ofSeconds(STATUS_TIMEOUT_SECONDS).toNanos()
        var status = detail.find(quoteId).status
        while (status != expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            status = detail.find(quoteId).status
        }
        return status
    }

    protected fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    protected fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    protected companion object {
        const val DOCUMENTS_BUCKET = "documents"
        const val ATTACHMENTS_BUCKET = "quote-documents"
        const val SUFFIX_LENGTH = 8
        const val VALIDITY_DAYS = 30L
        const val CUSTOMER_NUMBER_MODULUS = 100_000
        const val REPLICATION_TIMEOUT_SECONDS = 60L
        const val WAIT_SECONDS = 60L
        const val STATUS_TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 500L
        const val SETTLE_MILLIS = 3_000L
        const val CREATED_FROM_QUOTE_ACTION = 22

        /** One seller per run is enough; only one profile may be the default at a time. */
        @Volatile
        var senderId: Long? = null
    }
}
