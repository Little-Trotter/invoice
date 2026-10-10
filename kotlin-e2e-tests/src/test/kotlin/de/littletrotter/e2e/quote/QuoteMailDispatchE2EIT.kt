package de.littletrotter.e2e.quote

import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.mail.MailDispatchPort
import de.littletrotter.application.mail.port.inbound.MailComposeDraftQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendAttachment
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendUseCase
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteServiceException
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
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsDashboardQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.application.quote.port.inbound.QuoteStatus
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
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteMailModuleTestProfile
import de.littletrotter.e2e.S3TestResource
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
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Offer dispatch through the real offer module and the real mail module: the offer is created and
 * reviewed in invoicing, the first send reports that the dispatch copy is still being rendered,
 * the second hands a mail draft over, the draft is sent through the mail module into Mailpit, and
 * the module's `mail.send.result` makes the offer `SENT` with a `SEND` log line that points at the
 * mail journal; the dashboard figures "sent offers" and sent volume grow by that offer. The offer's PDF attachment travels as a presigned URL of the run's object store,
 * which the mail module fetches itself.
 *
 * Seam: the document worker is not part of the E2E JVM, so the test plays it. It stores a small PDF
 * where the offer module expects the dispatch copy and publishes the worker's completion message
 * exactly as the worker does; everything downstream of that is the modules' own code. The failure
 * path (offer stays in review, a log line is written) is covered by the offer module's own
 * integration test, not here. Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteMailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteMailDispatchE2EIT {

    @Inject
    lateinit var recipientRepository: RecipientRepository

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerLookup: QuoteCustomerLookupQueryPort

    @Inject
    lateinit var createQuote: CreateQuoteUseCase

    @Inject
    lateinit var editHeader: EditQuoteHeaderUseCase

    @Inject
    lateinit var editItems: EditQuoteItemsUseCase

    @Inject
    lateinit var submit: SubmitQuoteForReviewUseCase

    @Inject
    lateinit var sendQuote: SendQuoteUseCase

    @Inject
    lateinit var documents: QuoteDocumentQueryPort

    @Inject
    lateinit var detail: QuoteDetailQueryPort

    @Inject
    lateinit var log: QuoteLogQueryPort

    @Inject
    lateinit var statistics: QuoteStatisticsDashboardQueryPort

    @Inject
    lateinit var timeSource: TimeSource

    @Inject
    lateinit var dispatchPort: Instance<MailDispatchPort>

    @Inject
    lateinit var draftQuery: MailComposeDraftQueryPort

    @Inject
    lateinit var sendUseCase: MailSendUseCase

    private val createdRecipientIds = mutableListOf<Long>()
    private val createdSenderIds = mutableListOf<Long>()
    private var previousDefaultId: Long? = null

    @AfterEach
    fun cleanup() {
        if (createdSenderIds.isNotEmpty()) handDefaultBack(createdSenderIds.first())
        createdSenderIds.forEach { id -> QuarkusTransaction.requiringNew().run { senderProfileRepository.delete(id) } }
        createdRecipientIds.forEach { id -> QuarkusTransaction.requiringNew().run { recipientRepository.delete(id) } }
    }

    @Test
    fun `a reviewed offer is dispatched through the mail module and marked as sent by its result`() {
        val customerName = "Angebotskunde ${System.nanoTime()} GmbH"
        ensureBuckets()
        createSeller()
        createCustomer(customerName)
        val customerId = awaitReplica(customerName)
        val quoteId = createQuote.create(customerId)
        editHeader.editHeader(
            EditQuoteHeaderCommand(quoteId, LocalDate.now().plusDays(VALIDITY_DAYS), QuotePriceDisplay.AUTO, subject = "Beratung E2E"),
        )
        editItems.editItems(
            quoteId,
            listOf(
                QuoteItemInput("Beratung", BigDecimal("2.00"), InvoiceItemCategory.SERVICE, InvoiceItemUnit.HOUR, Money.of("100.00"), VatCategory.STANDARD, BigDecimal("19.00")),
            ),
        )
        val number = submitWhenRangeKnown(quoteId)
        val statsBefore = statistics.dashboard(QuoteStatisticsSelection(timeSource.today().year)).kpis

        val pending = assertInstanceOf(QuoteDispatchHandover.DocumentPending::class.java, sendWhenSellerKnown(quoteId))
        val pdf = "%PDF-1.4 synthetic offer ${UUID.randomUUID()}".toByteArray()
        playDocumentWorker(quoteId, pending.documentId, pdf)
        awaitDocumentCompleted(quoteId, pending.documentId)

        val composed = assertInstanceOf(QuoteDispatchHandover.Composed::class.java, sendQuote.send(quoteId))
        val subject = "Angebot $number"
        sendDraft(composed.draftId, subject, number)

        val message = awaitMailpitMessage(subject)
        assertTrue(message.contains("Angebot-$number.pdf"), "the mail carries the offer PDF as attachment: $message")
        assertEquals(QuoteStatus.SENT, awaitStatus(quoteId, QuoteStatus.SENT), "the mail result marks the offer as sent")
        val sendLines = log.log(quoteId, setOf(QuoteLogKind.SEND))
        assertTrue(sendLines.any { it.reference?.contains("mail-journal:") == true }, "SEND line references the mail journal: $sendLines")

        val statsAfter = statistics.dashboard(QuoteStatisticsSelection(timeSource.today().year)).kpis
        assertEquals(statsBefore.sentCount + 1, statsAfter.sentCount, "the dashboard counts the sent offer once")
        assertEquals(
            statsBefore.sentVolume + Money.of("200.00"),
            statsAfter.sentVolume,
            "the dashboard's sent volume grows by the offer's net (2 h x 100.00)",
        )
    }

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
    private fun createSeller() {
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
        previousDefaultId = QuarkusTransaction.requiringNew().call<Long?> { senderProfileRepository.findDefault()?.id }
        val saved = QuarkusTransaction.requiringNew().call { senderProfileRepository.save(sender) }
        createdSenderIds += saved.id!!
        makeDefault(saved.id!!)
    }

    /**
     * Other E2E classes in the same database claim the default sender too and the database allows exactly
     * one, so the flag is handed back after each test. Detached on purpose: a managed profile flagged as
     * default would be flushed before the old default is revoked.
     */
    private fun makeDefault(id: Long, default: Boolean = true) {
        val profile = checkNotNull(sellerProfileService.findByIdForEdit(id)) { "sender profile $id vanished" }
        profile.isDefault = default
        sellerProfileService.save(profile, defaults = SellerDefaultSelections())
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
            documentLanguage = "de"
            addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Teststrasse 1", "12345", "Musterstadt", Country.DE, true))
        }
        recipient.ensureShortHash()
        val saved = QuarkusTransaction.requiringNew().call { recipientRepository.save(recipient) }
        createdRecipientIds += saved.id!!
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

    /** The send is asynchronous; returns the message detail (including attachment names) once Mailpit holds it. */
    private fun awaitMailpitMessage(subject: String): String {
        val api = config(EmailAppTestResource.MAILPIT_API_URL_PROPERTY)
        val client = HttpClient.newHttpClient()
        val search = "$api/api/v1/search?query=subject:%22${subject.replace(" ", "%20")}%22"
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            val body = get(client, search)
            val id = MESSAGE_ID.find(body)?.groupValues?.get(1)
            if (id != null) return get(client, "$api/api/v1/message/$id")
            Thread.sleep(POLL_MILLIS)
        }
        error("mail '$subject' did not reach Mailpit within $WAIT_SECONDS s")
    }

    private fun get(client: HttpClient, url: String): String =
        client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body()

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

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DOCUMENTS_BUCKET = "documents"
        const val ATTACHMENTS_BUCKET = "quote-documents"
        const val SUFFIX_LENGTH = 8
        const val VALIDITY_DAYS = 30L
        const val CUSTOMER_NUMBER_MODULUS = 100_000
        const val REPLICATION_TIMEOUT_SECONDS = 60L
        const val WAIT_SECONDS = 60L
        const val STATUS_TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 500L
        val MESSAGE_ID = Regex("\"ID\"\\s*:\\s*\"([^\"]+)\"")
    }
}
