package de.littletrotter.e2e.trip

import de.littletrotter.application.export.port.outbound.TripWorkbookExportPort
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.ValkeyTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.ExportResult
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PositionNumberFormatter
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.infrastructure.messaging.JobDispatcher
import de.littletrotter.xpdf.infrastructure.messaging.JobResultReporter
import de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Base64
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * A business trip through the real e-invoice pipeline (TE-1..TE-5): [PdfExportServiceImpl]
 * (Mustang route) driven directly, the real, embedded [EmbeddedKositValidator] (never stubbed),
 * and — for TE-2 — the real Valkey round trip a trip workbook takes from
 * [InvoiceDtoMapper.buildTripWorkbookRef] (producer, `SETEX`s `lt:tripwb:{sha256}` via the real
 * [TripWorkbookExportPort]/[PayloadExchange] beans) to [JobConsumer.resolveTripWorkbookRefs]
 * (consumer, `GET`+verify), exactly the seam wired in production — reached here via
 * reflection on the real, private method (the same reflection idiom
 * [de.littletrotter.e2e.invoice.DemoIssuedDocumentsE2EIT.invokeBuildDownloadAction]/
 * [de.littletrotter.e2e.nats.NatsE2ESupport.injectField] already use to cross a module-internal
 * boundary), never a re-implementation of that resolution logic.
 *
 * Fixture idiom follows [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT]: parties/tax rate
 * built via [EntityManager], the trip itself built exclusively through [TripService.addTrip].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class TripEInvoiceE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var tripService: TripService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var tripWorkbookExportPort: TripWorkbookExportPort

    /** The real, CDI-wired mapper (payloadExchange + tripWorkbookExportPort both real beans) —
     *  only this instance ever populates [de.littletrotter.shared.TripExportDto.workbookRef]. */
    @Inject
    lateinit var invoiceDtoMapper: InvoiceDtoMapper

    @Inject
    lateinit var payloadExchange: PayloadExchange

    companion object {
        private lateinit var kosit: EmbeddedKositValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
        }
    }

    // No @AfterEach cleanup: every fixture in this suite is sent to ISSUED (or, for TE-4, a
    // CANCELLED cancellation document) with a TRIP position in place -- the issued-invoice freeze's own
    // `trg_trip_expense_before_write` rejects a DELETE of an issued invoice's `trip_expense` rows,
    // which a plain cascade-remove would attempt. Left permanently in this shared database, the
    // same convention `TripExpenseIT`'s own class KDoc documents (unique per-scenario tags avoid
    // any collision), matching `InvoiceGenerationLockE2EIT`'s own precedent of no cleanup at all.

    // ─── Fixtures ─────────────────────────────────────────────────────────────────────────────

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

    /** Builds an ISSUED invoice with one SERVICE item and one TRIP item, the trip built through
     *  [TripService.addTrip]. Returns the invoice id and the trip's own position id. */
    private fun sentTripInvoice(tag: String, trip: TripCommand): Pair<Long, String> {
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(invoiceId, trip)
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return invoiceId to positionId
    }

    /** The real, CDI-wired mapper — populates `trip.workbookRef` whenever the invoice carries a
     *  TRIP position, since [tripWorkbookExportPort]/[payloadExchange] are both real beans here. */
    private fun mapToDto(invoiceId: Long): InvoiceDto =
        QuarkusTransaction.requiringNew().call { invoiceDtoMapper.mapToDto(em.find(Invoice::class.java, invoiceId)!!) }

    /** Reflectively invokes the real, private [JobConsumer.resolveTripWorkbookRefs] — the exact
     *  method the worker calls before rendering — against a [JobDispatcher] instance wired with the
     *  real [payloadExchange] and throwaway mocks for the collaborators that method never reads. */
    private fun resolveTripWorkbookRefs(dto: InvoiceDto): InvoiceDto {
        val dispatcher = JobDispatcher(mock(), payloadExchange, JobResultReporter(mock<ResultPublisher>()))
        val method = JobDispatcher::class.java.getDeclaredMethod("resolveTripWorkbookRefs", InvoiceDto::class.java)
        method.isAccessible = true
        return method.invoke(dispatcher, dto) as InvoiceDto
    }

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    private data class Exported(val result: ExportResult, val pdfBytes: ByteArray, val xmlBytes: ByteArray, val store: InMemoryDocumentStore)

    private fun export(dto: InvoiceDto, jobId: String): Exported {
        val store = InMemoryDocumentStore()
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : de.littletrotter.shared.KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = kosit.validate(xml, timeoutMs)
            },
        )
        val result = service.performExport(dto, jobId)
        return Exported(result, store.get(result.objectKey), store.get(result.zugferdXmlKey), store)
    }

    private fun pdfText(pdfBytes: ByteArray): String = Loader.loadPDF(pdfBytes).use { PDFTextStripper().getText(it) }

    /** Same shear-based watermark detection [de.littletrotter.e2e.invoice
     *  .DemoIssuedDocumentsE2EIT.watermarkTexts]/[de.littletrotter.e2e.invoice
     *  .InvoiceLifecycleWatermarkE2EIT] use — the diagonal mark's own glyphs, body text excluded. */
    private fun watermarkTexts(pdfBytes: ByteArray): List<String> = Loader.loadPDF(pdfBytes).use { doc ->
        (0 until doc.numberOfPages).map { index ->
            val stripper = object : PDFTextStripper() {
                val rotated = StringBuilder()
                override fun processTextPosition(text: TextPosition) {
                    if (kotlin.math.abs(text.textMatrix.shearY) > 0.01f) rotated.append(text.unicode)
                }
            }
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            stripper.rotated.toString().replace(Regex("\\s+"), "")
        }
    }

    // ─── TE-1 — grouped ───────────────────────────────────────────────────────────────────────

    @Test
    fun `TE-1 grouped - one BG-25 line per trip, quantity 1, KoSIT VALID, PDF shows title and gross line, no 3_1`() {
        val (invoiceId, positionId) = sentTripInvoice(
            "TE1",
            TripCommand(
                title = "TE1 Dienstreise Nürnberg", detailedOnInvoice = false,
                expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("158.88"))),
            ),
        )
        val dto = mapToDto(invoiceId)
        val exported = export(dto, "TE1-job")
        assertTrue(exported.result.kositSuccess, "KoSIT must be VALID: ${exported.result.kositReport}")

        val xml = String(exported.xmlBytes, Charsets.UTF_8)
        val lineIds = EInvoiceXml.textList(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:AssociatedDocumentLineDocument/ram:LineID")
        assertEquals(2, lineIds.size, "exactly one BG-25 line per position (service + trip), never one per expense")
        assertFalse(EInvoiceXml.exists(xml, "//ram:IncludedNote"), "grouped must carry no BT-127 note")

        val text = pdfText(exported.pdfBytes)
        // The title sits in the item table's narrow Bezeichnung column and may wrap; the text
        // stripper then interleaves the neighbouring cells between the two words.
        assertTrue(
            text.contains("Dienstreise") && text.contains("Nürnberg"),
            "trip title missing from the printed page",
        )
        assertFalse(subRowNumber(positionId).containsMatchIn(text), "grouped rendering must never show a detailed sub-row")
    }

    // ─── TE-2 — detailed, BT-127 note + XLSX BG-24 attachment ────────────────────────────────

    @Test
    fun `TE-2 detailed - BT-127 note with the parts, PDF sub-rows net only, KoSIT VALID, XLSX BG-24 attachment present and parseable`() {
        val (invoiceId, positionId) = sentTripInvoice(
            "TE2",
            TripCommand(
                title = "TE2 Dienstreise Nürnberg", detailedOnInvoice = true,
                expenses = listOf(
                    TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel XYZ", quantity = BigDecimal("2"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("79.44")),
                    TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi Berlin", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("23.36")),
                ),
            ),
        )
        val plainDto = mapToDto(invoiceId)
        assertNotNull(plainDto.items.single { it.category == de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP }.trip?.workbookRef, "the producer must have SETEX'd a workbook ref into Valkey")

        val resolvedDto = resolveTripWorkbookRefs(plainDto)
        val tripDto = resolvedDto.items.single { it.category == de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP }.trip!!
        assertNotNull(tripDto.resolvedWorkbookBase64, "JobConsumer.resolveTripWorkbookRefs must resolve the real Valkey-stored workbook")

        val exported = export(resolvedDto, "TE2-job")
        assertTrue(exported.result.kositSuccess, "KoSIT must be VALID: ${exported.result.kositReport}")

        val xml = String(exported.xmlBytes, Charsets.UTF_8)
        val notes = EInvoiceXml.textList(xml, "//ram:IncludedNote/ram:Content")
        val note = notes.firstOrNull { it.contains("Hotel") }
        assertNotNull(note, "BT-127 note missing from the wire (all notes: $notes)")
        assertTrue(note!!.contains("Hotel XYZ"), "note must name the hotel expense: $note")
        assertTrue(note.contains("Taxi Berlin"), "note must name the taxi expense: $note")

        val text = pdfText(exported.pdfBytes)
        assertTrue(subRowNumber(positionId).containsMatchIn(text), "first detailed sub-row missing from the printed page")

        val mimeCode = EInvoiceXml.text(xml, "//ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:AttachmentBinaryObject/@mimeCode")
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", mimeCode, "BG-24 workbook attachment must carry the XLSX MIME type")
        val filename = EInvoiceXml.text(xml, "//ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:AttachmentBinaryObject/@filename")
        assertNotNull(filename)
        assertTrue(filename!!.endsWith(".xlsx"), "attachment file name must end in .xlsx: $filename")

        val base64 = EInvoiceXml.text(xml, "//ram:AdditionalReferencedDocument[ram:TypeCode='916']/ram:AttachmentBinaryObject")
        assertNotNull(base64, "attachment binary content missing from the wire")
        val bytes = Base64.getDecoder().decode(base64)
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(4, wb.numberOfSheets, "the trip workbook has four sheets since the dedicated per-diem sheet (bed2c7528)")
            assertEquals(listOf("Export", "Dienstreise", "Posten", "Verpflegung"), (0 until 4).map { wb.getSheetName(it) })
        }
    }

    // ─── TE-3 — 100 expenses ──────────────────────────────────────────────────────────────────

    @Test
    fun `TE-3 100 expenses - note truncated with the annex hint, PDF paginates, KoSIT VALID`() {
        val manyExpenses = (1..100).map { n ->
            TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Posten Nummer $n mit einer laengeren Beschreibung", quantity = BigDecimal("1"), unit = InvoiceItemUnit.PIECE, unitPriceNet = Money.of("1.00"))
        }
        val (invoiceId, _) = sentTripInvoice("TE3", TripCommand(title = "TE3 Dienstreise mit vielen Posten", detailedOnInvoice = true, expenses = manyExpenses))
        val dto = mapToDto(invoiceId)
        val exported = export(dto, "TE3-job")
        assertTrue(exported.result.kositSuccess, "KoSIT must be VALID: ${exported.result.kositReport}")

        val xml = String(exported.xmlBytes, Charsets.UTF_8)
        val notes = EInvoiceXml.textList(xml, "//ram:IncludedNote/ram:Content").filter { it.contains("siehe Anlage") }
        assertEquals(1, notes.size)
        assertEquals(1000, notes.single().length, "the BT-127 note must be truncated at exactly 1000 characters")
        assertTrue(notes.single().endsWith("… siehe Anlage"), "truncation suffix missing: ${notes.single().takeLast(30)}")

        val pageCount = Loader.loadPDF(exported.pdfBytes).use { it.numberOfPages }
        assertTrue(pageCount > 1, "100 expense sub-rows must paginate the printed document, got $pageCount page(s)")
    }

    // ─── TE-4 — cancellation document ───────────────────────────────────────────────────────────────

    @Test
    fun `TE-4 cancellation document - negated trip line, CANCELLATION mark, KoSIT VALID`() {
        val (invoiceId, _) = sentTripInvoice(
            "TE4",
            TripCommand(
                title = "TE4 Workshop-Pauschale", fixedNetPrice = Money.of("500.00"),
                expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00"))),
            ),
        )
        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(invoiceId, "E2E TE-4 Storno") }.id!!

        val dto = mapToDto(creditNoteId)
        assertTrue(dto.totals.netAmount.amount < BigDecimal.ZERO, "the storno document's own header total must be negative")
        val exported = export(dto, "TE4-job")
        assertTrue(exported.result.kositSuccess, "KoSIT must be VALID for the storno document (BR-DE credit-note rules): ${exported.result.kositReport}")

        val xml = String(exported.xmlBytes, Charsets.UTF_8)
        EInvoiceXml.assertAmount(xml, "//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:LineTotalAmount", dto.totals.netAmount.amount, "TE-4/BT-106")

        val marks = watermarkTexts(exported.pdfBytes)
        assertTrue(marks.isNotEmpty(), "the storno document's PDF must carry a watermark run")
        assertTrue(marks.all { it.contains("STORNO") }, "a type-code-384 storno document's PDF must carry the STORNO mark on every page, got: $marks")
    }

    // ─── TE-5 — empty trip with fixed price ──────────────────────────────────────────────────

    @Test
    fun `TE-5 empty trip with fixed price - one line, no note, no XLSX detail rows beyond totals`() {
        val (invoiceId, positionId) = sentTripInvoice(
            "TE5",
            TripCommand(title = "TE5 Workshop-Pauschale", detailedOnInvoice = true, fixedNetPrice = Money.of("300.00")),
        )
        val dto = mapToDto(invoiceId)
        val exported = export(dto, "TE5-job")
        assertTrue(exported.result.kositSuccess, "KoSIT must be VALID: ${exported.result.kositReport}")

        val xml = String(exported.xmlBytes, Charsets.UTF_8)
        val lineIds = EInvoiceXml.textList(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:AssociatedDocumentLineDocument/ram:LineID")
        assertEquals(2, lineIds.size, "still exactly one line per position (service + trip)")
        assertFalse(EInvoiceXml.exists(xml, "//ram:IncludedNote"), "README border case 11 - detailed but empty must carry no BT-127 note")

        val output = ByteArrayOutputStream()
        tripWorkbookExportPort.writeTripWorkbook(invoiceId, positionId, { key -> key }, output)
        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { wb ->
            val posten = wb.getSheet("Posten")
            assertEquals(2, posten.physicalNumberOfRows, "Posten must carry only the header and the total row, no detail rows")
        }
    }

    /**
     * The printed number of a trip's first detailed sub-row (`002.1` for position 2, see
     * [PositionNumberFormatter]) as a whole token, so a date such as `12.10.2026` never matches.
     */
    private fun subRowNumber(positionId: String): Regex =
        Regex("""(?<![\d.])${Regex.escape(PositionNumberFormatter.format(positionId, 1))}(?![\d.])""")
}
