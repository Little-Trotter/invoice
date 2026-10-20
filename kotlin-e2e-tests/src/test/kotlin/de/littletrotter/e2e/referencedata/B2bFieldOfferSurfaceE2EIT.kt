package de.littletrotter.e2e.referencedata

import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceHeaderUpdateService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.anArticleRecipientRef
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The B2B profile's field OFFER differs per (level x placement) surface: header vs. item vs.
 * recipient vs. seller. Facade-level mirror of
 * [de.littletrotter.application.reference.Ep6FieldCombinationMatrixTest]'s Vaadin/mocked-repo
 * matrix, exercised here through the real, Postgres-backed [B2bFieldDefinitionQueryPort] and
 * [B2bProfileRuleSet] instead — no Vaadin.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class B2bFieldOfferSurfaceE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    @Inject
    lateinit var articleQueries: ArticleQueryPort

    @Inject
    lateinit var b2bProfileQueries: B2bProfileQueryPort

    @Inject
    lateinit var manageB2bProfile: ManageB2bProfileUseCase

    @Inject
    lateinit var b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort

    @Inject
    lateinit var manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase

    @Inject
    lateinit var b2bFieldValueQueries: B2bFieldValueQueryPort

    @Inject
    lateinit var manageB2bFieldValue: ManageB2bFieldValueUseCase

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    @Inject
    lateinit var b2bProfileRuleSet: B2bProfileRuleSet

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var headerUpdateService: InvoiceHeaderUpdateService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var articleRepository: ArticleRepository

    private val mustang = XmlExportServiceImpl(null)

    private val createdInvoiceIds = mutableListOf<Long>()
    private val createdRecipientIds = mutableListOf<Long>()
    private val createdSenderIds = mutableListOf<Long>()
    private val createdArticleIds = mutableListOf<Long>()
    private val createdProfileIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            createdArticleIds.forEach { em.find(Article::class.java, it)?.let(em::remove) }
            createdRecipientIds.forEach { em.find(Recipient::class.java, it)?.let(em::remove) }
            createdSenderIds.forEach { em.find(SenderProfile::class.java, it)?.let(em::remove) }
            createdProfileIds.forEach { em.find(B2bProfile::class.java, it)?.let(em::remove) }
        }
    }

    private fun sender(tag: String): SenderProfile {
        val s = aSenderProfile {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        s.addAddress(Address.forSender(s, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        return s
    }

    private fun recipient(tag: String): Recipient {
        val r = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        r.addAddress(Address.forRecipient(r, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        return r
    }

    private fun field(profile: B2bProfileDto, term: B2bTerm, requirement: B2bFieldRequirement, placement: B2bPlacement, conditionTerm: B2bTerm? = null): B2bProfileFieldDto =
        manageB2bFieldDefinition.saveB2bProfileField(
            B2bProfileFieldDto(
                profileId = profile.id,
                term = term,
                businessDefinition = "$term ($placement/$requirement)",
                requirement = requirement,
                placement = placement,
                conditionTerm = conditionTerm,
            ),
        )

    /** One field per (level x placement) legal surface:
     *  BT-12 (HEADER/INVOICE, OPTIONAL), BT-155 (LINE, forced INVOICE, MANDATORY), BT-18
     *  (HEADER/RECIPIENT, CONDITIONAL on BT-12), BT-56 (HEADER/SELLER, OPTIONAL). */
    private fun buildFourSurfaceProfile(tag: String): B2bProfileDto {
        val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
        field(profile, B2bTerm.BT_12, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE)
        field(profile, B2bTerm.BT_155, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE)
        field(profile, B2bTerm.BT_18, B2bFieldRequirement.CONDITIONAL, B2bPlacement.RECIPIENT, conditionTerm = B2bTerm.BT_12)
        field(profile, B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL, B2bPlacement.SELLER)
        return profile
    }

    private fun finalizeToSent(invoiceId: Long) {
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
    }

    @Test
    fun `happy - the profile offers exactly one field per surface header vs item vs recipient vs seller`() {
        val tag = "EXB1"
        val profileId = QuarkusTransaction.requiringNew().call { buildFourSurfaceProfile(tag).id!! }
        createdProfileIds += profileId

        assertEquals(listOf(B2bTerm.BT_12), b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(profileId).map { it.term }, "header scope: exactly the HEADER-level INVOICE-placed FREE field")
        assertEquals(listOf(B2bTerm.BT_155), b2bFieldDefinitionQueries.findB2bInvoiceItemFields(profileId).map { it.term }, "item scope: exactly the LINE-level FREE field")
        assertEquals(listOf(B2bTerm.BT_18), b2bFieldDefinitionQueries.findB2bRecipientFields(profileId).map { it.term }, "recipient scope: exactly the RECIPIENT-placed FREE HEADER field")
        assertEquals(listOf(B2bTerm.BT_56), b2bFieldDefinitionQueries.findB2bSellerFields(profileId).map { it.term }, "seller scope: exactly the SELLER-placed field")
    }

    /**
     * Header/recipient/seller/per-item values land at the right BT positions in export.
     *
     * **Reality-vs-spec pin:** "recipient/seller party values on the party blocks" does not hold
     * uniformly. BT-18 (RECIPIENT-*placed*, i.e. captured as the customer's standing default) is
     * still a plain HEADER term on the wire — `ram:ApplicableHeaderTradeAgreement
     * /ram:AdditionalReferencedDocument`, not a party block at all; only "where the value is
     * captured/defaulted from" is RECIPIENT-specific, not "where it lands in XML". BT-56
     * (SELLER-*placed*, captured on the Anbieter form under `B2bFieldOwnerType.SENDER_PROFILE`)
     * DOES land on a party block — but the *buyer's* (`BuyerTradeParty/DefinedTradeContact`), per
     * BG-8: a contact person at the customer the seller knows to address, not the seller's own
     * contact. Confirmed against [de.littletrotter.xpdf.impl.mustang.En16931TermsExportTest]'s own
     * "BT-56 and BT-58 reach the DefinedTradeContact of the buyer" test. Both are asserted below
     * exactly as they behave, not as "SELLER placement -> seller party" would suggest.
     */
    @Test
    fun `happy - header value once, recipient-placed value, seller-captured value and per-item values export at the right BT positions`() {
        val tag = "EXB2"
        val profileId = QuarkusTransaction.requiringNew().call { buildFourSurfaceProfile(tag).id!! }
        createdProfileIds += profileId

        val (senderId, recipientId, invoiceId, item1Id, item2Id) = QuarkusTransaction.requiringNew().call {
            val s = sender(tag)
            val profile = em.find(B2bProfile::class.java, profileId)!!
            val r = recipient(tag).edit { b2bProfile = profile }
            em.persist(s); em.persist(r); em.flush()
            val inv = lifecycleService.create(s.id!!, r.id!!)
            val rate = taxRateQueries.findAllTaxRates().find { it.isDefault }!!
            itemMutationService.addItem(inv.id!!, InvoiceItemCommand(category = InvoiceItemCategory.SERVICE, description = "Item1", quantity = BigDecimal.ONE, unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("10.00"), taxRateId = rate.id))
            val withItems = itemMutationService.addItem(inv.id!!, InvoiceItemCommand(category = InvoiceItemCategory.SERVICE, description = "Item2", quantity = BigDecimal.ONE, unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("20.00"), taxRateId = rate.id))
            val items = withItems.itemList.sortedBy { it.positionId }
            listOf(s.id!!, r.id!!, inv.id!!, items[0].id!!, items[1].id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId

        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bRecipientFieldValues(recipientId, profileId, mapOf("BT-18" to "RCPT-VALUE")) }
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-12" to "HEADER-VALUE")) }
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, item1Id, mapOf("BT-155" to "ITEM-A")) }
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, item2Id, mapOf("BT-155" to "ITEM-B")) }
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.SENDER_PROFILE, senderId, mapOf("BT-56" to "Frau Meier")) }

        finalizeToSent(invoiceId)

        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(em.find(Invoice::class.java, invoiceId)!!) }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)

        EInvoiceXml.assertText(xml, "//ram:ApplicableHeaderTradeAgreement/ram:ContractReferencedDocument/ram:IssuerAssignedID", "HEADER-VALUE", "BT-12 header value, present once")
        EInvoiceXml.assertText(xml, "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument/ram:IssuerAssignedID", "RCPT-VALUE", "BT-18 recipient-placed value (still a header element, see this test's own KDoc)")
        EInvoiceXml.assertText(xml, "//ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName", "Frau Meier", "BT-56 seller-captured value lands on the BUYER party block (BG-8, see this test's own KDoc)")
        val perItemValues = EInvoiceXml.textList(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedTradeProduct/ram:SellerAssignedID")
        assertEquals(listOf("ITEM-A", "ITEM-B"), perItemValues, "EX-B2/BT-155 differs per item, in position order")
    }

    @Test
    fun `border - an empty REQUIRED header field is reported by the finalization gate and clears once filled`() {
        val tag = "EXB3"
        val (senderId, recipientId, invoiceId, profileId) = QuarkusTransaction.requiringNew().call {
            val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
            field(profile, B2bTerm.BT_12, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE)
            val s = sender(tag)
            val r = recipient(tag).edit { b2bProfile = em.find(B2bProfile::class.java, profile.id) }
            em.persist(s); em.persist(r); em.flush()
            val inv = lifecycleService.create(s.id!!, r.id!!)
            listOf(s.id!!, r.id!!, inv.id!!, profile.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId; createdProfileIds += profileId

        val beforeFindings = QuarkusTransaction.requiringNew().call { b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, invoiceId)!!) }
        assertTrue(beforeFindings.any { it.businessTerms.contains(B2bTerm.BT_12) }, "empty MANDATORY header field must be reported: $beforeFindings")

        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-12" to "V-1")) }

        val afterFindings = QuarkusTransaction.requiringNew().call { b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, invoiceId)!!) }
        assertTrue(afterFindings.none { it.businessTerms.contains(B2bTerm.BT_12) }, "filling it clears the finding: $afterFindings")
    }

    /**
     * An article's BT-155 template copies onto a new item; the per-recipient BT-156
     * buyer-number mapping resolves; a per-item override wins over the template in export.
     *
     * **Reality-vs-spec pin:** "the per-recipient buyer number (BT-156) prefills" describes a UI
     * interaction (`BaseItemDialog`/`prefillIfBlank`, both `internal` to kotlin-invoice, invisible
     * from this facade-level suite). What is asserted here is the underlying, facade-reachable
     * primitive the UI prefill is built on — [de.littletrotter.application.reference.port.inbound.ArticleQueryPort.findArticleRecipientRef]
     * resolving the mapping — not the auto-populate-a-blank-widget behavior itself, which stays
     * covered by kotlin-invoice's own Vaadin-level item-dialog tests.
     */
    @Test
    fun `border - the article LINE template copies onto a new item, the buyer-number mapping resolves, and a per-item override wins in export`() {
        val tag = "EXB4"
        val (senderId, recipientId, articleId, profileId) = QuarkusTransaction.requiringNew().call {
            val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
            field(profile, B2bTerm.BT_155, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE)
            val s = sender(tag)
            val r = recipient(tag).edit { b2bProfile = em.find(B2bProfile::class.java, profile.id) }
            em.persist(s); em.persist(r); em.flush()

            val article = articleRepository.save(anArticle { name = "$tag Beratung" })
            manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.ARTICLE, article.id!!, mapOf("BT-155" to "TEMPLATE-155"))
            em.persist(anArticleRecipientRef { this.article = article; this.recipient = r; buyerArticleNumber = "BUYER-42" })

            listOf(s.id!!, r.id!!, article.id!!, profile.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdArticleIds += articleId; createdProfileIds += profileId

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        createdInvoiceIds += invoiceId
        val rateId = taxRateQueries.findAllTaxRates().find { it.isDefault }!!.id
        val itemId = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(category = InvoiceItemCategory.SERVICE, description = "Beratung", quantity = BigDecimal.ONE, unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("10.00"), articleId = articleId, taxRateId = rateId),
            ).itemList.single().id!!
        }

        // Facade primitive the UI's BT-156 prefill is built on (see this test's own KDoc).
        val ref = articleQueries.findArticleRecipientRef(articleId, recipientId)
        assertEquals("BUYER-42", ref?.buyerArticleNumber, "NEKAR-T11 per-recipient buyer article number resolves for BT-156's UI prefill")

        // BT-155 template copy, scoped to the invoice's own profile's LINE fields.
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.copyB2bArticleTemplateToInvoiceItem(articleId, itemId, invoiceId) }
        assertEquals(mapOf("BT-155" to "TEMPLATE-155"), b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, itemId), "the article's LINE template value is copied onto the item")

        // Override wins.
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, itemId, mapOf("BT-155" to "OVERRIDE-155")) }

        finalizeToSent(invoiceId)
        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(em.find(Invoice::class.java, invoiceId)!!) }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        EInvoiceXml.assertText(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedTradeProduct/ram:SellerAssignedID", "OVERRIDE-155", "the per-item override wins over both the article template and the recipient mapping in export")
    }

    private fun headerCommand(invoiceId: Long, senderId: Long, recipientId: Long, b2bProfileId: Long?) = UpdateInvoiceHeaderCommand(
        invoiceId = invoiceId,
        expectedVersion = null,
        invoiceDate = null,
        servicePeriodFrom = null,
        servicePeriodTo = null,
        dueDate = null,
        freeText = null,
        buyerReference = null,
        orderReference = null,
        orderReferenceDate = null,
        currency = Currency.EUR,
        recipientId = recipientId,
        senderProfileId = senderId,
        paymentTermId = null,
        sellerVatRegistrationCountry = null,
        invoiceFormat = null,
        b2bProfileId = b2bProfileId,
    )

    /**
     * Switching an invoice's profile after values already exist.
     *
     * **Pin, not a wish:** [B2bProfileField]/[de.littletrotter.domain.reference.B2bFieldValue]
     * carries no `profileId` at all — a captured value is keyed by `(ownerType, ownerId, btCode)`
     * only. Switching `Invoice.b2bProfile` therefore neither deletes nor migrates any existing
     * `b2b_field_value` row: the old value for a term the NEW profile does not itself define
     * becomes invisible (no `find*Fields` surface offers it any more) but is **kept**, not
     * dropped — a stale, orphaned-but-intact row a later profile switch back would resurrect
     * unchanged. Asserted below exactly as that: kept in storage, absent from the offer.
     *
     * Also documents a second, sharper finding this same code path exposes: calling
     * `updateInvoiceHeader` for an UNCHANGED recipient WITHOUT resubmitting the invoice's current
     * `b2bProfileId` clears `Invoice.b2bProfile` to `null` outright
     * (`InvoiceLifecycleService.applyPartyChanges`: "`null` there clears an override back to no
     * profile") — every entry-pane save must always resubmit the field it isn't changing, or it
     * silently loses the invoice's profile assignment. Not exercised as its own assertion here
     * (it would only restate the same code path with `null` as the value), but worth flagging as
     * an easy caller footgun.
     */
    @Test
    fun `border - switching the invoice profile keeps the old profile's captured values, merely stops offering them`() {
        val tag = "EXB5"
        val (senderId, recipientId, oldProfileId, newProfileId) = QuarkusTransaction.requiringNew().call {
            val oldProfile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Old Profile"))
            field(oldProfile, B2bTerm.BT_12, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE)
            val newProfile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag New Profile"))
            field(newProfile, B2bTerm.BT_58, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE)
            val s = sender(tag)
            val r = recipient(tag).edit { b2bProfile = em.find(B2bProfile::class.java, oldProfile.id) }
            em.persist(s); em.persist(r); em.flush()
            listOf(s.id!!, r.id!!, oldProfile.id!!, newProfile.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId
        createdProfileIds += oldProfileId; createdProfileIds += newProfileId

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            assertEquals(oldProfileId, em.find(Invoice::class.java, invoiceId)!!.b2bProfile?.id, "fixture sanity: the standing default profile applied at creation")
        }
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-12" to "OLD-VALUE")) }

        QuarkusTransaction.requiringNew().run { headerUpdateService.updateHeader(headerCommand(invoiceId, senderId, recipientId, b2bProfileId = newProfileId)) }

        QuarkusTransaction.requiringNew().run {
            assertEquals(newProfileId, em.find(Invoice::class.java, invoiceId)!!.b2bProfile?.id, "the invoice now carries the new profile")
        }
        // Kept — not dropped: the raw value row survives, it is just no longer offered.
        assertEquals(mapOf("BT-12" to "OLD-VALUE"), b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId), "the old profile's captured value is still in storage after the switch")
        assertTrue(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(newProfileId).none { it.term == B2bTerm.BT_12 }, "the NEW profile simply never offers BT-12 as a field")
        // The old profile itself is untouched by another invoice's switch.
        assertEquals(listOf(B2bTerm.BT_12), b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(oldProfileId).map { it.term }, "profiles are independent of any one invoice's assignment")
    }

    // ─── Guided profile party values ───────────────────────────────

    /**
     * The guided step's resolution chain (invoice value > party value
     * > profile default) for a FREE SELLER-placed field, end to end through the real facade, the
     * real [B2bProfileRuleSet] and the real Mustang export: the seller's own captured value
     * (`SENDER_PROFILE` owner) is offered until an invoice-level override is saved, which then
     * wins both in [de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort.findB2bGuidedFieldValues] and on the wire — pinning that
     * [InvoiceDtoMapper]'s `b2bValues` composition really does keep "invoice value wins" for a
     * seller-placed term once such an override can exist (see that mapper's own KDoc).
     */
    @Test
    fun `happy - a guided invoice-level override of a SELLER-placed field wins over the seller's own captured value, in resolution and in export`() {
        val tag = "EXB6"
        val (senderId, recipientId, invoiceId, profileId) = QuarkusTransaction.requiringNew().call {
            val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
            field(profile, B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL, B2bPlacement.SELLER)
            val s = sender(tag)
            val r = recipient(tag).edit { b2bProfile = em.find(B2bProfile::class.java, profile.id) }
            em.persist(s); em.persist(r); em.flush()
            val inv = lifecycleService.create(s.id!!, r.id!!)
            // An invoice without positions can no longer be issued, so the override scenario needs one.
            val rate = taxRateQueries.findAllTaxRates().find { it.isDefault }!!
            itemMutationService.addItem(inv.id!!, InvoiceItemCommand(category = InvoiceItemCategory.SERVICE, description = "Item1", quantity = BigDecimal.ONE, unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("10.00"), taxRateId = rate.id))
            listOf(s.id!!, r.id!!, inv.id!!, profile.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId; createdProfileIds += profileId

        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.SENDER_PROFILE, senderId, mapOf("BT-56" to "Frau Meier")) }

        val beforeOverride = b2bFieldValueQueries.findB2bGuidedFieldValues(invoiceId).getValue("BT-56")
        assertEquals("Frau Meier", beforeOverride.value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.PARTY, beforeOverride.origin)

        QuarkusTransaction.requiringNew().run {
            manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-56" to "Herr Schmidt (nur diese Rechnung)"))
        }

        val afterOverride = b2bFieldValueQueries.findB2bGuidedFieldValues(invoiceId).getValue("BT-56")
        assertEquals("Herr Schmidt (nur diese Rechnung)", afterOverride.value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.INVOICE, afterOverride.origin)

        finalizeToSent(invoiceId)
        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(em.find(Invoice::class.java, invoiceId)!!) }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)

        EInvoiceXml.assertText(
            xml,
            "//ram:BuyerTradeParty/ram:DefinedTradeContact/ram:PersonName",
            "Herr Schmidt (nur diese Rechnung)",
            "EX-B6 - the guided step's invoice-level override reaches the wire, not the seller's own captured value",
        )
    }

    // ─── Border case: an untouched inherited value is not written back ─────────────────────────────────────

    /**
     * The write-back rule: saving `null` for a field whose widget still shows its
     * inherited (party or default) value must not create an invoice-level row, and editing an
     * existing override back to that inherited value must delete it — a later master-data
     * correction must keep reaching an open invoice that never diverged from it.
     */
    @Test
    fun `border - the write-back rule leaves no invoice row for an untouched inherited value, and deletes one edited back to it`() {
        val tag = "EXB7"
        val (senderId, recipientId, invoiceId, profileId) = QuarkusTransaction.requiringNew().call {
            val profile = manageB2bProfile.saveB2bProfile(B2bProfileDto(name = "$tag Profile"))
            field(profile, B2bTerm.BT_56, B2bFieldRequirement.OPTIONAL, B2bPlacement.SELLER)
            val s = sender(tag)
            val r = recipient(tag).edit { b2bProfile = em.find(B2bProfile::class.java, profile.id) }
            em.persist(s); em.persist(r); em.flush()
            val inv = lifecycleService.create(s.id!!, r.id!!)
            listOf(s.id!!, r.id!!, inv.id!!, profile.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId; createdProfileIds += profileId

        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.SENDER_PROFILE, senderId, mapOf("BT-56" to "Frau Meier")) }

        // The write-back rule (B2bProfileFieldsSection.currentValues) submits `null` for a widget
        // still at its inherited value — saveFieldValues must not create a row for that.
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-56" to null)) }
        assertTrue(
            b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId).isEmpty(),
            "an untouched inherited value must not materialize as an invoice row",
        )
        assertEquals("Frau Meier", b2bFieldValueQueries.findB2bGuidedFieldValues(invoiceId).getValue("BT-56").value, "resolution still falls through to the seller's own value")

        // A genuine override IS stored.
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-56" to "Herr Schmidt")) }
        assertEquals(mapOf("BT-56" to "Herr Schmidt"), b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId))

        // Edited back to the inherited value -> the write-back rule maps that to `null` again ->
        // the row is deleted.
        QuarkusTransaction.requiringNew().run { manageB2bFieldValue.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, mapOf("BT-56" to null)) }
        assertTrue(
            b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE, invoiceId).isEmpty(),
            "an override edited back to the inherited value deletes the row",
        )
    }
}
