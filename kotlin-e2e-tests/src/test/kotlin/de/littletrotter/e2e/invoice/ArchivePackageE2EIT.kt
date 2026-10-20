package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.archive.Ed25519ArchiveSigner
import de.littletrotter.shared.archive.Ed25519Keys
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.PublicKey
import java.time.LocalDate
import java.util.HexFormat
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The signed archive package (tar.gz), end to end against a real Postgres: an issued
 * invoice's export produces a package next to PDF/XML, verifiable with standard tooling semantics
 * (unpack, `sha256` check, Ed25519 verify against the exported public key — the revision-safety concept's
 * own "documented" verification contract); a cancellation document gets its own, independent package;
 * and the single-generation gate (a completed job locks further `createJob` calls) means the
 * worker never runs a second time for the same invoice, so the package it already wrote is never
 * touched again.
 *
 * CONTAINER-DEPENDENT (real Postgres via [PostgresTestResource]) — per this task's hard
 * constraints, written and compile-checked but deliberately NOT run in this environment (no
 * Docker). Deferred to a live-DB verification pass, same as every other kotlin-e2e-tests IT.
 *
 * Follows [InvoiceLifecycleWatermarkE2EIT]'s own established pattern: an in-memory [DocumentStore],
 * the real [PdfExportServiceImpl] driven directly (the worker itself is a separate NATS-connected
 * microservice this test suite does not stand up), and [GenerationJobService] for the single-generation gate
 * check that actually lives in kotlin-invoice, not kotlin-xpdf.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ArchivePackageE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Drives the real export pipeline (PDF+XML+archive package) with a real, freshly generated
     *  Ed25519 keypair signing every package this test builds — [publicKey] is the "exported
     *  public key" the revision-safety concept's verification contract hands to an auditor. */
    private val keyPair = Ed25519Keys.generateKeyPair()
    private val publicKey: PublicKey = keyPair.public
    private val signer = Ed25519ArchiveSigner(keyPair.private, Ed25519Keys.fingerprint(keyPair.public))

    /** Exports [dto] and returns the archive package key the export itself reported (per-attempt). */
    private fun exportInvoice(dto: InvoiceDto, store: InMemoryDocumentStore, jobId: String): String {
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
            archiveSigner = signer,
        )
        val result = service.performExport(dto, jobId)
        return result.archiveObjectKey ?: error("export of ${dto.invoiceNumber.value} stored no archive package")
    }

    /** Unpacks a tar.gz into path -> bytes, mirroring `ArchivePackageBuilderTest`'s own helper —
     *  the "external, standard tooling" unpack step this suite's verification asserts against. */
    private fun unpackTarGz(tarGzBytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        GzipCompressorInputStream(tarGzBytes.inputStream()).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    entries[entry.name] = tarIn.readAllBytes()
                    entry = tarIn.nextEntry
                }
            }
        }
        return entries
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The revision-safety concept's own verification contract: unpack, `sha256sum -c`-format check for
     *  every `original/` file, Ed25519 `verify` for every `signed/` file against [publicKey]. */
    private fun verifyPackage(entries: Map<String, ByteArray>, filenames: List<String>) {
        assertTrue(
            Ed25519Keys.verify(publicKey, entries.getValue("manifest.json"), entries.getValue("signed/manifest.json.sig")),
            "manifest.json signature must verify",
        )
        filenames.forEach { filename ->
            val original = entries.getValue("original/$filename")
            val hashLine = String(entries.getValue("original/$filename.sha256"), Charsets.UTF_8)
            assertEquals("${sha256Hex(original)}  $filename\n", hashLine, "sha256sum -c format must match the original bytes")
            assertTrue(
                Ed25519Keys.verify(publicKey, original, entries.getValue("signed/$filename.sig")),
                "$filename's own signature must verify against the exported public key",
            )
            assertTrue(
                Ed25519Keys.verify(publicKey, entries.getValue("original/$filename.sha256"), entries.getValue("signed/$filename.sha256.sig")),
                "$filename.sha256's own signature must verify",
            )
        }
    }

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
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
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun sentInvoice(tag: String): Long {
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR,
                    unitPriceNet = de.littletrotter.shared.Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return invoiceId
    }

    private fun createAndCompleteJob(invoiceId: Long): GenerationJobStatus {
        val job = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        val delegated = QuarkusTransaction.requiringNew().call { generationJobPublishingService.markDelegated(job) }
        val completed = QuarkusTransaction.requiringNew().call { generationJobResultHandler.markCompleted(delegated) }
        return completed.status
    }

    // ─── Happy path — an issued invoice gets a signed, externally verifiable archive package ────

    @Test
    fun `finalize invoice - archive package exists in store and verifies with standard tooling semantics`() {
        val tag = "AP1"
        val invoiceId = sentInvoice(tag)
        val store = InMemoryDocumentStore()

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        val archiveKey = exportInvoice(dto, store, "$tag-export")

        assertTrue(store.exists(archiveKey), "the archive package must be stored next to PDF/XML")

        val entries = unpackTarGz(store.get(archiveKey))
        val invoiceNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, invoiceId).invoiceNumber.value }
        val baseName = invoiceNumber.replace("/", "-")
        verifyPackage(entries, listOf("$baseName.pdf", "$baseName.xml"))
    }

    // ─── Cancellation gets its own, independent package ────────────────────────────────────────────

    @Test
    fun `cancellation - the credit note document gets its own signed archive package, distinct from the original`() {
        val tag = "AP2"
        val originalId = sentInvoice(tag)
        val store = InMemoryDocumentStore()

        val originalDto = QuarkusTransaction.requiringNew().call { mapToDto(originalId) }
        val originalArchiveKey = exportInvoice(originalDto, store, "$tag-original")

        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(originalId, "E2E RS-9 Storno").id!! }
        val creditNoteDto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        val creditNoteArchiveKey = exportInvoice(creditNoteDto, store, "$tag-storno")

        assertNotEquals(originalArchiveKey, creditNoteArchiveKey, "the storno document's package must have its own object key")
        assertTrue(store.exists(originalArchiveKey))
        assertTrue(store.exists(creditNoteArchiveKey))
        assertNotEquals(store.get(originalArchiveKey).toList(), store.get(creditNoteArchiveKey).toList())

        val creditNoteNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, creditNoteId).invoiceNumber.value }
        val entries = unpackTarGz(store.get(creditNoteArchiveKey))
        verifyPackage(entries, listOf("${creditNoteNumber.replace("/", "-")}.pdf", "${creditNoteNumber.replace("/", "-")}.xml"))
    }

    // ─── Single-generation gate — a second job attempt never touches the stored package ────

    @Test
    fun `second job attempt is rejected by the RS-1 gate - the already-stored archive package is left untouched`() {
        val tag = "AP3"
        val invoiceId = sentInvoice(tag)
        val store = InMemoryDocumentStore()

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        val archiveKey = exportInvoice(dto, store, "$tag-first")
        val firstBytes = store.get(archiveKey)

        // The first job is COMPLETED — the single-generation gate (GenerationJobService.createJob) rejects
        // every further job for this now-issued invoice from this point on.
        assertEquals(GenerationJobStatus.COMPLETED, createAndCompleteJob(invoiceId))
        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        }

        // No second worker run ever happened (the gate rejected it before publish) — the store
        // still holds exactly the first, untouched package.
        assertArrayEquals(firstBytes, store.get(archiveKey), "a rejected second job must never overwrite the already-stored package")
    }
}
