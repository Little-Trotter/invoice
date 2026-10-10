package de.littletrotter.system

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * The E2E proof of the PO requirement:
 * when the seller (Müller, DE) already holds a VAT registration for the recipient's country
 * (Michels, in Luxembourg/France/Belgium/the Netherlands), the invoice adopts that foreign VAT
 * ID automatically — no manual `sellerVatRegistrationCountry` selection — and every position is
 * taxed at that country's rates. Parameterized over LU/FR/BE/NL (§4.2), one instance of each
 * scenario per country except the two explicitly single-country cases (blocking, finalization).
 *
 * Follows [SystemWorkflowLuxembourgIT] exactly: fixture-per-test with unique suffixes (several
 * columns are unique-constrained across the whole table, not just per test), setup and reload
 * each in their own [QuarkusTransaction.requiringNew] transaction, `editInvoiceHeader
 * .updateInvoiceHeader(...)` calls left unwrapped for the same reason documented on that class —
 * `updateHeader` is itself `@Transactional`, and wrapping the *blocking* scenario's call in an
 * outer `QuarkusTransaction.requiringNew().run { assertThrows(...) { ... } }` would mark that
 * outer transaction rollback-only before the block catches the exception, turning a passing
 * assertion into a `RollbackException` at the later commit.
 *
 * [InvoiceLifecycleService] is injected directly (not just via [EditInvoiceHeaderUseCase]) so `create()` and
 * `transition()` can be asserted against the plain [Invoice] entity — the DTO-shaped input port
 * exists for the UI, not for this test's assertions.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SystemWorkflowCrossBorderVatIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    data class CountryRates(
        val country: Country,
        val standardCode: String,
        val standardRate: BigDecimal,
        val reducedCode: String,
        val reducedRate: BigDecimal,
    )

    companion object {
        /**
         * Shared across all test instances, so it really does count up. It replaces
         * `System.nanoTime() % 100000`, which only looked unique: 100 000 possible values drawn
         * once per [persistFixture] call, so two calls inside one parameterised method could —
         * and did — land on the same number and fail with
         * `duplicate key value violates unique constraint "uq_tax_rate_code"`. Same fix and same
         * reasoning as [de.littletrotter.application.SenderProfileEditLoadIT]; the time component
         * is dropped outright rather than combined, because it never contributed uniqueness.
         */
        private val uniqueCounter = AtomicInteger(0)

        @JvmStatic
        fun countries(): Stream<Arguments> = Stream.of(
            Arguments.of(CountryRates(Country.LU, "LU_STANDARD_17", BigDecimal("17.00"), "LU_REDUCED_8", BigDecimal("8.00"))),
            Arguments.of(CountryRates(Country.FR, "FR_STANDARD_20", BigDecimal("20.00"), "FR_REDUCED_10", BigDecimal("10.00"))),
            Arguments.of(CountryRates(Country.BE, "BE_STANDARD_21", BigDecimal("21.00"), "BE_REDUCED_6", BigDecimal("6.00"))),
            Arguments.of(CountryRates(Country.NL, "NL_STANDARD_21", BigDecimal("21.00"), "NL_REDUCED_9", BigDecimal("9.00"))),
        )
    }

    private data class Fixture(
        val senderId: Long,
        val recipientXId: Long,
        val recipientDeId: Long,
        val consultingArticleId: Long,
        val goodsArticleId: Long,
        val noVariantArticleId: Long,
        val deRateId: Long,
        val standardRateCode: String,
        val reducedRateCode: String,
    )

    /**
     * Müller (DE, [sender]) with a VAT registration for [rates.country] iff [registerSeller];
     * Michels ([recipientXId]) billed in that country, plus a plain German customer
     * ([recipientDeId]) for the recipient-change scenarios. Two article-bound catalog entries —
     * "Consulting" (SERVICE, [rates.country]'s standard-rate variant) and "Werkstoff" (PRODUCT,
     * the reduced-rate variant) — plus one article that deliberately carries no variant at all
     * ([noVariantArticleId], for the blocking scenario).
     */
    private fun persistFixture(tag: String, rates: CountryRates, registerSeller: Boolean = true): Fixture {
        // Unique per call: several columns below (invoice_prefix, customer_number, tax_rate.code)
        // are unique-constrained across the whole table, not just per test.
        val unique = uniqueCounter.incrementAndGet()
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "Müller $tag GmbH"
            // Inner hyphens are valid too (InvoicePrefixRule / InvoiceNumber share one prefix regex);
            // this fixture just keeps the prefix hyphen-free.
            invoicePrefix = "M$tag%05d".format(unique)
            email = Email.ofNullable("billing@mueller-$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            registerCourt = "Amtsgericht Berlin (Charlottenburg)"
        }
        val recipientX = Recipient().apply {
            customerNumber = CustomerNumber("KD-$tag-X-%05d".format(unique))
            name = "Michels ${rates.country.alpha2} S.A."
            email = Email.ofNullable("ap@michels-$tag.example")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE02100500000054540402")
            routingId = "LWID-X-$tag-%05d".format(unique)
        }
        recipientX.addAddress(
            Address.forRecipient(recipientX, Address.AddressType.BILLING, "Rue Exemple 1", "1000", "Capitale", rates.country, true),
        )
        val recipientDe = Recipient().apply {
            customerNumber = CustomerNumber("KD-$tag-DE-%05d".format(unique))
            name = "Schulze GmbH"
            email = Email.ofNullable("ap@schulze-$tag.example")
            vatId = VatId("DE222222222")
            iban = Iban.ofNullable("DE02500105170137075030")
            routingId = "LWID-DE-$tag-%05d".format(unique)
        }
        recipientDe.addAddress(
            Address.forRecipient(recipientDe, Address.AddressType.BILLING, "Musterstraße 1", "10115", "Berlin", Country.DE, true),
        )

        val deRate = TaxRate().apply {
            name = "USt 19% (DE)"; rate = TaxRatePercent(BigDecimal("19.00")); country = Country.DE
            code = "DE-STD-$tag-$unique"; category = "S"; isSelectable = true; isActive = true
        }
        val standardRate = TaxRate().apply {
            name = "Standard ${rates.country.alpha2}"; rate = TaxRatePercent(rates.standardRate); country = rates.country
            code = "${rates.standardCode}-$tag-$unique"; category = "S"; isSelectable = true; isActive = true
        }
        val reducedRate = TaxRate().apply {
            name = "Reduced ${rates.country.alpha2}"; rate = TaxRatePercent(rates.reducedRate); country = rates.country
            code = "${rates.reducedCode}-$tag-$unique"; category = "S"; isSelectable = true; isActive = true
        }

        val consultingArticle = Article().apply {
            name = "Consulting"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.HOUR
            defaultQuantity = BigDecimal.ONE
            defaultUnitPriceNet = Money.of("120.00")
            defaultTaxRateRef = deRate
        }
        val goodsArticle = Article().apply {
            name = "Werkstoff"
            category = InvoiceItemCategory.PRODUCT
            defaultUnit = InvoiceItemUnit.PIECE
            defaultQuantity = BigDecimal.ONE
            defaultUnitPriceNet = Money.of("80.00")
            defaultTaxRateRef = deRate
        }
        val noVariantArticle = Article().apply {
            name = "Ohne Länder-Variante"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.HOUR
            defaultQuantity = BigDecimal.ONE
            defaultUnitPriceNet = Money.of("90.00")
            defaultTaxRateRef = deRate
        }

        em.persist(deRate); em.persist(standardRate); em.persist(reducedRate)
        em.persist(sender); em.persist(recipientX); em.persist(recipientDe)
        em.persist(consultingArticle); em.persist(goodsArticle); em.persist(noVariantArticle)

        if (registerSeller) {
            sender.updateVatRegistrations(
                listOf(SellerVatRegistrationCommand(rates.country, VatId(sellerVatIdFor(rates.country)))), today = FIXED_TODAY,
            )
        }
        consultingArticle.replaceTaxVariants(listOf(ArticleTaxVariant.of(consultingArticle, rates.country, standardRate, today = FIXED_TODAY)), today = FIXED_TODAY)
        goodsArticle.replaceTaxVariants(listOf(ArticleTaxVariant.of(goodsArticle, rates.country, reducedRate, today = FIXED_TODAY)), today = FIXED_TODAY)
        // noVariantArticle deliberately keeps an empty taxVariants list (the blocking scenario).

        em.flush()

        return Fixture(
            senderId = sender.id!!,
            recipientXId = recipientX.id!!,
            recipientDeId = recipientDe.id!!,
            consultingArticleId = consultingArticle.id!!,
            goodsArticleId = goodsArticle.id!!,
            noVariantArticleId = noVariantArticle.id!!,
            deRateId = deRate.id!!,
            standardRateCode = standardRate.code!!,
            reducedRateCode = reducedRate.code!!,
        )
    }

    /** A real-world-shaped VAT id per country (VatIdValidator's per-country regex, not just a
     *  shared "prefix + 8 digits" pattern — LU's 8-digit id is not a valid shape for the others). */
    private fun sellerVatIdFor(country: Country): String = when (country) {
        Country.LU -> "LU12345678"
        Country.FR -> "FR12345678901"
        Country.BE -> "BE0123456789"
        Country.NL -> "NL123456789B01"
        else -> throw IllegalArgumentException("No fixture VAT id template for ${country.alpha2}.")
    }

    private fun headerCommand(
        fixture: Fixture,
        invoiceId: Long,
        recipientId: Long?,
        sellerVatRegistrationCountry: String? = null,
        invoiceFormat: String? = null,
    ) = UpdateInvoiceHeaderCommand(
        invoiceId = invoiceId,
        expectedVersion = null,
        invoiceDate = LocalDate.of(2026, 8, 1),
        servicePeriodFrom = null,
        servicePeriodTo = null,
        dueDate = LocalDate.of(2026, 8, 31),
        freeText = null,
        buyerReference = null,
        orderReference = null,
        orderReferenceDate = null,
        currency = Currency.EUR,
        recipientId = recipientId,
        senderProfileId = fixture.senderId,
        paymentTermId = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry,
        invoiceFormat = invoiceFormat,
    )

    // ── 1. Consultant scenario (SERVICE) — the PO's core assertion ──────────────────────────────

    @ParameterizedTest(name = "consultant scenario ({0})")
    @MethodSource("countries")
    fun `create derives the foreign registration automatically and taxes the service at the standard rate`(rates: CountryRates) {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C1", rates) }

        val created = lifecycleService.create(fixture.senderId, fixture.recipientXId)
        assertEquals(
            rates.country, created.sellerVatRegistrationCountry,
            "the invoice must carry the seller's ${rates.country.alpha2} registration WITHOUT any manual header update",
        )
        assertEquals(InvoiceFormat.GERMAN, created.invoiceFormat, "V5 — invoiceFormat is never implied, only explicitly chosen")

        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "Consulting",
                quantity = BigDecimal("2"),
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("120.00"),
                articleId = fixture.consultingArticleId,
            ),
        )

        val dto = QuarkusTransaction.requiringNew().call {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            val item = reloaded.itemList.single()
            assertEquals(fixture.standardRateCode, item.taxRuleCode)
            assertEquals(0, rates.standardRate.compareTo(item.taxRateSnapshot?.value))
            assertEquals(0, Money.of("240.00").amount.compareTo(reloaded.totals.netAmount.amount), "net total")
            val expectedVat = Money.of("240.00").percentage(rates.standardRate)
            assertEquals(0, expectedVat.amount.compareTo(reloaded.totals.vatAmount.amount), "vat total")
            assertEquals(
                0, (Money.of("240.00") + expectedVat).amount.compareTo(reloaded.totals.grossAmount.amount), "gross total",
            )
            InvoiceDtoMapper().mapToDto(reloaded)
        }

        assertEquals(rates.country.alpha2, dto.meta.sellerVatRegistrationCountry, "the foreign VAT registration is exposed on the wire")
        val registration = dto.senderProfile?.additionalVatRegistrations?.singleOrNull { it.country == rates.country.alpha2 }
        assertEquals(sellerVatIdFor(rates.country), registration?.vatId, "the seller's ${rates.country.alpha2} VAT id is selectable by country in the mapped DTO")
    }

    // ── 2. Craftsman scenario (PRODUCT) — reduced-rate variants flow through unchanged ──────────

    @ParameterizedTest(name = "craftsman scenario ({0})")
    @MethodSource("countries")
    fun `create derives the foreign registration automatically and taxes goods at the reduced rate`(rates: CountryRates) {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C2", rates) }

        val created = lifecycleService.create(fixture.senderId, fixture.recipientXId)
        assertEquals(rates.country, created.sellerVatRegistrationCountry)

        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.PRODUCT,
                description = "Werkstoff",
                quantity = BigDecimal("5"),
                unit = InvoiceItemUnit.PIECE,
                unitPriceNet = Money.of("80.00"),
                articleId = fixture.goodsArticleId,
            ),
        )

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            val item = reloaded.itemList.single()
            assertEquals(fixture.reducedRateCode, item.taxRuleCode)
            assertEquals(0, rates.reducedRate.compareTo(item.taxRateSnapshot?.value))
            assertEquals(0, Money.of("400.00").amount.compareTo(reloaded.totals.netAmount.amount), "net total")
        }
    }

    // ── 3. Recipient change re-derivation ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "recipient change re-derivation ({0})")
    @MethodSource("countries")
    fun `switching the recipient re-derives the registration, switching back to Germany clears it`(rates: CountryRates) {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C3", rates) }

        val created = lifecycleService.create(fixture.senderId, fixture.recipientDeId)
        assertNull(created.sellerVatRegistrationCountry, "a German recipient derives to null")

        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "Consulting",
                quantity = BigDecimal.ONE,
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("120.00"),
                articleId = fixture.consultingArticleId,
                taxRateId = fixture.deRateId,
            ),
        )
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertEquals(0, BigDecimal("19.00").compareTo(reloaded.itemList.single().taxRateSnapshot?.value), "still on the German default rate before any recipient change")
        }

        // Switch to the X recipient: the header command's own sellerVatRegistrationCountry is
        // ignored — the recipient change alone drives the re-derivation.
        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, afterItem.id!!, fixture.recipientXId))
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertEquals(rates.country, reloaded.sellerVatRegistrationCountry)
            val item = reloaded.itemList.single()
            assertEquals(fixture.standardRateCode, item.taxRuleCode, "the existing article-bound item is re-taxed to the derived country's rate")
            assertEquals(0, rates.standardRate.compareTo(item.taxRateSnapshot?.value))
        }

        // Switch back to the German recipient: derives to null, the frozen snapshot is untouched.
        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, afterItem.id!!, fixture.recipientDeId))
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertNull(reloaded.sellerVatRegistrationCountry)
            assertEquals(InvoiceFormat.GERMAN, reloaded.invoiceFormat)
            val item = reloaded.itemList.single()
            assertEquals(fixture.standardRateCode, item.taxRuleCode, "the item's tax snapshot stays whatever it was last derived to")
        }
    }

    // ── 3b. Recipient change re-derivation falls back to a delivery-only address ─────────────────

    @Test
    fun `recipient change re-derivation falls back to the new recipient's delivery address when it has no billing address`() {
        val luRates = CountryRates(Country.LU, "LU_STANDARD_17", BigDecimal("17.00"), "LU_REDUCED_8", BigDecimal("8.00"))
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C3B", luRates) }

        // A second LU recipient that deliberately carries only a DELIVERY address, no BILLING
        // one — exercises the fallback half of `newRecipient?.billingAddress?.country ?:
        // newRecipient?.deliveryAddress?.country`, the counterpart to the billing-address
        // path every other scenario here already exercises.
        val deliveryOnlyRecipientId = QuarkusTransaction.requiringNew().call<Long> {
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("KD-C3B-DELIVERY-%05d".format(uniqueCounter.incrementAndGet()))
                name = "Michels Lieferadresse S.A."
                email = Email.ofNullable("ap@michels-c3b.example")
                vatId = VatId("DE333333333")
                iban = Iban.ofNullable("DE02120300000000202051")
            }
            recipient.addAddress(
                Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Rue Livraison 5", "1010", "Capitale", Country.LU, true),
            )
            em.persist(recipient)
            em.flush()
            recipient.id!!
        }

        val created = lifecycleService.create(fixture.senderId, fixture.recipientDeId)
        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "Consulting",
                quantity = BigDecimal.ONE,
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("120.00"),
                articleId = fixture.consultingArticleId,
                taxRateId = fixture.deRateId,
            ),
        )

        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, afterItem.id!!, deliveryOnlyRecipientId))

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertEquals(Country.LU, reloaded.sellerVatRegistrationCountry, "derivation falls back to the delivery address when no billing address exists")
            assertEquals(fixture.standardRateCode, reloaded.itemList.single().taxRuleCode)
        }
    }

    // ── 4. No registration — existing DE/reverse-charge behavior stays untouched ─────────────────

    @ParameterizedTest(name = "no registration ({0})")
    @MethodSource("countries")
    fun `a recipient in a registered-elsewhere country never derives without an actual registration`(rates: CountryRates) {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C4", rates, registerSeller = false) }

        val created = lifecycleService.create(fixture.senderId, fixture.recipientXId)
        assertNull(created.sellerVatRegistrationCountry, "no registration for ${rates.country.alpha2} means no derivation, however plausible the recipient's country")

        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "Consulting",
                quantity = BigDecimal.ONE,
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("120.00"),
                articleId = fixture.consultingArticleId,
                taxRateId = fixture.deRateId,
            ),
        )
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertNull(reloaded.sellerVatRegistrationCountry)
            assertEquals(0, BigDecimal("19.00").compareTo(reloaded.itemList.single().taxRateSnapshot?.value), "unchanged German default rate")
        }
    }

    // ── 5. Blocking — mirrors the existing LU blocking test, here for FR ─────────────────────────

    @Test
    fun `blocking case - a derived FR registration whose article has no FR variant blocks the whole save`() {
        val frRates = CountryRates(Country.FR, "FR_STANDARD_20", BigDecimal("20.00"), "FR_REDUCED_10", BigDecimal("10.00"))
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C5", frRates) }

        // A DE-recipient draft with an item bound to the article that carries NO FR variant.
        val created = lifecycleService.create(fixture.senderId, fixture.recipientDeId)
        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "Ohne Länder-Variante",
                quantity = BigDecimal.ONE,
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("90.00"),
                articleId = fixture.noVariantArticleId,
                taxRateId = fixture.deRateId,
            ),
        )

        // Manually selecting FR (recipient unchanged) hits the existing re-apply loop, which
        // throws because the bound article has no FR variant — the header save aborts entirely.
        val ex = assertThrows(InvoiceValidationException::class.java) {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, afterItem.id!!, fixture.recipientDeId, sellerVatRegistrationCountry = "FR"))
        }
        assertEquals(InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING, ex.error)

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertNull(reloaded.sellerVatRegistrationCountry, "nothing partially saved — the blocked transaction rolled back")
        }
    }

    // ── 6. Finalization — one full happy path (LU) through finalize ──────────────────────────────

    @Test
    fun `finalization happy path - LU totals and tax snapshot are frozen at LU rates`() {
        val luRates = CountryRates(Country.LU, "LU_STANDARD_17", BigDecimal("17.00"), "LU_REDUCED_8", BigDecimal("8.00"))
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture("C6", luRates) }

        val created = lifecycleService.create(fixture.senderId, fixture.recipientXId)
        assertEquals(Country.LU, created.sellerVatRegistrationCountry)

        val afterItem = itemMutationService.addItem(
            created.id!!,
            InvoiceItemCommand(
                category = InvoiceItemCategory.SERVICE,
                description = "Consulting",
                quantity = BigDecimal("3"),
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("120.00"),
                articleId = fixture.consultingArticleId,
            ),
        )

        // CORE.SERVICE_PERIOD_XOR ships in
        // littletrotter.compliance-rules.blocking-rules' default and enforces even at this IT's
        // own OBSERVE-default complianceStage; BT-72 satisfies it cheaply.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, afterItem.id)!!.deliveryDate = LocalDate.now() }

        lifecycleService.transition(afterItem.id!!, InvoiceStatus.IN_REVIEW)
        lifecycleService.transition(afterItem.id!!, InvoiceStatus.SENT)

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, afterItem.id)!!
            assertEquals(InvoiceStatus.SENT, reloaded.status)
            assertTrue(reloaded.invoiceNumber.isAssigned(), "a finalized invoice carries an assigned number")
            assertEquals(Country.LU, reloaded.sellerVatRegistrationCountry)

            val item = reloaded.itemList.single()
            assertEquals(fixture.standardRateCode, item.taxRuleCode)
            assertEquals(0, luRates.standardRate.compareTo(item.taxRateSnapshot?.value))

            val expectedNet = Money.of("360.00")
            val expectedVat = expectedNet.percentage(luRates.standardRate)
            assertEquals(0, expectedNet.amount.compareTo(reloaded.totals.netAmount.amount), "net total frozen at finalization")
            assertEquals(0, expectedVat.amount.compareTo(reloaded.totals.vatAmount.amount), "vat total frozen at LU 17 %")
            assertEquals(0, (expectedNet + expectedVat).amount.compareTo(reloaded.totals.grossAmount.amount), "gross total frozen")
        }
    }
}
