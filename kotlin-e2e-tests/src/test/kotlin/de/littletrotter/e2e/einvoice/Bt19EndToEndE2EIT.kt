package de.littletrotter.e2e.einvoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.application.reference.B2bProfileUsageService
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.profile.ComplianceBlockStageProfile
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.xpdf.impl.Bt19HeaderInjector
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.validation.CiiSchematronValidator
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
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
 * PO 2026-08-30 — produktive Engine-Strategie: Mustang-XML + eigene Einbettung; BT-19
 * Ende-zu-Ende inkl. eingebettetem XML.
 *
 * End-to-end acceptance pin for the [Bt19HeaderInjector] fix:
 * a captured BT-19 value must reach the wire not merely in the standalone generated XML, but also
 * in the copy actually embedded inside the hybrid PDF/A-3 document the productive Mustang route
 * hands to the user ("Mustang-XML + eigene Einbettung", the PO's own framing of the decided engine
 * strategy) — extracted here via PDFBox
 * (`documentCatalog.names.embeddedFiles`), the same detection idiom
 * [de.littletrotter.xpdf.impl.PdfExportServiceImplTest]/[EInvoiceGenerationE2EIT] already use for
 * the hybrid-PDF invariant, extended here to actually read the embedded bytes back out and
 * assert their content, not merely their presence.
 *
 * The standalone XML is additionally checked against the CII Schematron (EN16931/EXTENDED) next
 * to KoSIT, so a BT-19 element in the wrong place fails on two independent rule sets.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
