package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceStornoService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.compliance.reverseChargeEligibility
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * LU recipient x seller LU VAT registration, facade-level. Extends what [SellerVatRegistrationPreselectE2EIT] (Vaadin/Karibu, entry-pane level) and [ItemTaxRateForeignSellerE2EIT] (manual tax-rate selection) already prove with three genuinely new facts: (1) [InvoiceLifecycleService.create] itself — not just the entry pane — already persists the preselect the moment the invoice is created (see the happy-path test's own KDoc for the reality-vs-spec pin this uncovers), (2) an article-bound position under an active LU registration auto-derives its tax rate from the article's own LU [ArticleTaxVariant] with no manual `taxRateId`, and (3) [de.littletrotter.domain.invoice.Invoice.adoptPartiesAndTaxContextFrom] carries the LU registration context onto a storno document, a combination [InvoiceStornoE2EIT]/[de.littletrotter.e2e.receipts.StornoReceiptEmbeddingE2EIT] never exercise.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class LuSellerVatRegistrationE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var time: TimeSource

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    @Inject
    lateinit var stornoService: InvoiceStornoService

    @Inject
    lateinit var articleRepository: ArticleRepository

    @Inject
    lateinit var b2bProfileRepository: B2bProfileRepository

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

    private fun senderWithLuRegistration(tag: String): SenderProfile {
        val sender = SenderProfile().apply {
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
        sender.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), java.time.LocalDate.now())
        return sender
    }

    private fun senderWithoutLuRegistration(tag: String): SenderProfile {
        val sender = SenderProfile().apply {
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
        return sender
    }

    private fun luRecipient(tag: String, vatIdVerified: Boolean = false): Recipient {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Michels S.A."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("LU21416127")
            customerType = CustomerType.BUSINESS
            routingId = "PO-$tag-1"
            if (vatIdVerified) vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        return recipient
    }

    private fun luTaxRateId(): Long =
        taxRateQueries.findAllTaxRates().find { it.code == "LU_STANDARD_17" }?.id
            ?: error("expected seeded TaxRate 'LU_STANDARD_17' (V2__baseline_seed.sql) to exist")

    private fun reverseChargeTaxRateId(): Long =
        taxRateQueries.findAllTaxRates().find { it.categoryEnum == VatCategory.REVERSE_CHARGE }?.id
            ?: error("expected seeded REVERSE_CHARGE TaxRate (V2__baseline_seed.sql) to exist")

    // ─── LU seller registration preselect (happy) ────────────────────────────────────────────

    /**
     * Pins BT-… preselect (`Invoice.sellerVatRegistrationCountry`), reverse-charge
     * suppression, article-driven LU tax variant pricing, and BT-31 (SellerTradeParty
     * SpecifiedTaxRegistration) carrying the LU registration id on export.
     *
     * **Reality-vs-spec pin:** the spec text reads "createInvoiceWithRecipient -> ...
     * (preselect)" as if this were a novel fact to prove; it is real, but the mechanism differs
     * from what [SellerVatRegistrationPreselectE2EIT]'s own KDoc describes ("opening an invoice
     * with no stored value must pre-select..."): `InvoiceLifecycleService.create` already calls
     * `deriveSellerVatRegistrationCountry` and stores the result on the
     * `Invoice` row at creation time, before any entry-pane/Save round trip. The entry pane's own
     * `resolveSellerVatRegistrationCountry` (TaxRuleUiSupport) is a second, independent pure
     * function used only when the stored field is still null (e.g., legacy invoices predating
     * this derivation) — for a freshly created invoice today it is redundant with the already-persisted
     * value, not the mechanism that sets it. That older suite's "no stored value" phrasing is
     * therefore stale documentation, not a functional bug — flagged here, not changed (production
     * code is out of scope for this suite).
     */
    @Test
    fun `LU recipient with seller LU registration preselects, suppresses reverse charge, prices via the article LU variant and exports BT-31`() {
        val tag = "EXA1"
        val (senderId, recipientId, profileId, articleId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithLuRegistration(tag)
            val profile = b2bProfileRepository.save(B2bProfile().apply { name = "$tag Standing Profile" })
            val recipient = luRecipient(tag).apply { b2bProfile = profile }
            em.persist(sender); em.persist(recipient); em.flush()

            val luRate = taxRateQueries.findAllTaxRates().find { it.code == "LU_STANDARD_17" }!!
            val today = java.time.LocalDate.now()
            val article = Article().apply { name = "$tag Beratung" }
            article.replaceTaxVariants(listOf(ArticleTaxVariant.of(article, Country.LU, em.find(TaxRate::class.java, luRate.id), today)), today)
            val savedArticle = articleRepository.save(article)
            listOf(sender.id!!, recipient.id!!, profile.id!!, savedArticle.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId
        createdProfileIds += profileId; createdArticleIds += articleId

        val created = QuarkusTransaction.requiringNew().call {
            createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId)
        }
        createdInvoiceIds += created.id

        // Preselect — persisted immediately by create(), no Save/entry-pane round trip needed.
        assertEquals("LU", created.sellerVatRegistrationCountry, "R-1 preselects the seller's LU registration from the recipient's LU billing country")
        assertFalse(created.reverseCharge, "reverse charge is never auto-enabled; it stays the header's own explicit decision")

        // The standing B2B profile is carried onto the invoice, same as InvoiceCreateForCustomerE2EIT
        // exercises in more depth — asserted here only to honor the spec's "and standing B2B profile" clause.
        QuarkusTransaction.requiringNew().run {
            assertEquals(profileId, em.find(Invoice::class.java, created.id)!!.b2bProfile?.id, "AP-9 rule 1 — b2bProfile derives from the recipient at creation")
        }

        // Reverse charge NOT proposed: a non-null sellerVatRegistrationCountry disables RC outright.
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, created.id)!!
            assertNull(invoice.reverseChargeEligibility(time.today()), "a non-null sellerVatRegistrationCountry disables reverse-charge eligibility (TaxCategoryConsistency.kt)")
        }

        // Article-bound item under the LU registration: applySellerCountryTaxVariant fires with
        // NO explicit taxRateId, unlike ItemTaxRateForeignSellerE2EIT's manual-selection cases.
        val saved = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                created.id,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    articleId = articleId,
                    taxRateId = null,
                ),
            )
        }
        val item = saved.itemList.single()
        assertEquals(luTaxRateId(), item.taxRateRef?.id, "the LU tax variant, not a manually chosen rate, prices this position")
        assertEquals(Money.of("34.00"), item.vatAmount, "17 % of 200.00 (LU_STANDARD_17)")

        // Finalize and export — BT-31 carries the LU registration's own vatId.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, created.id)!!.deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(created.id, de.littletrotter.domain.invoice.InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(created.id, de.littletrotter.domain.invoice.InvoiceStatus.SENT) }
        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper().mapToDto(em.find(Invoice::class.java, created.id)!!) }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", "LU12345678",
            "BT-31 (the LU registration, not the seller's German home vatId)",
        )
    }

    // ─── Seller without LU registration proposes reverse charge (border) ─────────────────────

    /** Seller WITHOUT an LU registration: an explicit Yes creates the RC invoice and
     *  the exported line carries category AE with the VATEX-EU-AE exemption text. */
    @Test
    fun `seller without LU registration proposes reverse charge and exports AE with exemption text`() {
        val tag = "EXA2"
        val (senderId, recipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithoutLuRegistration(tag)
            val recipient = luRecipient(tag, vatIdVerified = true)
            em.persist(sender); em.persist(recipient); em.flush()
            val invoiceId = lifecycleService.create(sender.id!!, recipient.id!!, reverseChargeDecision = true).id!!
            Triple(sender.id!!, recipient.id!!, invoiceId)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId

        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertNull(invoice.sellerVatRegistrationCountry, "the seller holds no LU registration, so nothing is preselected")
            assertTrue(invoice.reverseCharge, "the explicit Yes decision is persisted at creation")
            val proposal = invoice.reverseChargeEligibility(time.today())
            assertNotNull(proposal, "an EU/LU business recipient with a VERIFIED VAT-id is reverse-charge eligible once no seller registration blocks it")
            assertEquals(VatCategory.REVERSE_CHARGE, proposal!!.category)
        }

        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("500.00"),
                    taxRateId = reverseChargeTaxRateId(),
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, de.littletrotter.domain.invoice.InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, de.littletrotter.domain.invoice.InvoiceStatus.SENT) }

        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper().mapToDto(em.find(Invoice::class.java, invoiceId)!!) }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        EInvoiceXml.assertText(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedLineTradeSettlement/ram:ApplicableTradeTax/ram:CategoryCode", "AE", "Reverse-charge item CategoryCode")
        EInvoiceXml.assertText(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedLineTradeSettlement/ram:ApplicableTradeTax/ram:ExemptionReasonCode", "VATEX-EU-AE", "Reverse-charge item ExemptionReasonCode")
        assertNotNull(
            EInvoiceXml.text(xml, "//ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedLineTradeSettlement/ram:ApplicableTradeTax/ram:ExemptionReason"),
            "Reverse-charge item must carry an exemption reason text, not just the code",
        )
    }

    @Test
    fun `cross-border creation rejects unanswered and persists an explicit No`() {
        val tag = "EXA2B"
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithoutLuRegistration(tag)
            val recipient = luRecipient(tag, vatIdVerified = true)
            em.persist(sender); em.persist(recipient); em.flush()
            sender.id!! to recipient.id!!
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId

        val unanswered = assertThrows(InvalidParameterException::class.java) {
            QuarkusTransaction.requiringNew().call {
                lifecycleService.create(senderId, recipientId, reverseChargeDecision = null)
            }
        }
        assertTrue(unanswered.message!!.contains("ausdrücklich mit Ja oder Nein"))

        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(senderId, recipientId, reverseChargeDecision = false).id!!
        }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertFalse(invoice.reverseCharge)
            assertNull(invoice.sellerVatRegistrationCountry)
        }
    }

    // ─── INTERNATIONAL format with an LU registration (border) ───────────────────────────────

    private fun headerCommand(invoiceId: Long, senderId: Long, recipientId: Long, sellerVatRegistrationCountry: String?, invoiceFormat: String?) =
        UpdateInvoiceHeaderCommand(
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
            sellerVatRegistrationCountry = sellerVatRegistrationCountry,
            invoiceFormat = invoiceFormat,
        )

    /**
     * `invoiceFormat = INTERNATIONAL` with an active LU registration is accepted.
     *
     * **Reality-vs-spec pin:** the spec's second half ("clearing the registration country while
     * format stays INTERNATIONAL is rejected") does not hold as written. Nothing throws — the
     * unchanged-recipient branch of `InvoiceLifecycleService.applySellerVatRegistration` reads
     * `command.sellerVatRegistrationCountry` (here `null`, so the field is cleared) and
     * `applyInvoiceFormat`'s `isInvoiceFormatEditable` guard then silently forces
     * `InvoiceFormat.GERMAN` regardless of what `command.invoiceFormat` asked for — the DB CHECK
     * constraint `chk_invoice_format_requires_seller_vat_registration` is never even reached
     * because the service layer keeps the two fields consistent on every save. "Rejected" reads
     * as "raises an error"; the actual behavior is a silent, in-band correction. Asserted below as
     * the correction it actually is.
     */
    @Test
    fun `INTERNATIONAL format is accepted with an LU registration and silently coerced back to GERMAN once the registration is cleared`() {
        val tag = "EXA3"
        val (senderId, recipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithLuRegistration(tag)
            val recipient = luRecipient(tag)
            em.persist(sender); em.persist(recipient); em.flush()
            val invoiceId = lifecycleService.create(sender.id!!, recipient.id!!).id!!
            Triple(sender.id!!, recipient.id!!, invoiceId)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId

        QuarkusTransaction.requiringNew().run {
            assertEquals(Country.LU, em.find(Invoice::class.java, invoiceId)!!.sellerVatRegistrationCountry, "fixture sanity: create() preselected LU")
        }

        val withInternational = QuarkusTransaction.requiringNew().call {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(invoiceId, senderId, recipientId, sellerVatRegistrationCountry = "LU", invoiceFormat = "INTERNATIONAL"))
        }
        assertEquals("INTERNATIONAL", withInternational.invoiceFormat?.let { InvoiceFormat.entries[it].name }, "INTERNATIONAL is legal while the LU registration is active")

        val afterClearing = QuarkusTransaction.requiringNew().call {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(invoiceId, senderId, recipientId, sellerVatRegistrationCountry = null, invoiceFormat = "INTERNATIONAL"))
        }
        assertNull(afterClearing.sellerVatRegistrationCountry, "the registration is genuinely cleared")
        assertEquals(
            "GERMAN",
            afterClearing.invoiceFormat?.let { InvoiceFormat.entries[it].name },
            "reality: the service silently coerces the format back to GERMAN rather than rejecting the save (see this test's own KDoc)",
        )
    }

    // ─── Storno of an LU-registered invoice (border) ─────────────────────────────────────────

    /** Storno of an LU invoice: 384, negative totals, and the LU registration/tax context
     *  is copied onto the storno document by `adoptPartiesAndTaxContextFrom`, so its own export
     *  still carries BT-31 under the LU registration, not the seller's home vatId. */
    @Test
    fun `storno of an LU-registered invoice keeps 384, negative totals and the LU registration context`() {
        val tag = "EXA4"
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithLuRegistration(tag)
            val recipient = luRecipient(tag)
            em.persist(sender); em.persist(recipient); em.flush()
            Triple(sender.id!!, recipient.id!!, luTaxRateId())
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId

        val originalId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        createdInvoiceIds += originalId
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, originalId)!!.deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                originalId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, de.littletrotter.domain.invoice.InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, de.littletrotter.domain.invoice.InvoiceStatus.SENT) }

        val originalGross = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, originalId)!!.totals.grossAmount.amount }

        val creditNoteId = stornoService.createCreditNote(originalId, "Leistung storniert").id!!
        createdInvoiceIds += creditNoteId

        QuarkusTransaction.requiringNew().run {
            val creditNote = em.find(Invoice::class.java, creditNoteId)!!
            assertEquals(de.littletrotter.domain.invoice.InvoiceTypeCode.CORRECTED_INVOICE, creditNote.typeCode, "BT-3 = 384")
            assertTrue(creditNote.totals.grossAmount.amount.signum() < 0, "storno totals are negative")
            assertEquals(0, originalGross.negate().compareTo(creditNote.totals.grossAmount.amount))
            assertEquals(Country.LU, creditNote.sellerVatRegistrationCountry, "adoptPartiesAndTaxContextFrom copies the LU registration onto the storno document")
            assertFalse(creditNote.reverseCharge, "reverse charge is copied too (false here, same as the original)")
        }

        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper().mapToDto(em.find(Invoice::class.java, creditNoteId)!!) }
        assertEquals("384", dto.typeCode)
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", "LU12345678",
            "BT-31 on the storno document — still the LU registration, not the seller's German home vatId",
        )
    }
}
