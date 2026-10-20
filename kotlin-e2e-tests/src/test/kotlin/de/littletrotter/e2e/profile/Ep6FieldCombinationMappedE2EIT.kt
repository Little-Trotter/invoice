package de.littletrotter.e2e.profile

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
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.validation.CiiSchematronValidator
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The "Mapped" third of the field-combination matrix: finalize+generate -> value at the
 * correct EN-16931/XPath position, for every axis cell [NewFieldEndToEndE2EIT] does not
 * already cover. That suite proved the whole chain once, for BT-12/BT-18 (HEADER, FREE, INVOICE
 * placement) — this suite adds the placements/levels/kind-shaped gaps that chain never touched:
 * - **Invoice 1** — the remaining FREE HEADER terms (BT-19/56/58), including BT-58 placed
 *   [B2bPlacement.RECIPIENT] (its value resolved via the invoice > recipient > default chain,
 *   never captured on the invoice header itself) and BT-19, which [B2bTerm.BT_19]'s own KDoc
 *   already documents Mustang 2.25.0 as not yet writing — confirmed empirically here rather
 *   than assumed.
 * - **Invoice 2 / 3** — [de.littletrotter.shared.b2b.B2bTermLevel.LINE] FREE terms (BT-155/156):
 *   present at their line-item
 *   XPath position when captured (invoice 2), absent when the profile defines them but the item
 *   never captures a value (invoice 3) — same profile, batched to two invoices only because the
 *   "absent" proof needs an otherwise-identical item with nothing captured.
 * - **Invoice 4** — a [B2bPlacement.SELLER] capture of a HEADER-level FREE term (legal per
 *   [de.littletrotter.domain.reference.B2bProfileField.assertPlacementCompatibleWithLevel]): the
 *   value is captured (`b2b_field_value`, owner [B2bFieldOwnerType.SENDER_PROFILE]) and
 *   [InvoiceDtoMapper] now reads that owner type too (appended to `b2bValues`, keyed by
 *   this invoice's own sender) — previously a captured value that silently never reached the
 *   e-invoice. Mustang-only: the mapping lives in the DTO layer shared by all export paths, not
 *   in the XML generation itself, so repeating the proof for further routes would not add coverage.
 *
 * Reuses [EInvoiceXml] (the shared XPath helper), [EmbeddedKositValidator] and [CiiSchematronValidator]
 * exactly as [NewFieldEndToEndE2EIT] and [de.littletrotter.e2e.einvoice
 * .EInvoiceGenerationE2EIT] already establish it — duplicated locally per this package's own
 * fixture-per-file convention (see those classes' KDoc).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
class Ep6FieldCombinationMappedE2EIT {

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

    // ─── Shared scaffolding — sender/recipient/tax rate/invoice, same idiom as NewFieldEndToEndE2EIT ─

    private data class BaseFixture(val senderId: Long, val recipientId: Long, val taxRateId: Long, val invoiceId: Long)

    private fun buildInvoiceWithProfile(tag: String, profile: B2bProfile): BaseFixture {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "EP-6 Sender $tag GmbH"
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
                name = "EP-6 Empfänger $tag AG"
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
        // not what this suite exercises, so satisfy it cheaply with BT-72.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 1, 15) } }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "EP-6 Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        return BaseFixture(senderId, recipientId, taxRateId, invoiceId)
    }

    private fun itemIdOf(invoiceId: Long): Long =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.itemList.single().id!! }

    private fun finalizeAndMapToDto(invoiceId: Long): InvoiceDto {
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return QuarkusTransaction.requiringNew().call<InvoiceDto> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "the fixture must actually finalize for this suite to mean anything")
            InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)
        }
    }

    // ─── Invoice 1 — remaining FREE HEADER terms + RECIPIENT-placed BT-58 + BT-19's Mustang gap ───

    private fun buildInvoice1(tag: String): BaseFixture {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "EP-6 header FREE terms" })
        val profileId = profile.id!!
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_12; businessDefinition = "Vertragsreferenz"; requirement = B2bFieldRequirement.MANDATORY; placement = B2bPlacement.INVOICE; sortOrder = 1 })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_18; businessDefinition = "Belegobjekt"; requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_12; placement = B2bPlacement.INVOICE; sortOrder = 2 })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_56; businessDefinition = "Käufer-Kontaktperson"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE; sortOrder = 3 })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_58; businessDefinition = "Käufer-Kontakt-E-Mail"; requirement = B2bFieldRequirement.MANDATORY; placement = B2bPlacement.RECIPIENT; sortOrder = 4 })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE; sortOrder = 5 })

        val fixture = buildInvoiceWithProfile(tag, profile)
        b2bFieldValueService.saveFieldValues(
            B2bFieldOwnerType.INVOICE, fixture.invoiceId,
            mapOf("BT-12" to "VERTRAG-$tag-1", "BT-18" to "OBJ-$tag-1", "BT-56" to "Einkauf, Frau Meier", "BT-19" to "KST-$tag-1"),
        )
        // BT-58 stored at the RECIPIENT level, never on the invoice header itself — the
        // recipient-default resolution chain (invoice > recipient > default) is what must carry it into the DTO.
        b2bFieldValueService.saveRecipientFieldValues(fixture.recipientId, profileId, mapOf("BT-58" to "kunde-$tag@example.com"))
        return fixture
    }

    private fun assertInvoice1Mapping(xml: String, label: String, tag: String) {
        EInvoiceXml.assertText(xml, "//ram:ApplicableHeaderTradeAgreement/ram:ContractReferencedDocument/ram:IssuerAssignedID", "VERTRAG-$tag-1", "$label/BT-12")
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='130']/ram:IssuerAssignedID", "OBJ-$tag-1", "$label/BT-18",
        )
        EInvoiceXml.assertText(xml, "//ram:ApplicableHeaderTradeAgreement/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName", "Einkauf, Frau Meier", "$label/BT-56")
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeAgreement/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:EmailURIUniversalCommunication/ram:URIID",
            "kunde-$tag@example.com", "$label/BT-58 (RECIPIENT placement, resolved via the recipient value)",
        )
        // Finding EP6-F4b (fixed) — BT-19 IS captured (see the DTO assertion in the test body
        // below) and now also reaches the wire at the header level: Mustang itself still has no
        // header setter (unchanged, B2bTerm.BT_19's own KDoc / MustangExportModel's class KDoc),
        // but the route-agnostic Bt19HeaderInjector post-processing seam injects it after
        // generation.
        EInvoiceXml.assertText(xml, "//ram:ApplicableHeaderTradeSettlement/ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID", "KST-$tag-1", "$label/BT-19 (EP6-F4b fixed via Bt19HeaderInjector)")
    }

    @Test
    fun `EP-6 Mapped - Mustang - HEADER FREE terms BT-12,18,56 and RECIPIENT-placed BT-58 map, BT-19 now maps at the header too (EP6-F4b fixed)`() {
        val tag = "EP6H1M"
        val fixture = buildInvoice1(tag)
        val dto = finalizeAndMapToDto(fixture.invoiceId)
        assertEquals(true, dto.b2bValues.any { it.term.officialCode == "BT-19" }, "BT-19 must reach the DTO - the gap was Mustang's mapping, not the capture")

        val xml = mustangXml(dto)
        assertInvoice1Mapping(xml, "EP-6/Mustang", tag)
        assertKositValid(xml, "EP-6/Mustang")
    }

    // ─── Invoice 2/3 — LINE FREE terms present-when-captured / absent-when-not ─────────────────────

    private fun lineProfile(tag: String): B2bProfile {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "EP-6 LINE FREE terms" })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_155; businessDefinition = "Artikelnummer Verkäufer"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE; sortOrder = 1 })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_156; businessDefinition = "Artikelnummer Käufer"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE; sortOrder = 2 })
        return profile
    }

    @Test
    fun `EP-6 Mapped - Mustang - LINE FREE terms BT-155,156 map at the item position when captured`() {
        val tag = "EP6L2M"
        val profile = lineProfile(tag)
        val fixture = buildInvoiceWithProfile(tag, profile)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, itemIdOf(fixture.invoiceId), mapOf("BT-155" to "SELLER-$tag", "BT-156" to "BUYER-$tag"))

        val xml = mustangXml(finalizeAndMapToDto(fixture.invoiceId))
        EInvoiceXml.assertText(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedTradeProduct/ram:SellerAssignedID", "SELLER-$tag", "EP-6/Mustang/BT-155")
        EInvoiceXml.assertText(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedTradeProduct/ram:BuyerAssignedID", "BUYER-$tag", "EP-6/Mustang/BT-156")
        assertKositValid(xml, "EP-6/Mustang (LINE captured)")
    }

    @Test
    fun `EP-6 Mapped - Mustang - LINE FREE terms BT-155,156 are absent when the profile defines them but the item never captures a value`() {
        val tag = "EP6L3M"
        val profile = lineProfile(tag)
        val fixture = buildInvoiceWithProfile(tag, profile) // no saveFieldValues call at all for this item

        val xml = mustangXml(finalizeAndMapToDto(fixture.invoiceId))
        EInvoiceXml.assertAbsent(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedTradeProduct/ram:SellerAssignedID", "EP-6/Mustang/BT-155 (never captured)")
        EInvoiceXml.assertAbsent(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedTradeProduct/ram:BuyerAssignedID", "EP-6/Mustang/BT-156 (never captured)")
        assertKositValid(xml, "EP-6/Mustang (LINE not captured)")
    }

    // ─── Invoice 4 — SELLER-placed HEADER FREE term: captured, but never reaches the wire ─────────

    @Test
    fun `EP-6 Mapped - finding EP6-F6 (fixed) - a SELLER-placed HEADER FREE term is captured and InvoiceDtoMapper now reads SENDER_PROFILE-owner values`() {
        val tag = "EP6SEL"
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "EP-6 SELLER-placed HEADER FREE term" })
        b2bProfileFieldDefinitionService.saveField(B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_56; businessDefinition = "Kontaktstelle beim Anbieter"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.SELLER; sortOrder = 1 })
        val fixture = buildInvoiceWithProfile(tag, profile)
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, fixture.senderId, mapOf("BT-56" to "Vertrieb, Herr Schulz"))

        // The value IS captured and readable back...
        assertEquals("Vertrieb, Herr Schulz", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, fixture.senderId)["BT-56"])

        // ...and now reaches the DTO and the wire too: InvoiceDtoMapper's
        // b2bValues assembly reads B2bFieldOwnerType.SENDER_PROFILE (keyed by this invoice's own
        // sender) in addition to INVOICE/INVOICE_ITEM.
        val dto = finalizeAndMapToDto(fixture.invoiceId)
        assertEquals(true, dto.b2bValues.any { it.term.officialCode == "BT-56" && it.value == "Vertrieb, Herr Schulz" }, "the SELLER-captured value must now reach the header DTO")

        val xml = mustangXml(dto)
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeAgreement/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName",
            "Vertrieb, Herr Schulz", "EP-6/Mustang/BT-56 (SELLER placement, now mapped - EP6-F6 fixed)",
        )
        assertKositValid(xml, "EP-6/Mustang (SELLER-placed BT-56, EP6-F6 fixed)")
    }
}
