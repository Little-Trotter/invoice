package de.littletrotter.ui.documents

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.RustFsTestResource
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.application.invoice.Sha256Hex
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceDocumentRepository
import de.littletrotter.domain.repository.SystemMessageRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Real HTTP-level acceptance of [DocumentDownloadResource]: a genuine RestAssured request
 * against a genuine RustFS-backed [DocumentStore] and Postgres, proving the actual contract a
 * browser tab relies on (status, `Content-Disposition`, bytes) rather than exercising the resource
 * methods as plain Kotlin functions. `auth.enabled`/`quarkus.oidc.tenant-enabled` stay at their
 * %test defaults here (every request permitted, `SecurityIdentityAppRoleProvider`'s anonymous-is-
 * every-role bypass applies) — the one behaviour that needs the opposite (401 anonymous) is its
 * own class below, [DocumentDownloadAuthEnforcedIT], with its own [TestProfile].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(RustFsTestResource::class)
class DocumentDownloadResourceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var receiptUploadService: ReceiptUploadService

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var invoiceDocumentRepository: InvoiceDocumentRepository

    @Inject
    lateinit var systemMessageRepository: SystemMessageRepository

    /** Same fixture shape as [de.littletrotter.application.invoice.GenerationJobServiceIT]'s own
     *  `persistInvoice` — a minimal, real, persisted [Invoice] a [de.littletrotter.domain.invoice
     *  .GenerationJob] can hang off. Runs in its own, immediately committed transaction so the row
     *  is visible to the separately transacted service calls that follow. */
    private fun persistInvoice(number: String): Invoice = QuarkusTransaction.requiringNew().call<Invoice> {
        val sender = SenderProfile().apply {
            name = "ST-A5 Muster GmbH"
            email = Email("billing@st-a5.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            // Coordinator finding (2026-09-18 full verify) — a counter-derived prefix ("IP0001-"
            // etc.) collided with `uq_sender_profile_invoice_prefix` under a full `make test` run:
            // this dev server reuses the Postgres Testcontainer across separate `mvn` invocations,
            // so leftover rows from an earlier scoped run of this exact class (same small counter,
            // same prefix) were still there. Random, not counter-derived, so a rerun never repeats
            // a prior run's value regardless of container reuse.
            invoicePrefix = "IP${UUID.randomUUID().toString().take(8)}-"
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-$number")
            name = "ST-A5 Kunde AG"
            email = Email("kunde@st-a5.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "IT_ST_A5_$number"
            category = "S"
            isSelectable = false
        }
        em.persist(sender)
        em.persist(recipient)
        em.persist(taxRate)
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            forceStatusForTest(InvoiceStatus.SENT)
            invoiceDate = LocalDate.now()
            senderProfile = sender
            this.recipient = recipient
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
        }
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
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

    /** [InvoiceNumber]'s own format guard only accepts `PREFIX-YYYY-NNNN` (legacy)/`PREFIX-YYYY-MM
     *  -NNNNN` (new)/`PREFIX-YYYY-MM-DD-NNNNN…` (daily) — a plain per-test label is rejected. This
     *  fixture always uses the legacy shape, `counter` zero-padded to the 4-digit legacy width. */
    private fun invoiceNumberFor(counter: Int): String = "STA5-2026-%04d".format(counter)

    /** A COMPLETED job carrying [pdfKey]/[xmlKey]/[archiveKey] in its `resultRefs`, with matching
     *  bytes already put into the real [documentStore] under those exact keys. */
    private fun completedJobWithArtifacts(
        counter: Int,
        pdfBytes: ByteArray = "%PDF-1.4 pdf bytes".toByteArray(),
        xmlBytes: ByteArray = "<xml>zugferd</xml>".toByteArray(),
        archiveBytes: ByteArray = "tar.gz bytes".toByteArray(),
    ): Long {
        val invoice = persistInvoice(invoiceNumberFor(counter))
        val pdfKey = "st-a5-it/$counter.pdf"
        val xmlKey = "st-a5-it/$counter.xml"
        val archiveKey = "st-a5-it/$counter.tar.gz"
        documentStore.put(pdfKey, pdfBytes, "application/pdf")
        documentStore.put(xmlKey, xmlBytes, "application/xml")
        documentStore.put(archiveKey, archiveBytes, "application/gzip")
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)
        val resultRefs = """{"objectKey":"$pdfKey","zugferdObjectKey":"$xmlKey","archiveObjectKey":"$archiveKey"}"""
        val completed = generationJobResultHandler.markCompleted(job, resultRefs)
        return completed.id!!
    }

    @Test
    fun `the pdf route answers 200 with inline disposition and the exact stored bytes`() {
        val pdfBytes = "%PDF-1.4 unique-pdf-${UUID.randomUUID()}".toByteArray()
        val jobId = completedJobWithArtifacts(1, pdfBytes = pdfBytes)

        val response = given()
            .`when`().get("/documents/jobs/{jobId}/pdf", jobId)
            .then().statusCode(200)
            .header("Content-Disposition", containsString("inline"))
            .contentType("application/pdf")
            .extract()

        assertArrayEquals(pdfBytes, response.asByteArray())
    }

    @Test
    fun `the xml route answers 200 with attachment disposition and the exact stored bytes`() {
        val xmlBytes = "<xml>unique-${UUID.randomUUID()}</xml>".toByteArray()
        val jobId = completedJobWithArtifacts(2, xmlBytes = xmlBytes)

        val response = given()
            .`when`().get("/documents/jobs/{jobId}/xml", jobId)
            .then().statusCode(200)
            .header("Content-Disposition", containsString("attachment"))
            .contentType("application/xml")
            .extract()

        assertArrayEquals(xmlBytes, response.asByteArray())
    }

    @Test
    fun `the archive route answers 200 with attachment disposition and the exact stored bytes`() {
        val archiveBytes = "archive-${UUID.randomUUID()}".toByteArray()
        val jobId = completedJobWithArtifacts(3, archiveBytes = archiveBytes)

        val response = given()
            .`when`().get("/documents/jobs/{jobId}/archive", jobId)
            .then().statusCode(200)
            .header("Content-Disposition", containsString("attachment"))
            .extract()

        assertArrayEquals(archiveBytes, response.asByteArray())
    }

    @Test
    fun `the pdf route answers 404 for a job id that does not exist`() {
        given()
            .`when`().get("/documents/jobs/{jobId}/pdf", 999_999_999L)
            .then().statusCode(404)
            .contentType(containsString("text/plain"))
    }

    @Test
    fun `the pdf route answers 404 when the job exists but never produced a PDF`() {
        val invoice = persistInvoice(invoiceNumberFor(4))
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)

        given()
            .`when`().get("/documents/jobs/{jobId}/pdf", job.id)
            .then().statusCode(404)
    }

    @Test
    fun `the pdf route answers 202 while the current original is missing and its regeneration runs`() {
        val invoice = persistInvoice(invoiceNumberFor(6))
        val pdfKey = "st-a5-it/recover-${UUID.randomUUID()}.pdf"
        val pdfBytes = "recover bytes ${UUID.randomUUID()}".toByteArray()
        documentStore.put(pdfKey, pdfBytes, "application/pdf")
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)
        val jobId = generationJobResultHandler.markCompleted(job, """{"objectKey":"$pdfKey","sha256":"${Sha256Hex.of(pdfBytes)}"}""").id
        documentStore.delete(pdfKey)

        given()
            .`when`().get("/documents/jobs/{jobId}/pdf", jobId)
            .then().statusCode(202)
            .contentType(containsString("text/plain"))
            .body(containsString("Auftrag"))
    }

    @Test
    fun `the pdf route answers 409 when the stored checksum no longer matches the object's bytes`() {
        val pdfKey = "st-a5-it/checksum-mismatch-${UUID.randomUUID()}.pdf"
        documentStore.put(pdfKey, "actual bytes".toByteArray(), "application/pdf")
        val invoiceNumber = invoiceNumberFor(5)
        val invoice = persistInvoice(invoiceNumber)
        QuarkusTransaction.requiringNew().call {
            invoiceDocumentRepository.save(
                InvoiceDocument().apply {
                    shortName = "st-a5-checksum-mismatch"
                    documentType = "PDF"
                    fileName = "checksum-mismatch.pdf"
                    mimeType = "application/pdf"
                    objectKey = pdfKey
                    sha256Checksum = "0".repeat(64)
                    this.invoice = em.getReference(Invoice::class.java, invoice.id)
                },
            )
        }
        val job = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        generationJobPublishingService.markDelegated(job)
        val jobId = generationJobResultHandler.markCompleted(job, """{"objectKey":"$pdfKey"}""").id
        // A newer completed job makes the tampered one history: only the invoice's current document
        // is recovered on access, an older job's artifacts keep their checksum protection.
        val newerKey = "st-a5-it/newer-${UUID.randomUUID()}.pdf"
        val newerBytes = "newer bytes".toByteArray()
        documentStore.put(newerKey, newerBytes, "application/pdf")
        val newerJob = generationJobCreationService.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, trigger = GenerationJobTrigger.REGENERATION)
        generationJobResultHandler.markCompleted(newerJob, """{"objectKey":"$newerKey","sha256":"${Sha256Hex.of(newerBytes)}"}""")

        given()
            .`when`().get("/documents/jobs/{jobId}/pdf", jobId)
            .then().statusCode(409)

        // PO review (2026-09-18) — the mismatch must be traceable to the actual invoice, not just
        // a bare 409: DocumentChecksumVerifier.verifiedGet's message-centre ERROR entry must carry
        // the invoice number, which requires DocumentDownloadResource to thread job.invoice's
        // id/number through into verifiedGet (spec.invoiceId/spec.invoiceNumber) rather than the
        // `null, null` it started out with.
        val recorded = systemMessageRepository.find(userId = "st-a5-test-reader", filter = SystemMessageFilter(source = setOf(SystemMessageSource.EXPORT)))
        assertTrue(
            recorded.any { it.resolvedText.contains(invoiceNumber) },
            "expected a message-centre entry naming invoice $invoiceNumber, got: ${recorded.map { it.resolvedText }}",
        )
    }

    @Test
    @TestSecurity(user = "master-data-only", roles = ["admin"])
    fun `the pdf route answers 403 for an authenticated caller holding neither required permission`() {
        // "admin" grants REFERENCE_DATA_EDIT/SYSTEM_VIEW/DATABASE_MAINTENANCE only (AppRole) — none
        // of INVOICE_JOBS/INVOICE_FULL_EDIT, so the permission gate itself (not the anonymous/401
        // gate DocumentDownloadAuthEnforcedIT covers) must reject this. No fixture needed: the
        // permission check runs before the job id is ever resolved.
        given()
            .`when`().get("/documents/jobs/{jobId}/pdf", 1L)
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "master-data-only", roles = ["admin"])
    fun `the receipts route answers 403 for an authenticated caller holding neither required permission`() {
        given()
            .`when`().get("/documents/receipts/{receiptId}", 1L)
            .then().statusCode(403)
    }

    @Test
    fun `the receipts route answers 200 with the exact stored bytes`() {
        val pdfBytes = validPdfBytes()
        val receipt = receiptUploadService.upload("beleg.pdf", "ST-A5 Beleg", pdfBytes, LocalDate.now())

        val response = given()
            .`when`().get("/documents/receipts/{receiptId}", receipt.id)
            .then().statusCode(200)
            .contentType("application/pdf")
            .extract()

        assertArrayEquals(pdfBytes, response.asByteArray())
    }

    @Test
    fun `the receipts route answers 404 for a receipt id that does not exist`() {
        given()
            .`when`().get("/documents/receipts/{receiptId}", 999_999_999L)
            .then().statusCode(404)
    }

    /** Minimal valid PDF header bytes — same shape [ReceiptUploadService.upload]'s own `isPdf` magic-
     *  number check requires (`%PDF`), see that method's own KDoc. */
    private fun validPdfBytes(): ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34)
}