class Bt19EndToEndE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var b2bProfileLifecycleService: B2bProfileLifecycleService

    @Inject
    lateinit var b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    @Inject
    lateinit var b2bProfileUsageService: B2bProfileUsageService

    companion object {
        private lateinit var kosit: EmbeddedKositValidator
        private lateinit var schematron: CiiSchematronValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
            schematron = CiiSchematronValidator().apply { init() }
        }
    }

    private val mustang = XmlExportServiceImpl(null)

    private fun mustangXml(dto: InvoiceDto): String = String(mustang.generate(dto), Charsets.UTF_8)

    private fun assertKositValid(xml: String, label: String) {
        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "$label: KoSIT must be VALID, was ${result.status} - ${result.details}")
        val schematronResult = schematron.validate(xml.toByteArray(Charsets.UTF_8))
        assertEquals(KositOutcome.VALID, schematronResult.outcome, "$label: CII Schematron must be VALID, was ${schematronResult.outcome} - ${schematronResult.findings}")
    }

    /** Mustang pretty-prints its XML; a literal substring count across header (compact,
     *  freshly injected) and line (Mustang's own indentation) needs the same whitespace-free
     *  form on both sides — same helper idiom as [de.littletrotter.xpdf.impl.mustang
     *  .En16931TermsExportTest]. */
    private fun compact(xml: String): String = Regex(">\\s+<").replace(xml, "><")

    // ─── Fixture — one invoice, a profile with an OPTIONAL INVOICE-placed BT-19 field ────────────

    private data class Fixture(val invoiceId: Long)

    private fun buildInvoice(tag: String, profile: B2bProfile): Fixture {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "BT19 Sender $tag GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
                phone = "+49 30 1234567"
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "BT19 Empfänger $tag AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                b2bProfile = profile
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

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        // D-R1 (CORE.SERVICE_PERIOD_XOR, BLOCK stage in this suite's ComplianceBlockStageProfile):
        // not what this BT-19 suite exercises, so satisfy it cheaply with BT-72.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 1, 15) } }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "BT-19 E2E Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        return Fixture(invoiceId)
    }

    private fun finalizeAndMapToDto(invoiceId: Long): InvoiceDto {
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return QuarkusTransaction.requiringNew().call<InvoiceDto> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "the fixture must actually finalize for this suite to mean anything")
            InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)
        }
    }

    // ─── Hybrid PDF/A-3 export (Mustang route) + embedded-XML extraction ────────────

    private fun minimalRenderedInvoicePage(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("BT-19 E2E base invoice page")
                cs.endText()
            }
            val os = ByteArrayOutputStream()
            doc.save(os)
            return os.toByteArray()
        }
    }

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    /** Extracts the embedded `factur-x.xml`/`xrechnung.xml` attachment's own bytes from a hybrid
     *  PDF/A-3 document — the same detection [de.littletrotter.xpdf.impl.PdfExportServiceImplTest]
     *  already uses for presence, extended here to read the attachment's content back out. `null`
     *  when no such attachment exists. */
    private fun extractEmbeddedXml(pdfBytes: ByteArray): String? = try {
        Loader.loadPDF(pdfBytes).use { doc ->
            val names = doc.documentCatalog.names?.embeddedFiles?.names ?: emptyMap()
            val spec = names["factur-x.xml"] ?: names["xrechnung.xml"]
            spec?.embeddedFile?.toByteArray()?.let { String(it, Charsets.UTF_8) }
        }
    } catch (ex: Exception) {
        null
    }

    /** Drives the real [PdfExportServiceImpl.performExport] (Mustang route) with a stubbed render
     *  step only — the visible layout is never checked here, only the embedded-XML attachment
     *  [org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3] actually builds around the enriched XML
     *  [XmlExportServiceImpl.generate] already returned (Bt19HeaderInjector runs inside that call,
     *  so the embedded copy carries the same header injection as the standalone XML by
     *  construction, not by a second injection pass). */
    private fun exportHybridPdf(dto: InvoiceDto): ByteArray {
        val renderer: BasePdfRendererPort = mock()
        whenever(renderer.render(any())).thenReturn(minimalRenderedInvoicePage())
        val store = InMemoryDocumentStore()

        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = mustang,
            reportRenderer = renderer,
            documentStore = store,
            kositValidator = object : de.littletrotter.shared.KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = kosit.validate(xml, timeoutMs)
            },
        )
        service.performExport(dto, "e2e-bt19-${dto.invoiceNumber.value}")
        return store.objects.entries.single { it.key.endsWith(".pdf") }.value
    }

    // ─── Positive case — BT-19 captured, asserted on every wire/embedding surface ────────────────

    @Test
    fun `BT-19 captured - Mustang route - header element present in standalone XML, embedded PDF copy, and every line as BT-133, KoSIT VALID`() {
        val tag = "BT19MU"
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "BT-19 E2E" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val fixture = buildInvoice(tag, profile)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, fixture.invoiceId, mapOf("BT-19" to "KST-4711"))

        val dto = finalizeAndMapToDto(fixture.invoiceId)

        // (a) standalone XML — header element present with the captured value.
        val xml = mustangXml(dto)
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID",
            "KST-4711", "$tag/Mustang standalone XML/BT-19 header",
        )
        // (d) Mustang route additionally: BT-133 line fanout still present on every line.
        EInvoiceXml.assertText(
            xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedLineTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID",
            "KST-4711", "$tag/Mustang standalone XML/BT-133 line",
        )
        // (c) KoSIT VALID.
        assertKositValid(xml, "$tag/Mustang standalone XML")

        // (b) the SAME element+value inside the XML embedded in hybrid PDF/A-3.
        val embeddedXml = extractEmbeddedXml(exportHybridPdf(dto))
        assertTrue(embeddedXml != null, "$tag/Mustang: hybrid PDF must carry an embedded factur-x.xml/xrechnung.xml attachment")
        EInvoiceXml.assertText(
            embeddedXml!!, "//ram:ApplicableHeaderTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID",
            "KST-4711", "$tag/Mustang embedded XML/BT-19 header",
        )
    }

    // ─── Negative case — no BT-19 captured: absent everywhere, never an empty element ────────────

    @Test
    fun `no BT-19 captured - header element absent from standalone XML and from the embedded PDF copy - never an empty element`() {
        val tag = "BT19NO"
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "BT-19 E2E, uncaptured" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val fixture = buildInvoice(tag, profile)
        // No saveFieldValues call at all - BT-19 stays uncaptured.

        val dto = finalizeAndMapToDto(fixture.invoiceId)

        val mustangXmlResult = mustangXml(dto)
        EInvoiceXml.assertAbsent(mustangXmlResult, "//ram:ApplicableHeaderTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount", "$tag/Mustang standalone XML/BT-19 header (uncaptured)")
        EInvoiceXml.assertAbsent(mustangXmlResult, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedLineTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount", "$tag/Mustang standalone XML/BT-133 line (uncaptured)")
        assertKositValid(mustangXmlResult, "$tag/Mustang standalone XML (uncaptured)")

        val embeddedXml = extractEmbeddedXml(exportHybridPdf(dto))
        assertTrue(embeddedXml != null, "$tag/Mustang: hybrid PDF must still carry the embedded attachment even without BT-19")
        EInvoiceXml.assertAbsent(embeddedXml!!, "//ram:ApplicableHeaderTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount", "$tag/Mustang embedded XML/BT-19 header (uncaptured)")
    }

    // ─── Unit-level pins for Bt19HeaderInjector itself (idempotence, empty-value no-op) ───────────

    @Test
    fun `Bt19HeaderInjector is idempotent - injecting twice never produces two header elements`() {
        val tag = "BT19IDEM"
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "BT-19 idempotence" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        val fixture = buildInvoice(tag, profile)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, fixture.invoiceId, mapOf("BT-19" to "KST-9999"))
        val dto = finalizeAndMapToDto(fixture.invoiceId)

        val onceInjected = mustangXml(dto) // generate() already ran the injector once internally
        val twiceInjected = Bt19HeaderInjector.inject(onceInjected, dto)

        // Exactly one header occurrence and one line (BT-133) occurrence of the literal fragment
        // both before and after the second pass — re-running the injector must add nothing.
        val fragment = "<ram:ReceivableSpecifiedTradeAccountingAccount><ram:ID>KST-9999</ram:ID></ram:ReceivableSpecifiedTradeAccountingAccount>"
        assertEquals(2, Regex(Regex.escape(fragment)).findAll(compact(onceInjected)).count(), "sanity: one header + one line occurrence before the second pass")
        assertEquals(onceInjected, twiceInjected, "a second injector pass over already-injected XML must be a pure no-op")
    }

    @Test
    fun `Bt19HeaderInjector is a no-op when the invoice has no resolvable BT-19 or costCenter value`() {
        val dto = InvoiceDto(items = emptyList())
        val xml = "<ram:ApplicableHeaderTradeSettlement><ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode></ram:ApplicableHeaderTradeSettlement>"

        val result = Bt19HeaderInjector.inject(xml, dto)

        assertEquals(xml, result)
        assertFalse(result.contains("ReceivableSpecifiedTradeAccountingAccount"), "no value to inject means no element at all - never an empty one")
    }
}
