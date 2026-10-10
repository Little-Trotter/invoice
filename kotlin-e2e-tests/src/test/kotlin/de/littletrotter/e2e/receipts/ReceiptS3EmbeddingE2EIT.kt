package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceStornoService
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The E2E level of the receipts feature, run through the REAL object store
 * ([S3TestResource]'s RustFS container via the injected [DocumentStore] bean), not the
 * [de.littletrotter.shared.DocumentStore] in-memory stand-in [ReceiptEmbeddingE2EIT] and
 * [StornoReceiptEmbeddingE2EIT] use — the S3/RustFS receipt store is a deliberate architecture
 * decision (see those files' own KDoc for the render/KoSIT stubbing rationale, reused here
 * verbatim; only the store differs).
 *
 * Three scenarios, all against the single-BG-24-embedding contract (a receipt is never rasterized
 * into the PDF as an annex page any more, only carried as Base64 inside the embedded
 * `factur-x.xml`/`xrechnung.xml`, see kotlin-xpdf's `MustangTransactionBuilder` KDoc):
 * - [S3-01]: happy path — upload a receipt to an invoice and another to one of its positions,
 *   through the real stack, then export and find both embedded as their own BG-24 entry.
 * - [S3-02]: border — the same receipt at invoice level and on two positions embeds exactly once,
 *   not three times.
 * - [S3-03]: border — a receipt on a position of an invoice that is then storno'd travels to the
 *   storno document (ST-3 full copy) and neither copy loses its own links, and the storno's own
 *   export still embeds it exactly once.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class ReceiptS3EmbeddingE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var stornoService: InvoiceStornoService

    /** The receipt services, split into 4 units; a plain field bundle, no forwarding methods,
     *  since this fixture is shared across tests that each only need a subset. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    private fun receiptService(): TestReceiptServices {
        val pool = ReceiptPoolService(receiptRepository, documentStore)
        val budget = ReceiptBudgetService(receiptRepository)
        return TestReceiptServices(
            upload = ReceiptUploadService(receiptRepository, documentStore, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, pool, budget),
            budget = budget,
            pool = pool,
        )
    }

    private fun blankInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E S3 receipt-embedding base page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** A real, parseable one-page PDF — kept genuinely valid, not a magic-header stub, since the
     *  raw bytes are what actually reaches the wire as the BG-24 `AttachmentBinaryObject`. */
    private fun pdf(marker: Byte): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E S3 receipt fixture marker=$marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** Builds a two-item DRAFT invoice (fixture-per-file convention, see
     *  [ReceiptEmbeddingE2EIT.buildInvoiceFixture]'s own KDoc), persisted via plain JPA. */
    private fun buildInvoiceFixture(tag: String): Invoice {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "Beleg S3 E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE111111111")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply { customerNumber = CustomerNumber("$tag-100"); name = "Kunde AG"; vatId = VatId("DE222222222") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("$tag-2026-0001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 8, 1)
            paymentTermsDays = 14
            addItem(
                de.littletrotter.domain.invoice.InvoiceItem().apply {
                    positionId = "1"; description = "Beratung"
                    quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("100.00"))
                    sortOrder = 1
                }.also { it.applyTaxRule(taxRate) },
            )
            addItem(
                de.littletrotter.domain.invoice.InvoiceItem().apply {
                    positionId = "2"; description = "Material"
                    quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("50.00"))
                    sortOrder = 2
                }.also { it.applyTaxRule(taxRate) },
            )
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        return invoice
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Drives the real Mustang export route against the real (S3-backed) [documentStore], with
     *  only the render step stubbed — same convention as [ReceiptEmbeddingE2EIT.exportPdf].
     *  [XmlExportServiceImpl] is given the same real [documentStore] the receipts were uploaded
     *  into (not `null`) — it is the one that actually reads a receipt's bytes back out to embed
     *  them as BG-24 `AttachmentBinaryObject`s. */
    private fun exportPdf(dto: InvoiceDto, jobId: String): ByteArray {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(blankInvoicePage())

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(documentStore),
            reportRenderer = renderer,
            documentStore = documentStore,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        // The same single derivation point PdfExportServiceImpl itself uses
        // (de.littletrotter.shared.DocumentKeyRules) — not a guessed/duplicated key pattern.
        val objectKey = service.performExport(dto, jobId).objectKey
        check(documentStore.exists(objectKey)) { "expected exported PDF at $objectKey" }
        return documentStore.get(objectKey)
    }

    /** Extracts the embedded `factur-x.xml`/`xrechnung.xml` attachment's own bytes from the
     *  exported hybrid PDF — same detection idiom [de.littletrotter.e2e.einvoice
     *  .Bt19EndToEndE2EIT.extractEmbeddedXml] uses, duplicated locally per this module's own
     *  per-file-helper convention (see this class's own KDoc). `null` when no such attachment
     *  exists. */
    private fun extractEmbeddedXml(pdfBytes: ByteArray): String? = try {
        Loader.loadPDF(pdfBytes).use { doc ->
            val names = doc.documentCatalog.names?.embeddedFiles?.names ?: emptyMap()
            val spec = names["factur-x.xml"] ?: names["xrechnung.xml"]
            spec?.embeddedFile?.toByteArray()?.let { String(it, Charsets.UTF_8) }
        }
    } catch (ex: Exception) {
        null
    }

    /** The ordered list of BG-24 `IssuerAssignedID` values (BT-122, one per embedded receipt)
     *  actually embedded in [pdfBytes]'s XML — fails loudly if the export carries no embedded XML
     *  at all, since every export in this suite has an XML by construction. */
    private fun embeddedReceiptNames(pdfBytes: ByteArray): List<String> {
        val xml = extractEmbeddedXml(pdfBytes) ?: error("the export must carry exactly one embedded XML")
        return EInvoiceXml.textList(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:IssuerAssignedID",
        )
    }

    // ── S3-01 — happy path: upload to invoice AND to a position, through the real S3 stack ────

    @Test
    @Transactional
    fun `S3-01 - happy path - a receipt uploaded to the invoice and one uploaded to a position both embed as their own BG-24 entry through the real S3 stack`() {
        val invoice = buildInvoiceFixture("RS301")
        val item1 = invoice.itemList[0].id!!
        val svc = receiptService()

        val invoiceReceipt = svc.upload.upload("invoice-direct-upload.pdf", "Beleg invoice-direct-upload.pdf", pdf(1), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invoiceReceipt.id!!))
        val itemReceipt = svc.upload.upload("position-direct-upload.pdf", "Beleg position-direct-upload.pdf", pdf(2), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(item1, listOf(itemReceipt.id!!))
        em.flush(); em.clear()

        // Proves the upload actually went through the real object store, not just the DB row.
        assertTrue(documentStore.exists(invoiceReceipt.objectKey), "invoice-level receipt bytes must be in S3")
        assertTrue(documentStore.exists(itemReceipt.objectKey), "position-level receipt bytes must be in S3")

        val dto = mapToDto(invoice.id!!)
        val pdfBytes = exportPdf(dto, "s3-01")

        Loader.loadPDF(pdfBytes).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages — only the base invoice page") }
        val names = embeddedReceiptNames(pdfBytes)
        assertTrue(names.contains("invoice-direct-upload.pdf"), "the invoice-level upload must be embedded: $names")
        assertTrue(names.contains("position-direct-upload.pdf"), "the position-level upload must be embedded: $names")
    }

    // ── S3-02 — border: same receipt at invoice level and on two positions, embedded once ──────

    @Test
    @Transactional
    fun `S3-02 - border - the same receipt attached at invoice level and on two positions embeds exactly once`() {
        val invoice = buildInvoiceFixture("RS302")
        val item1 = invoice.itemList[0].id!!
        val item2 = invoice.itemList[1].id!!
        val svc = receiptService()

        val shared = svc.upload.upload("dreifach-s3.pdf", "Beleg dreifach-s3.pdf", pdf(3), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(shared.id!!))
        svc.link.addReceiptsToItem(item1, listOf(shared.id!!))
        svc.link.addReceiptsToItem(item2, listOf(shared.id!!))
        em.flush(); em.clear()

        // Three independent junction rows in the DB, but ONE BG-24 entry.
        assertEquals(3, svc.pool.findReceiptUsage(shared.id!!).size, "three distinct links must exist before export")

        val dto = mapToDto(invoice.id!!)
        val pdfBytes = exportPdf(dto, "s3-02")

        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages, "receipts never add pages — only the base invoice page")
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles?.names
            assertEquals(1, embeddedFiles?.size, "exactly one embedded file regardless of how many receipts")
        }
        val names = embeddedReceiptNames(pdfBytes)
        assertEquals(listOf("dreifach-s3.pdf"), names, "the shared receipt must be embedded exactly once, not once per link")
    }

    // ── S3-03 — border: a position's receipt travels to the storno document ────────────────────

    @Test
    fun `S3-03 - border - a receipt on a position of an invoice that is then storno'd travels to the storno document without losing either copy's links`() {
        val tag = "RS303"
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                phone = "+49 30 1234567"
                name = "Storno S3 E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example"); taxNumber = TaxNumber("12/345/67890"); vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100"); name = "Storno S3 Kunde AG"
                email = Email.ofNullable("ap@$tag.example"); vatId = VatId("DE222222222")
                routingId = "PO-$tag-STANDING"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender); em.persist(recipient)
            val taxRate = TaxRate().apply {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate); em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val originalId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, originalId)!!.deliveryDate = LocalDate.of(2026, 1, 15) }
        val item1 = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                originalId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"), taxRateId = taxRateId,
                ),
            ).itemList.first().id!!
        }

        val svc = receiptService()
        val itemReceiptId = QuarkusTransaction.requiringNew().call {
            val itemReceipt = svc.upload.upload("position-vor-storno-s3.pdf", "Beleg position-vor-storno-s3.pdf", pdf(4), LocalDate.of(2026, 3, 14))
            svc.link.addReceiptsToItem(item1, listOf(itemReceipt.id!!))
            itemReceipt.id!!
        }
        assertTrue(documentStore.exists(receiptRepository.findById(itemReceiptId)!!.objectKey), "the position receipt's bytes must be in S3 before storno")

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.SENT) }

        val creditNoteId = stornoService.createCreditNote(originalId, "Leistung storniert").id!!

        QuarkusTransaction.requiringNew().run {
            val creditNote = em.find(Invoice::class.java, creditNoteId)!!
            assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, creditNote.typeCode)
        }

        // Neither copy loses its own link — the duplicate is a genuinely independent junction row,
        // and both still resolve to the same S3 object (an assignment never re-uploads bytes).
        QuarkusTransaction.requiringNew().run {
            val creditNoteItem = em.find(Invoice::class.java, creditNoteId)!!.itemList.first { it.description == "Beratung" }.id!!
            val creditNoteReceipts = svc.link.findReceiptsForItem(creditNoteItem)
            assertEquals(listOf("position-vor-storno-s3.pdf"), creditNoteReceipts.map { it.fileName }, "the storno document must carry its own copy of the link")
            assertEquals(listOf(itemReceiptId), creditNoteReceipts.map { it.id }, "same pool receipt, new junction row")

            val originalReceipts = svc.link.findReceiptsForItem(item1)
            assertEquals(listOf(itemReceiptId), originalReceipts.map { it.id }, "the original's own link must survive untouched")
        }

        val creditNoteDto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        val pdfBytes = exportPdf(creditNoteDto, "s3-03")
        Loader.loadPDF(pdfBytes).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages — only the base invoice page") }
        assertEquals(
            listOf("position-vor-storno-s3.pdf"), embeddedReceiptNames(pdfBytes),
            "the storno document's own export must embed the duplicated receipt too",
        )
    }
}
