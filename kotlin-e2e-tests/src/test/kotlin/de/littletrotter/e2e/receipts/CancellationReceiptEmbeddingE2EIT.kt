package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
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
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import de.littletrotter.fixture.edit

/**
 * PO wave G (2026-08-30), Auftrag 5 — the cancellation "full copy" contract end-to-end: an `ISSUED`
 * invoice with receipts assigned at BOTH levels (invoice + item) is cancelled
 * ([InvoiceCancellationService.createCreditNote]), and the resulting cancellation document must carry the SAME
 * receipt assignments (duplicated junction rows, not moved ones), the full item/amount copy, the
 * BG-3 references, type code 384 on the wire (PO decision 1, 2026-09-02; previously 381) — and,
 * since it carries the same receipts, the SAME set of BG-24 embeddings when exported (a receipt is
 * rasterized behind the invoice pages and carried once as Base64 inside the embedded XML). Reuses [ReceiptEmbeddingE2EIT]'s fixture/export patterns (real
 * [ReceiptLinkService], real PostgreSQL join tables, real [InvoiceDtoMapper], real Mustang export
 * chain with only the base-page render stubbed).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class CancellationReceiptEmbeddingE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceLookup: InvoiceLookupRepository

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    /** Same idiom as [ReceiptEmbeddingE2EIT.InMemoryDocumentStore] — shared across the original's
     *  upload and the credit note's later export, since duplicating a receipt ASSIGNMENT (a new
     *  junction row pointing at the same [de.littletrotter.domain.masterdata.Receipt]) never
     *  re-uploads the bytes; both invoices' exports embed the exact same object-store entry. */
    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    private fun blankInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E storno-embedding base page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** A genuinely parseable one-page PDF — see [ReceiptEmbeddingE2EIT.pdf]'s own KDoc: the raw
     *  bytes are what actually reaches the wire as the BG-24 `AttachmentBinaryObject`. */
    private fun pdf(marker: Byte): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            PDPageContentStream(doc, doc.getPage(0)).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E storno receipt fixture marker=$marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    private fun exportPdf(dto: InvoiceDto, store: InMemoryDocumentStore, jobId: String): ByteArray {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(blankInvoicePage())

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(store),
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        val key = service.performExport(dto, jobId).objectKey
        return store.objects[key] ?: error("expected exported PDF at $key")
    }

    /** Extracts the embedded `factur-x.xml`/`xrechnung.xml` attachment's own bytes from the
     *  exported hybrid PDF — same detection idiom [de.littletrotter.e2e.einvoice
     *  .Bt19EndToEndE2EIT.extractEmbeddedXml] uses, duplicated locally per this module's own
     *  per-file-helper convention. `null` when no such attachment exists. */
    private fun extractEmbeddedXml(pdfBytes: ByteArray): String? = try {
        Loader.loadPDF(pdfBytes).use { doc ->
            val names = doc.documentCatalog.names?.embeddedFiles?.names ?: emptyMap()
            val spec = names["factur-x.xml"] ?: names["xrechnung.xml"]
            spec?.embeddedFile?.toByteArray()?.let { String(it, Charsets.UTF_8) }
        }
    } catch (ex: Exception) {
        null
    }

    /** The ordered list of BG-24 `IssuerAssignedID` values embedded in [pdfBytes]'s XML. */
    private fun embeddedReceiptNames(pdfBytes: ByteArray): List<String> {
        val xml = extractEmbeddedXml(pdfBytes) ?: error("the export must carry exactly one embedded XML")
        return EInvoiceXml.textList(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:IssuerAssignedID",
        )
    }

    @Test
    fun `ISSUED invoice with receipts on both levels - cancellation carries the full copy and its own export embeds the same three receipts`() {
        val store = InMemoryDocumentStore()
        val tag = "STA1"

        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                phone = "+49 30 1234567"
                name = "Storno Annex E2E GmbH"
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
                name = "Storno Annex Kunde AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                // BT-10 (Käuferreferenz) — BuyerReferenceRequirement.ensureBuyerReferenceForFinalization
                // falls back to this before ISSUED; without it, the fixture cannot reach ISSUED at all.
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
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val originalId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        // D-R1 (CORE.SERVICE_PERIOD_XOR) — satisfied cheaply with BT-72, same idiom as
        // Bt19EndToEndE2EIT's fixture: not what this suite exercises.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, originalId)!!.edit { deliveryDate = LocalDate.of(2026, 1, 15) } }

        val itemIds = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                originalId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
            val withSecondItem = itemMutationService.addItem(
                originalId,
                InvoiceItemCommand(
                    // PRODUCT, not SERVICE: LeistungsverzeichnisRules only allows PIECE for
                    // PRODUCT positions (f8d9f1a25 enforces this at addItem).
                    category = InvoiceItemCategory.PRODUCT,
                    description = "Material",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.PIECE,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = taxRateId,
                ),
            )
            withSecondItem.itemList.map { it.id!! }
        }
        val item1 = itemIds[0]

        // Setup: receipts at both item and invoice level
        val receiptUploadSvc = receiptUploadService(receiptRepository, store, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
        val receiptPoolSvc = ReceiptPoolService(receiptRepository, store)
        val receiptLinkSvc = ReceiptLinkService(receiptRepository, invoiceRepository, invoiceLookup, receiptPoolSvc, ReceiptBudgetService(receiptRepository))
        val (itemReceiptId, invoiceReceipt1Id, invoiceReceipt2Id) = QuarkusTransaction.requiringNew().call {
            val itemReceipt = receiptUploadSvc.upload("stundenzettel.pdf", "Beleg stundenzettel.pdf", pdf(1), LocalDate.of(2026, 3, 14))
            receiptLinkSvc.addReceiptsToItem(item1, listOf(itemReceipt.id!!))
            val invoiceReceipt1 = receiptUploadSvc.upload("vertrag.pdf", "Beleg vertrag.pdf", pdf(2), LocalDate.of(2026, 3, 14))
            val invoiceReceipt2 = receiptUploadSvc.upload("anschreiben.pdf", "Beleg anschreiben.pdf", pdf(3), LocalDate.of(2026, 3, 14))
            receiptLinkSvc.addReceiptsToInvoice(originalId, listOf(invoiceReceipt1.id!!, invoiceReceipt2.id!!))
            Triple(itemReceipt.id!!, invoiceReceipt1.id!!, invoiceReceipt2.id!!)
        }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.ISSUED) }

        val originalBefore = QuarkusTransaction.requiringNew().call {
            val inv = em.find(Invoice::class.java, originalId)!!
            assertEquals(InvoiceStatus.ISSUED, inv.status)
            inv.invoiceNumber.value to inv.totals.grossAmount.amount
        }

        val creditNoteId = cancellationService.createCreditNote(originalId, "Leistung storniert").id!!

        // Full copy: positions, amounts, BG-3 references
        QuarkusTransaction.requiringNew().run {
            val creditNote = em.find(Invoice::class.java, creditNoteId)!!
            assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, creditNote.typeCode)
            assertEquals(originalBefore.first, creditNote.originalInvoiceNumber?.value, "BT-25")
            assertEquals(2, creditNote.itemList.size, "volle Kopie: beide Positionen")
            // PO decision 2 (2026-09-02, sign logic) — the cancellation's total is the exact NEGATIVE of
            // the original's, forced by BR-CO-25 once payment terms are gone (InvoiceCancellationService's
            // own class KDoc).
            assertEquals(0, originalBefore.second.negate().compareTo(creditNote.totals.grossAmount.amount), "Gesamtbetrag negiert")
            assertTrue(creditNote.totals.grossAmount.amount.signum() < 0, "384 traegt negative Betraege")
            assertNull(creditNote.dueDate, "keine Zahlungsziel-Uebernahme (PO-Entscheidung 2, 2026-09-02)")
        }

        // Receipt assignments duplicated, not moved
        QuarkusTransaction.requiringNew().run {
            val creditNoteItem1 = em.find(Invoice::class.java, creditNoteId)!!.itemList.first { it.description == "Beratung" }.id!!
            assertEquals(listOf(itemReceiptId), receiptLinkSvc.findReceiptsForItem(creditNoteItem1).map { it.id }, "item-level Duplikat")
            assertEquals(
                setOf(invoiceReceipt1Id, invoiceReceipt2Id),
                receiptLinkSvc.findReceiptsForInvoice(creditNoteId).map { it.id }.toSet(),
                "invoice-level Duplikat",
            )
            // Original keeps its assignments untouched
            assertEquals(listOf(itemReceiptId), receiptLinkSvc.findReceiptsForItem(item1).map { it.id })
            assertEquals(
                setOf(invoiceReceipt1Id, invoiceReceipt2Id),
                receiptLinkSvc.findReceiptsForInvoice(originalId).map { it.id }.toSet(),
            )
        }

        // ST-3: original is cancelled, otherwise untouched
        QuarkusTransaction.requiringNew().run {
            val original = em.find(Invoice::class.java, originalId)!!
            assertEquals(InvoiceStatus.CANCELLED, original.status, "das Original ist storniert")
            assertEquals(originalBefore.first, original.invoiceNumber.value, "es behaelt seine Nummer")
            assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, original.typeCode)
        }

        val dto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        assertEquals("384", dto.typeCode, "BT-3 der Stornorechnung auf der Leitung")
        assertEquals(originalBefore.first, dto.references.originalInvoiceNumber, "BT-25 auf der Leitung")

        // BG-24 embedding on the generated credit note PDF — one page per receipt follows the base page.
        val pdfBytes = exportPdf(dto, store, "storno-embedding-e2e")
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1 + 3, doc.numberOfPages, "Basisseite plus eine Seite je Beleg")
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles?.names
            assertEquals(1, embeddedFiles?.size, "genau eine eingebettete Datei fuer alle drei Belege")
        }
        // Rechnungsebene-Gruppe vor Positionsebene-Gruppe (MustangExportModelMapper.collectReceipts'
        // dokumentierter Vertrag) — die Reihenfolge INNERHALB einer Gruppe ist vom Read-Modell nicht
        // garantiert, deshalb nur die beiden Gruppen (als Mengen) pruefen, keine exakte Sequenz.
        val names = embeddedReceiptNames(pdfBytes)
        assertEquals(3, names.size, "alle drei duplizierten Belege muessen als eigener BG-24-Eintrag eingebettet sein")
        assertEquals(
            setOf("vertrag.pdf", "anschreiben.pdf"), names.take(2).toSet(),
            "die Rechnungsebene-Gruppe steht zuerst, Reihenfolge innerhalb der Gruppe beliebig",
        )
        assertEquals(
            setOf("stundenzettel.pdf"), names.drop(2).toSet(),
            "die Positionsebene-Gruppe folgt danach",
        )
    }
}