/**
 * The `401 anonymous` acceptance case needs the opposite of every other test in this file:
 * the global `authenticated` HTTP policy actually enforced, not %test's blanket `permit` (see
 * `application.properties`' own `%test.quarkus.http.auth.permission.authenticated.policy=permit`
 * line). Same `auth.enabled=true`/`quarkus.oidc.tenant-enabled=false` split
 * [de.littletrotter.infrastructure.auth.InternalAuthVerifyEnforcedIT] already established (a real
 * OIDC tenant would attempt discovery against an Authentik no test stack runs) — here it also
 * needs the `authenticated` policy's own %test override reverted, which that class' target
 * endpoint never needed (its own `/internal/auth/verify` permit rule is unconditional). A separate
 * top-level class (not a nested one) for the same ArchUnit-engine reason `EnforcedAuthProfile`
 * itself is top-level, see [de.littletrotter.infrastructure.auth.InternalAuthVerifyEnforcedIT]'s
 * own KDoc.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DocumentDownloadAuthEnforcedProfile::class)
class DocumentDownloadAuthEnforcedIT {

    @Test
    fun `the pdf route answers 401 for an anonymous caller`() {
        given()
            .`when`().get("/documents/jobs/{jobId}/pdf", 1L)
            .then().statusCode(401)
    }
}

class DocumentDownloadAuthEnforcedProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "auth.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "false",
        "quarkus.http.auth.permission.authenticated.policy" to "authenticated",
    )
}
