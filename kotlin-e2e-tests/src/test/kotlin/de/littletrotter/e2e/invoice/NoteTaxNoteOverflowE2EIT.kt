package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceReverseChargeService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.initialInvoiceNoteSnapshot
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.invoiceNoteFor
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.PdfLayoutInspector
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

/**
 * Fix for `fix-note-taxnote-box-overflow.md` — end to end against a real Postgres and the real
 * Typst renderer: one invoice with a genuinely long seller note (footerNote, 12
 * word-wrapped lines) and a real Reverse-Charge tax note (the seeded `EU_B2B_RC_SERVICE` rule's
 * BT-120 text, applied through the real production path,
 * [InvoiceLifecycleService.setReverseChargeDecision]), rendered via the export path. Confirms every line of
 * both boxes prints and neither box's text visually overlaps the block that follows it — the
 * actual defect PO reported, not just that the PDF renders without exception.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class NoteTaxNoteOverflowE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var reverseChargeService: InvoiceReverseChargeService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

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

    private fun exportPdf(dto: InvoiceDto, jobId: String): ByteArray {
        val store = InMemoryDocumentStore()
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        val objectKey = service.performExport(dto, jobId).objectKey
        return store.get(objectKey)
    }

    private val longFooterNote = (1..12).joinToString(" ") {
        "Zeile$it lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod."
    }

    /** Real production path: [SenderProfile.changeInvoiceNote] for the long footer note, and
     *  [InvoiceLifecycleService.setReverseChargeDecision] for the tax note — resolves the seeded
     *  `EU_B2B_RC_SERVICE` rule and its real BT-120 text
     *  (`V2__baseline_seed.sql`), exactly the way a real invoice would end up with both boxes on
     *  the same page. */
    private fun buildReverseChargeInvoiceWithLongNote(tag: String): Long {
        val sender = aSenderProfile {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.changeInvoiceNote(em.find(Language::class.java, 1), longFooterNote)
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Dupont Conseil S.A.R.L."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("FRXX123456789")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 1", "75001", "Paris", Country.FR, true))
        em.persist(sender)
        em.persist(recipient)

        // Placeholder pre-toggle rate, same idiom EInvoiceGenerationE2EIT's RC scenario uses —
        // setReverseChargeDecision below replaces it with the real seeded EU_B2B_RC_SERVICE rule.
        val placeholderRate = TaxRate().edit {
            code = "${tag}_DE_STD_19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(placeholderRate)

        val invoice = anInvoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // Same CORE.SERVICE_PERIOD_XOR guard EInvoiceGenerationE2EIT documents.
            deliveryDate = E2eBusinessDay.today()
        }
        invoice.addItem(
            anInvoiceItem().edit {
                positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Beratung"
                quantity = BigDecimal("5"); unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("100.00")
                sortOrder = 0
            }.also { it.applyTaxRule(placeholderRate) },
        )
        invoice.recalculateTotals()
        // INV-B (ea6d6dd64) — since that commit, InvoiceDtoMapper reads the invoice-owned note
        // snapshot, not SenderProfile.invoiceNoteFor(documentLanguage) directly. This fixture
        // builds the Invoice by hand instead of via InvoiceLifecycleService.create, so it must
        // take the snapshot itself, exactly as create() does.
        invoice.texts.applyInvoiceNote(
            initialInvoiceNoteSnapshot(sender.id, invoice.documentLanguage.code, sender.invoiceNoteFor(invoice.documentLanguage.code)),
        )
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!

        reverseChargeService.setReverseChargeDecision(invoiceId, invoice.version, true)
        em.flush()
        return invoiceId
    }

    @Test
    fun `a long note and a real Reverse-Charge tax note render fully and do not overlap`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { buildReverseChargeInvoiceWithLongNote("NTB1") }
        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }

        assertNoteAndTaxNoteDoNotOverlap(PdfLayoutInspector().inspect(exportPdf(dto, "NTB1")).pages)
    }

    private fun assertNoteAndTaxNoteDoNotOverlap(pages: List<PdfLayoutInspector.PageLayout>) {
        val blocks = pages.flatMap { it.textBlocks }
        assertTrue(blocks.any { it.text.contains("Zeile1 ") }, "Anfang des Hinweistexts fehlt, got: ${blocks.map { it.text }}")
        assertTrue(blocks.any { it.text.contains("Zeile12") }, "letzte Zeile des Hinweistexts wurde abgeschnitten, got: ${blocks.map { it.text }}")
        assertTrue(
            blocks.any { it.text.contains("Reverse Charge: Der Leistungsempfänger") },
            "Steuerhinweis-Text (BT-120, seeded EU_B2B_RC_SERVICE) fehlt, got: ${blocks.map { it.text }}",
        )
        assertTrue(
            blocks.any { it.text.contains("UStG.") },
            "letzte Zeile des Steuerhinweises wurde abgeschnitten, got: ${blocks.map { it.text }}",
        )

        // Overlap is a same-page property: a tax note that the layout moved to a later page
        // cannot collide with the note's last line on the page before.
        val notePage = pages.last { page -> page.textBlocks.any { it.text.contains("Zeile") || it.text.contains("eiusmod") } }
        val taxNotePage = pages.first { page -> page.textBlocks.any { it.text.contains("Reverse Charge: Der Leistungsempfänger") } }
        assertTrue(taxNotePage.pageIndex >= notePage.pageIndex, "Steuerhinweis (Seite ${taxNotePage.pageIndex}) steht vor dem Hinweistext (Seite ${notePage.pageIndex})")
        if (taxNotePage.pageIndex == notePage.pageIndex) {
            val noteBottom = notePage.textBlocks.filter { it.text.contains("Zeile") || it.text.contains("eiusmod") }.maxOf { it.y + it.height }
            val taxNoteTop = taxNotePage.textBlocks.first { it.text.contains("Reverse Charge: Der Leistungsempfänger") }.y
            assertTrue(
                noteBottom <= taxNoteTop,
                "Hinweistext endet bei y=$noteBottom, Steuerhinweis beginnt aber schon bei y=$taxNoteTop — die Blöcke überlappen",
            )
        }
    }
}
