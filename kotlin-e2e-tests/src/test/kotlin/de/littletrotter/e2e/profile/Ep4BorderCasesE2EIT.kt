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
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The profile system's border-case catalog, the
 * XML/KoSIT-dependent half: the only two cases that genuinely need a real e-invoice
 * generation round-trip (both engines) rather than a Postgres-only IT. The pure/mocked cases live
 * in kotlin-invoice's `Ep4BorderCasesTest`, the Postgres-only IT cases in `Ep4BorderCasesIT`.
 *
 * - **Value edge content / XML-hostile characters**: `&`, `<`, `"`, `]]>` in a
 *   captured FREE value must reach the wire correctly escaped, never truncated or corrupted, with
 *   KoSIT staying green.
 * - **Same BT code on SELLER and RECIPIENT placement simultaneously**, proven on
 *   the actual wire this time (the config-level half — no cross-profile rejection exists — is
 *   proven in kotlin-invoice's `Ep4BorderCasesTest`): `InvoiceDtoMapper` did not read
 *   `SENDER_PROFILE`-owner values at all until fixed to append them too.
 *
 * Reuses [EInvoiceXml] (the shared XPath helper), [EmbeddedKositValidator] and [CiiSchematronValidator]
 * exactly as [NewFieldEndToEndE2EIT]/[Ep6FieldCombinationMappedE2EIT] already establish it —
 * duplicated locally per this package's own fixture-per-file convention.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@TestProfile(Ep4ComplianceBlockStageProfile::class)
class Ep4BorderCasesE2EIT {

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

    // ─── Shared scaffolding — same idiom as NewFieldEndToEndE2EIT / Ep6FieldCombinationMappedE2EIT ──

    private data class BaseFixture(val senderId: Long, val recipientId: Long, val taxRateId: Long, val invoiceId: Long)

    private fun buildInvoiceWithProfile(tag: String, profile: B2bProfile): BaseFixture {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "EP-4 Sender $tag GmbH"
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
                name = "EP-4 Empfänger $tag AG"
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
        // CORE.SERVICE_PERIOD_XOR (BLOCK stage in this suite's Ep4ComplianceBlockStageProfile) is
        // not what this suite's border cases exercise, so satisfy it cheaply with BT-72.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 1, 15) } }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "EP-4 Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        return BaseFixture(senderId, recipientId, taxRateId, invoiceId)
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

    // ─── Spec case 5 — value edge content: XML-hostile characters must be escaped, never truncated ─

    /** `&`, `<`, `"`, and a raw `]]>` (the CDATA-close sequence) — the four classic
     *  XML-serialization traps. [B2bTerm.BT_56] (HEADER/TEXT/FREE, maxLength 100) is comfortably
     *  long enough to carry all of them at once without hitting the length guard. */
    private val hostileValue = "Müller & Söhne <GmbH> \"Straße\" ]]> Ende"

    private fun xmlHostileProfile(tag: String): B2bProfile {
        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "EP-4 XML-hostile value" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = profile; term = B2bTerm.BT_56; businessDefinition = "Kontaktperson"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.INVOICE },
        )
        return profile
    }

    @Test
    fun `EP-4 value edge content - Mustang escapes XML-hostile characters correctly, KoSIT stays green, the value round-trips verbatim`() {
        val tag = "EP4XMLM"
        val fixture = buildInvoiceWithProfile(tag, xmlHostileProfile(tag))
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, fixture.invoiceId, mapOf("BT-56" to hostileValue))

        val xml = mustangXml(finalizeAndMapToDto(fixture.invoiceId))

        // A successful DOM parse inside EInvoiceXml.text() is itself proof the serializer escaped
        // & < " correctly (an unescaped one would break well-formedness and the parse would throw)
        // — the round-trip equality proves nothing was silently truncated or corrupted on top.
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeAgreement/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName",
            hostileValue, "EP-4/Mustang/BT-56 (XML-hostile value)",
        )
        assertKositValid(xml, "EP-4/Mustang (XML-hostile value)")
    }

    // ─── Spec case 4 — same BT code SELLER vs RECIPIENT simultaneously, on the actual wire ────────

    /**
     * Two independent, simultaneously active profiles place the SAME term (BT-56) at opposite
     * placements: profile [sellerProfile] places it SELLER (captured under
     * [B2bFieldOwnerType.SENDER_PROFILE]) and, separately, THIS invoice's own profile places it
     * RECIPIENT (resolved through [B2bFieldValueService.resolvedRecipientFieldValues]). Nothing in
     * either config path rejects this — see kotlin-invoice's `Ep4BorderCasesTest
     * .SameBtCodeAcrossPlacements` for the config-level proof — so the only question left is what
     * happens on the wire:
     * [InvoiceDtoMapper] appends every `SENDER_PROFILE`-owner value of this invoice's own sender to
     * `b2bValues` (previously never read under any code path), so the SELLER-side capture of BT-56
     * now reaches the DTO too — but strictly *after* the RECIPIENT-resolved entry in the list, so
     * `MustangExportModelMapper.b2bValue()`'s own "first entry wins" contract still lets the
     * RECIPIENT side win the one physical wire position both compete for; the SELLER side reaching
     * the DTO at all is the fix, not which one wins a same-term collision.
     */
    @Test
    fun `EP-4 same BT code SELLER vs RECIPIENT - finding EP6-F6 fixed - the SELLER value now reaches the DTO, the RECIPIENT value still wins the one wire position`() {
        val tag = "EP4DUALM"
        val sellerProfile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Seller"; description = "EP-4 SELLER-side BT-56" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = sellerProfile; term = B2bTerm.BT_56; businessDefinition = "Anbieter-Kontakt"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.SELLER },
        )
        val recipientProfile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Recipient"; description = "EP-4 RECIPIENT-side BT-56" })
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit { this.profile = recipientProfile; term = B2bTerm.BT_56; businessDefinition = "Käufer-Kontakt"; requirement = B2bFieldRequirement.OPTIONAL; placement = B2bPlacement.RECIPIENT },
        )

        val fixture = buildInvoiceWithProfile(tag, recipientProfile) // the invoice's own profile is the RECIPIENT one
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, fixture.senderId, mapOf("BT-56" to "Vertrieb, SELLER-Wert"))
        b2bFieldValueService.saveRecipientFieldValues(fixture.recipientId, recipientProfile.id!!, mapOf("BT-56" to "Einkauf, RECIPIENT-Wert"))

        // Both values are independently captured and readable back — no collision at storage
        // level (keyed by ownerType+ownerId+term, not just term).
        assertEquals("Vertrieb, SELLER-Wert", b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, fixture.senderId)["BT-56"])
        assertEquals("Einkauf, RECIPIENT-Wert", b2bFieldValueService.findRecipientFieldValues(fixture.recipientId, recipientProfile.id!!)["BT-56"])
        val dto = finalizeAndMapToDto(fixture.invoiceId)

        assertTrue(dto.b2bValues.any { it.term.officialCode == "BT-56" && it.value == "Vertrieb, SELLER-Wert" }, "EP6-F6 fixed: the SELLER-captured value must now reach the DTO")
        assertTrue(dto.b2bValues.any { it.term.officialCode == "BT-56" && it.value == "Einkauf, RECIPIENT-Wert" }, "the RECIPIENT-resolved value must still reach the DTO too")

        val xml = mustangXml(dto)
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeAgreement/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName",
            "Einkauf, RECIPIENT-Wert", "EP-4/Mustang/BT-56 (RECIPIENT side still wins the one wire position)",
        )
        assertKositValid(xml, "EP-4/Mustang (dual-placement BT-56)")
    }
}

/** Same `littletrotter.compliance-rules.stage=BLOCK` override `B2bProfileE2EIT`'s own
 *  `ComplianceBlockStageProfile` needs, redeclared per that class's own KDoc reasoning (no
 *  test-jar dependency across modules, and a `QuarkusTestProfile` must be a standalone top-level
 *  class) — named distinctly (`Ep4...`) only to avoid a same-package class-name collision with
 *  `B2bProfileE2EIT`'s own `ComplianceBlockStageProfile`. */
class Ep4ComplianceBlockStageProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("littletrotter.compliance-rules.stage" to "BLOCK")
}
