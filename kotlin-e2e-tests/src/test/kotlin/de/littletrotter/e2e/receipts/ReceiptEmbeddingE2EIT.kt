package de.littletrotter.e2e.receipts

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.DocumentKeyRules
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId

/**
 * BL-2 §3 (receipt embedding in the export path) + PO addition (2026-08-30, updated by the BG-24
 * single-embedding decision) — an invoice with receipts assigned at BOTH levels (invoice + item)
 * goes through the REAL assignment (real [ReceiptLinkService], real PostgreSQL join tables), the
 * real [InvoiceDtoMapper] (so the wire DTO's receipts come from the pool, not id-only stubs — see
 * [InvoiceReceiptAssignmentIT]'s BI-T08 for why that distinction matters) and the real
 * [PdfExportServiceImpl.performExport] / [de.littletrotter.xpdf.impl.BasePdfStage.renderBase] /
 * `MustangTransactionBuilder` chain, asserting the BG-24 `AdditionalReferencedDocument` entries
 * actually produced in the exported XML. A receipt is never rasterized into the PDF as an annex
 * page any more — it is carried exactly once, as Base64 inside the embedded
 * `factur-x.xml`/`xrechnung.xml` — so this suite's own PDF-level assertion is that the page count
 * never grows with the receipt count, only the base invoice content does.
 *
 * This suite proves the *integration*: assignment through the DB survives the DTO mapping and
 * reaches the embedded XML unchanged, and that removing receipts through the real service and
 * re-exporting produces exactly the new set.
 *
 * Only the render step is stubbed (a single blank page, mirroring [EInvoiceGenerationE2EIT]'s own
 * `exportHybridPdf` convention: "the *visible* layout is never checked here, only the embedded
 * XML structure"); everything downstream of it — the Mustang export, the DTO mapping, PDF/A-3
 * base check — is real. The invoice stays DRAFT throughout: [ReceiptLinkService]'s read-only guard
 * (A9 in the BL-1 matrix) blocks receipt mutation once SENT/CANCELLED, and step 2 below
 * needs to remove receipts between two exports of the SAME invoice.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReceiptEmbeddingE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    /** Shared across upload (write) and export (read) — the same object store instance the real
     *  wiring wires [ReceiptUploadService]/[ReceiptPoolService] and [XmlExportServiceImpl] against
     *  in production, just in-memory here (same idiom as EInvoiceGenerationE2EIT's
     *  InMemoryDocumentStore). */
    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** A minimal single-page PDF standing in for the rendered invoice template — the page count
     *  this suite asserts on is always exactly this page's own count, since receipts never add
     *  pages of their own any more. */
    private fun blankInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E receipt-embedding base page")
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
                cs.showText("E2E receipt fixture marker=$marker")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    /** The receipt services, split into 4 units; a plain field bundle, no forwarding methods,
     *  since this fixture is shared across tests that each only need a subset. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    private fun testReceiptServices(store: DocumentStore): TestReceiptServices {
        val pool = ReceiptPoolService(receiptRepository, store)
        val budget = ReceiptBudgetService(receiptRepository)
        return TestReceiptServices(
            upload = ReceiptUploadService(receiptRepository, store, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))),
            link = ReceiptLinkService(receiptRepository, invoiceRepository, pool, budget),
            budget = budget,
            pool = pool,
        )
    }

    /** Builds a two-item DRAFT invoice (fixture-per-file convention, see EInvoiceGenerationE2EIT's
     *  own KDoc) and returns it together with a [TestReceiptServices] wired against [store]. */
    private fun buildInvoiceFixture(tag: String, store: InMemoryDocumentStore): Pair<Invoice, TestReceiptServices> {
        val sender = SenderProfile().apply {
            name = "Beleg Print E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE123456789")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply { customerNumber = CustomerNumber("$tag-100"); name = "Kunde AG"; vatId = VatId("DE987654321") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        // A real tax rate — Mustang's export model needs a resolved tax category/rate per item;
        // applyTaxRule (the real R1 domain method) mirrors EInvoiceGenerationE2EIT's own fixtures.
        val taxRate = TaxRate().apply {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 8, 1)
            paymentTermsDays = 14
            addItem(
                InvoiceItem().apply {
                    positionId = "1"; description = "Beratung"
                    quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("100.00"))
                    // Explicit, distinct, positive sortOrders — Invoice.addItem only
                    // auto-assigns when sortOrder <= 0, so unlike auto-assignment this
                    // guarantees a stable, unambiguous @OrderBy("sortOrder ASC") result.
                    sortOrder = 1
                }.also { it.applyTaxRule(taxRate) },
            )
            addItem(
                InvoiceItem().apply {
                    positionId = "2"; description = "Material"
                    quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("50.00"))
                    sortOrder = 2
                }.also { it.applyTaxRule(taxRate) },
            )
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val svc = testReceiptServices(store)
        return invoice to svc
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** [buildInvoiceFixture]'s own three-item sibling: "invoice with 3 items" per the
     *  spec's own setup, otherwise identical (DRAFT, numbered, real DE tax rate). */
    private fun buildThreeItemInvoiceFixture(tag: String, store: InMemoryDocumentStore): Pair<Invoice, TestReceiptServices> {
        val sender = SenderProfile().apply {
            name = "Beleg Print E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE123456789")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply { customerNumber = CustomerNumber("$tag-100"); name = "Kunde AG"; vatId = VatId("DE987654321") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 8, 1)
            paymentTermsDays = 14
            listOf("Beratung" to "100.00", "Material" to "50.00", "Sonstiges" to "25.00").forEachIndexed { index, (description, price) ->
                addItem(
                    InvoiceItem().apply {
                        positionId = (index + 1).toString(); this.description = description
                        quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal(price))
                        sortOrder = index + 1
                    }.also { it.applyTaxRule(taxRate) },
                )
            }
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val svc = testReceiptServices(store)
        return invoice to svc
    }

    /** A DRAFT invoice whose `invoiceNumber` is left at its default
     *  [InvoiceNumber.UNASSIGNED] (blank) — built by persisting `Invoice()` directly, bypassing
     *  [de.littletrotter.application.invoice.InvoiceLifecycleService.create] (which, since N5,
     *  numbers every draft from birth), so this fixture still exercises [DocumentKeyRules
     *  .deriveExportKeys]'s blank-number edge case on purpose, unlike [buildInvoiceFixture]'s
     *  numbered fixture. [customer] is shared across two calls of this so both drafts belong to
     *  the SAME customer, as the scenario requires. */
    private fun buildBlankNumberedDraftFixture(tag: String, customer: Recipient, store: InMemoryDocumentStore): Pair<Invoice, TestReceiptServices> {
        val sender = SenderProfile().apply {
            name = "Beleg Draft E2E GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
            vatId = VatId("DE123456789")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        em.persist(sender)

        val taxRate = TaxRate().apply {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            // invoiceNumber left at InvoiceNumber.UNASSIGNED (Invoice's own default) — a
            // genuinely blank-numbered draft, on purpose.
            senderProfile = sender
            this.recipient = customer
            invoiceDate = LocalDate.of(2026, 8, 1)
            paymentTermsDays = 14
            addItem(
                InvoiceItem().apply {
                    positionId = "1"; description = "Beratung"
                    quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal("100.00"))
                    sortOrder = 1
                }.also { it.applyTaxRule(taxRate) },
            )
        }
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val svc = testReceiptServices(store)
        return invoice to svc
    }

    /** Drives the real Mustang export route with only the render step stubbed (see class KDoc)
     *  and returns the final, stored PDF bytes. [XmlExportServiceImpl] is given the same [store]
     *  the receipts were uploaded into (not `null`) — it is the one that actually reads a
     *  receipt's bytes back out to embed them as BG-24 `AttachmentBinaryObject`s. */
    private fun exportPdf(dto: InvoiceDto, store: InMemoryDocumentStore, jobId: String): ByteArray {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(blankInvoicePage())

        // PdfExportServiceImplTestFactory does not default kositValidator (there is
        // no such default any more), so the always-valid KoSIT stand-in is inlined here instead —
        // this suite only asserts the receipt embedding, never KoSIT's own verdict.
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(store),
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        // The exact key the export reported (per-attempt, DocumentKeyRules.deriveJobExportKeys) —
        // distinct from the uploaded receipts' own "receipt-pool/<checksum>.pdf" keys in [store].
        val key = service.performExport(dto, jobId).objectKey
        return store.objects[key] ?: error("expected exported PDF at $key")
    }

    /** [exportPdf]'s `.single { ... }` lookup assumes exactly one invoice's PDF ever
     *  lands in [store]; this fixture puts TWO drafts' exports in the same store on purpose (to prove
     *  their keys don't collide), so this variant returns the exact key the export reported
     *  together with its bytes. */
    private fun exportPdfWithKey(dto: InvoiceDto, store: InMemoryDocumentStore, jobId: String): Pair<String, ByteArray> {
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
        return key to (store.objects[key] ?: error("expected exported PDF at $key"))
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
     *  actually embedded in [pdfBytes]'s XML — empty, not an error, when the export carries no
     *  embedded XML at all or no BG-24 entries (the documented no-receipts shape). */
    private fun embeddedReceiptNames(pdfBytes: ByteArray): List<String> {
        val xml = extractEmbeddedXml(pdfBytes) ?: return emptyList()
        return EInvoiceXml.textList(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:IssuerAssignedID",
        )
    }

    // ── (1) both levels assigned — each embeds as its own BG-24 entry, invoice-level first ────

    @Test
    @Transactional
    fun `BL2-3-01 - receipts at both invoice and item level each embed as their own BG-24 entry, invoice-level before item-level`() {
        val store = InMemoryDocumentStore()
        val (invoice, svc) = buildInvoiceFixture("RPA1", store)
        val item1 = invoice.itemList[0].id!!
        val item2 = invoice.itemList[1].id!!

        // Item-level: one receipt per item, in position order.
        val itemReceipt1 = svc.upload.upload("item1.pdf", "Beleg item1.pdf", pdf(1), LocalDate.of(2026, 3, 14))
        val itemReceipt2 = svc.upload.upload("item2.pdf", "Beleg item2.pdf", pdf(2), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(item1, listOf(itemReceipt1.id!!))
        svc.link.addReceiptsToItem(item2, listOf(itemReceipt2.id!!))

        // Invoice-level: two receipts on the invoice as a whole.
        val invReceipt1 = svc.upload.upload("invoice1.pdf", "Beleg invoice1.pdf", pdf(3), LocalDate.of(2026, 3, 14))
        val invReceipt2 = svc.upload.upload("invoice2.pdf", "Beleg invoice2.pdf", pdf(4), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invReceipt1.id!!, invReceipt2.id!!))
        em.flush(); em.clear()

        val dto = mapToDto(invoice.id!!)
        val pdfBytes = exportPdf(dto, store, "bl2-3-01")

        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages, "receipts never add pages — only the base invoice page")
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles?.names
            assertEquals(1, embeddedFiles?.size, "exactly one embedded file carries all four receipts")
        }

        // BG-24 dedup order — the invoice-level group precedes the item-level group
        // (MustangExportModelMapper.collectReceipts's own documented contract); the order *within*
        // each group is not guaranteed by the read model, so only the two groups (as sets) are
        // asserted, not an exact sequence across all four.
        val names = embeddedReceiptNames(pdfBytes)
        assertEquals(4, names.size, "all four distinct receipts must be embedded exactly once each")
        assertEquals(
            setOf("invoice1.pdf", "invoice2.pdf"), names.take(2).toSet(),
            "the invoice-level group must come first, in any order within the group",
        )
        assertEquals(
            setOf("item1.pdf", "item2.pdf"), names.drop(2).toSet(),
            "the item-level group must come last, in any order within the group",
        )
    }

    // ── (2) remove one item-level and one invoice-level receipt, regenerate ────

    @Test
    @Transactional
    fun `BL2-3-02 - removing one item-level and one invoice-level receipt then regenerating reflects exactly the remaining set`() {
        val store = InMemoryDocumentStore()
        val (invoice, svc) = buildInvoiceFixture("RPA2", store)
        val item1 = invoice.itemList[0].id!!

        val itemKeep = svc.upload.upload("item-keep.pdf", "Beleg item-keep.pdf", pdf(11), LocalDate.of(2026, 3, 14))
        val itemRemove = svc.upload.upload("item-remove.pdf", "Beleg item-remove.pdf", pdf(12), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(item1, listOf(itemKeep.id!!, itemRemove.id!!))

        val invKeep = svc.upload.upload("invoice-keep.pdf", "Beleg invoice-keep.pdf", pdf(13), LocalDate.of(2026, 3, 14))
        val invRemove = svc.upload.upload("invoice-remove.pdf", "Beleg invoice-remove.pdf", pdf(14), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invKeep.id!!, invRemove.id!!))
        em.flush(); em.clear()

        val firstDto = mapToDto(invoice.id!!)
        val firstExport = exportPdf(firstDto, store, "bl2-3-02-first")
        Loader.loadPDF(firstExport).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages") }
        assertEquals(
            setOf("item-keep.pdf", "item-remove.pdf", "invoice-keep.pdf", "invoice-remove.pdf"),
            embeddedReceiptNames(firstExport).toSet(),
            "all four receipts must be embedded before removal",
        )

        // Remove one at each level.
        svc.link.removeReceiptsFromItem(item1, listOf(itemRemove.id!!))
        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(invRemove.id!!))
        em.flush(); em.clear()

        val secondDto = mapToDto(invoice.id!!)
        val secondExport = exportPdf(secondDto, store, "bl2-3-02-second")

        Loader.loadPDF(secondExport).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages") }
        val names = embeddedReceiptNames(secondExport)
        assertTrue(names.contains("item-keep.pdf"), "the kept item-level receipt must still be embedded: $names")
        assertTrue(names.contains("invoice-keep.pdf"), "the kept invoice-level receipt must still be embedded: $names")
        assertFalse(names.contains("item-remove.pdf"), "the removed item-level receipt must be gone: $names")
        assertFalse(names.contains("invoice-remove.pdf"), "the removed invoice-level receipt must be gone: $names")
    }

    // ── (3) remove all receipts, regenerate ─────────────────────────────────────

    @Test
    @Transactional
    fun `BL2-3-03 - removing all receipts and regenerating produces the documented no-BG-24 base document`() {
        val store = InMemoryDocumentStore()
        val (invoice, svc) = buildInvoiceFixture("RPA3", store)
        val item1 = invoice.itemList[0].id!!

        val itemReceipt = svc.upload.upload("only-item.pdf", "Beleg only-item.pdf", pdf(21), LocalDate.of(2026, 3, 14))
        val invReceipt = svc.upload.upload("only-invoice.pdf", "Beleg only-invoice.pdf", pdf(22), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(item1, listOf(itemReceipt.id!!))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invReceipt.id!!))
        em.flush(); em.clear()

        val withReceiptsDto = mapToDto(invoice.id!!)
        val withReceiptsExport = exportPdf(withReceiptsDto, store, "bl2-3-03-with")
        Loader.loadPDF(withReceiptsExport).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages") }
        assertEquals(
            setOf("only-item.pdf", "only-invoice.pdf"), embeddedReceiptNames(withReceiptsExport).toSet(),
            "both receipts must be embedded before removal",
        )

        svc.link.removeReceiptsFromItem(item1, listOf(itemReceipt.id!!))
        svc.link.removeReceiptsFromInvoice(invoice.id!!, listOf(invReceipt.id!!))
        em.flush(); em.clear()

        val noReceiptsDto = mapToDto(invoice.id!!)
        assertTrue(noReceiptsDto.items.all { it.receipts.isEmpty() }, "DTO must carry no item-level receipts any more")
        assertTrue(noReceiptsDto.receipts.isEmpty(), "DTO must carry no invoice-level receipts any more")

        val noReceiptsExport = exportPdf(noReceiptsDto, store, "bl2-3-03-without")
        // Documented no-receipts behavior (MustangTransactionBuilder.getAdditionalReferencedDocuments
        // returns null for an empty spec list): no BG-24 group at all, base page count unaffected.
        Loader.loadPDF(noReceiptsExport).use { doc -> assertEquals(1, doc.numberOfPages, "no receipts left, same single base page as with receipts") }
        assertTrue(embeddedReceiptNames(noReceiptsExport).isEmpty(), "no BG-24 entries left once every receipt link is removed")
    }

    // ── Happy path — invoice-level + item-level + pool-only receipts ────────────────────────

    /** Three items, three receipts (one invoice-level, one on item 2, one left in the pool): the
     *  embedded XML must carry exactly the two assigned receipts, invoice-level before item-level
     *  ([MustangExportModelMapper.collectReceipts]'s documented dedup order), and never the
     *  pool-only one. */
    @Test
    @Transactional
    fun `EX-C1 - happy - one invoice-level receipt, one item2-level receipt and one pool-only receipt - only the two assigned ones embed`() {
        val store = InMemoryDocumentStore()
        val (invoice, svc) = buildThreeItemInvoiceFixture("EXC1", store)
        val item2 = invoice.itemList[1].id!!

        val invoiceReceipt = svc.upload.upload("invoice-level.pdf", "Beleg invoice-level.pdf", pdf(41), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToInvoice(invoice.id!!, listOf(invoiceReceipt.id!!))
        val itemReceipt = svc.upload.upload("item2-level.pdf", "Beleg item2-level.pdf", pdf(42), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(item2, listOf(itemReceipt.id!!))
        val poolOnly = svc.upload.upload("pool-only.pdf", "Beleg pool-only.pdf", pdf(43), LocalDate.of(2026, 3, 14))
        em.flush(); em.clear()

        assertTrue(svc.pool.findReceiptUsage(poolOnly.id!!).isEmpty(), "the pool-only receipt must carry no usage at all")

        val dto = mapToDto(invoice.id!!)
        val pdfBytes = exportPdf(dto, store, "ex-c1")

        Loader.loadPDF(pdfBytes).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages") }
        val names = embeddedReceiptNames(pdfBytes)
        assertEquals(
            listOf("invoice-level.pdf", "item2-level.pdf"), names,
            "deterministic dedup order — invoice-level before item-level",
        )
        assertFalse(names.contains("pool-only.pdf"), "the pool-only receipt must never be embedded")
    }

    // ── Border case — item deletion returns its receipt to the pool ─────────────────────────

    /** Deleting an item that carries a receipt must not orphan the `invoice_item_receipt` junction row (Hibernate owns that `@ManyToMany` from [de.littletrotter.domain.invoice.InvoiceItem.receipts] and clears it before the item row itself is deleted — the delete succeeding at all is itself part of the proof, since the join table carries no `ON DELETE CASCADE`, see `V1__baseline_schema.sql`'s `invoice_item_receipt` definition); the receipt itself survives, unassigned, in the pool. Disjoint from the PI-6 receipt-edge spec's deletion scenarios: those delete the *Receipt* record from the pool (Playwright/UI level); this deletes the *InvoiceItem* (facade level). */
    @Test
    @Transactional
    fun `EX-C2 - border - a receipt assigned to a DELETED item returns to the pool with no orphaned FK and is not embedded`() {
        val store = InMemoryDocumentStore()
        val (invoice, svc) = buildInvoiceFixture("EXC2", store)
        val item1Id = invoice.itemList[0].id!!
        val item1PositionId = invoice.itemList[0].positionId

        val receipt = svc.upload.upload("position-to-delete.pdf", "Beleg position-to-delete.pdf", pdf(44), LocalDate.of(2026, 3, 14))
        svc.link.addReceiptsToItem(item1Id, listOf(receipt.id!!))
        em.flush(); em.clear()
        assertEquals(1, svc.pool.findReceiptUsage(receipt.id!!).size, "one usage before the item is deleted")

        itemMutationService.deleteItemByPosition(invoice.id!!, item1PositionId)
        em.flush(); em.clear()

        assertTrue(svc.pool.findReceiptUsage(receipt.id!!).isEmpty(), "no orphaned FK — the receipt has no usages left, it returned to the pool")
        assertNotNull(receiptRepository.findById(receipt.id!!), "the receipt row itself is never deleted by an item deletion")

        val dto = mapToDto(invoice.id!!)
        assertTrue(dto.items.all { it.receipts.isEmpty() }, "no remaining item carries the receipt")
        val pdfBytes = exportPdf(dto, store, "ex-c2")
        Loader.loadPDF(pdfBytes).use { doc -> assertEquals(1, doc.numberOfPages, "receipts never add pages") }
        assertTrue(embeddedReceiptNames(pdfBytes).isEmpty(), "no BG-24 entry left for the now-unassigned pool receipt")
    }

    // ── Border case — blank-numbered drafts of the same customer, no key collision ──────────

    /** A blank-numbered DRAFT ([DocumentKeyRules.deriveJobExportKeys]) exports under `invoices/draft/<id>-<job>-<attempt>`, and a second blank-numbered draft of the SAME customer gets its own distinct key from its own id — the two never collide. */
    @Test
    @Transactional
    fun `EX-C4 - border - two blank-numbered drafts of the same customer export under distinct draft-id keys, no embedded-receipt cross-contamination`() {
        val store = InMemoryDocumentStore()
        val customer = Recipient().apply { customerNumber = CustomerNumber("EXC4-100"); name = "Kunde AG"; vatId = VatId("DE555555555") }
        customer.addAddress(Address.forRecipient(customer, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(customer)

        val (draft1, svc1) = buildBlankNumberedDraftFixture("EXC4A", customer, store)
        val (draft2, svc2) = buildBlankNumberedDraftFixture("EXC4B", customer, store)
        assertFalse(draft1.invoiceNumber.isAssigned(), "fixture sanity: draft 1 is genuinely blank-numbered")
        assertFalse(draft2.invoiceNumber.isAssigned(), "fixture sanity: draft 2 is genuinely blank-numbered")
        assertNotEquals(draft1.id, draft2.id)

        val draft1Receipt = svc1.upload.upload("draft1-receipt.pdf", "Beleg draft1-receipt.pdf", pdf(45), LocalDate.of(2026, 3, 14))
        svc1.link.addReceiptsToInvoice(draft1.id!!, listOf(draft1Receipt.id!!))
        val draft2Receipt = svc2.upload.upload("draft2-receipt.pdf", "Beleg draft2-receipt.pdf", pdf(46), LocalDate.of(2026, 3, 14))
        svc2.link.addReceiptsToInvoice(draft2.id!!, listOf(draft2Receipt.id!!))
        em.flush(); em.clear()

        val dto1 = mapToDto(draft1.id!!)
        val dto2 = mapToDto(draft2.id!!)
        val (key1, pdf1) = exportPdfWithKey(dto1, store, "ex-c4-1")
        val (key2, pdf2) = exportPdfWithKey(dto2, store, "ex-c4-2")
        assertTrue(key1.startsWith("invoices/draft/${draft1.id}-"), key1)
        assertTrue(key2.startsWith("invoices/draft/${draft2.id}-"), key2)
        assertNotEquals(key1, key2, "same customer, two drafts — the invoice id, not the (identically blank) number, disambiguates the key")
        assertTrue(store.objects.containsKey(key1))
        assertTrue(store.objects.containsKey(key2))

        Loader.loadPDF(pdf1).use { doc -> assertEquals(1, doc.numberOfPages, "draft 1's own base page, receipts never add pages") }
        assertEquals(listOf("draft1-receipt.pdf"), embeddedReceiptNames(pdf1), "draft 1 embeds only its own receipt")
        Loader.loadPDF(pdf2).use { doc -> assertEquals(1, doc.numberOfPages, "draft 2's own base page, unaffected by draft 1's export") }
        assertEquals(listOf("draft2-receipt.pdf"), embeddedReceiptNames(pdf2), "draft 2 embeds only its own receipt, never draft 1's")
    }
}
