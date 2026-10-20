package de.littletrotter.system

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleConfirmation
import de.littletrotter.shared.tax.TaxRuleResolutionRequest
import de.littletrotter.shared.tax.isStaleFor
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Currency
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * Full-stack proof of the Luxembourg seller-VAT-registration sales case
 * over the real Postgres-backed
 * [de.littletrotter.application.invoice.InvoiceLifecycleService.updateHeader]/[EditInvoiceHeaderUseCase]
 * path, not just unit tests per task. Lives next to [SystemWorkflowIT] — system tests stay in
 * `kotlin-invoice`; the once-planned separate integration-test module was dropped.
 *
 * Setup and reload each run in their own transaction ([QuarkusTransaction.requiringNew]), same
 * reasoning as [de.littletrotter.ui.view.invoice.InvoiceDetailWriteBackIT]: inside a single
 * transaction the persistence context would hand back the very instances written in setup and
 * hide any real round-trip effect. The `editInvoiceHeader.updateInvoiceHeader(...)` calls themselves
 * are deliberately left *unwrapped*, unlike that sibling test: `updateHeader` is itself
 * `@Transactional`, and wrapping the *blocking* scenario's call in an outer
 * `QuarkusTransaction.requiringNew().run { assertThrows(...) { ... } } }` would let
 * `InvoiceValidationException` mark that outer transaction rollback-only before the block catches
 * it, so the later commit would throw a `RollbackException` even though the assertion itself
 * passed. Calling `updateInvoiceHeader` directly lets its own `@Transactional` boundary start and
 * roll back its own transaction cleanly, with nothing ambient to poison.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SystemWorkflowLuxembourgIT {

    companion object {
        /**
         * Shared across all test instances, so it really does count up. It replaces
         * `System.nanoTime() % 100000`, which only looked unique: 100 000 possible values drawn
         * once per [persistFixture] call, so two calls could land on the same number and fail with
         * `duplicate key value violates unique constraint`. Same fix and same reasoning as
         * [de.littletrotter.application.SenderProfileEditLoadIT]; the time component is dropped
         * outright rather than combined, because it never contributed uniqueness. Still well
         * inside 5 digits, so the "DSC-#####" prefix keeps fitting its max-10 column.
         */
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    private data class Fixture(
        val invoiceId: Long,
        val recipientId: Long,
        val senderId: Long,
        val articleWithVariantId: Long,
        val itemId: Long,
        val deRateCode: String,
        val luRateCode: String,
    )

    /** [withLuVariant] — whether the bound article carries an LU [ArticleTaxVariant]; the
     *  blocking scenario deliberately omits it. */
    private fun persistFixture(withLuVariant: Boolean): Fixture {
        // Unique per call: each test method persists its own fixture against the same shared DB,
        // and several columns below (invoice_prefix, customer_number, tax_rate.code,
        // invoice_number) are unique-constrained across the whole table, not just per test.
        // Bounded to 5 digits so "DSC-#####" still fits SenderProfile.invoicePrefix's max-10 size.
        val unique = uniqueCounter.incrementAndGet()
        val sender = aSenderProfile {
            name = "Deep Space Commerce GmbH"
            invoicePrefix = "DSC-%05d".format(unique)
            email = Email.ofNullable("billing@deepspace.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            registerCourt = "Amtsgericht Berlin (Charlottenburg)"
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-LU-%05d".format(unique))
            name = "Bettel Consulting S.A."
            email = Email.ofNullable("ap@bettel-consulting.example")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE02100500000054540402")
        }
        val deRate = TaxRate().edit {
            name = "USt 19% (DE)"; rate = TaxRatePercent(BigDecimal("19.00")); country = Country.DE
            code = "IT-DE-STANDARD-19-$unique"; category = "S"; isSelectable = true; isActive = true
        }
        val luRate = TaxRate().edit {
            name = "TVA Luxembourg 17%"; rate = TaxRatePercent(BigDecimal("17.00")); country = Country.LU
            code = "IT-LU-STANDARD-17-$unique"; category = "S"; isSelectable = true; isActive = true
        }
        val article = anArticle {
            name = "Backend Development"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.HOUR
            defaultQuantity = BigDecimal.ONE
            defaultUnitPriceNet = Money.of("120.00")
            defaultTaxRateRef = deRate
        }

        em.persist(deRate)
        em.persist(luRate)
        em.persist(sender)
        em.persist(recipient)
        em.persist(article)

        sender.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        if (withLuVariant) {
            article.replaceTaxVariants(listOf(ArticleTaxVariant.of(article, Country.LU, luRate, today = FIXED_TODAY)), today = FIXED_TODAY)
        }

        val invoice = Invoice().edit {
            invoiceNumber = InvoiceNumber("ITLU-2026-08-%05d".format(unique))
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 8, 1)
            dueDate = LocalDate.of(2026, 8, 31)
        }
        val item = InvoiceItem().edit {
            taxRateRef = em.seededStandardTaxRate()
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = article.name
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.HOUR
            unitPriceNet = article.defaultUnitPriceNet
            articleRef = article
            applyTaxRule(deRate) // starts on the German default path, like a freshly added position
            sortOrder = 0
        }
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()

        return Fixture(
            invoiceId = invoice.id!!,
            recipientId = recipient.id!!,
            senderId = sender.id!!,
            articleWithVariantId = article.id!!,
            itemId = item.id!!,
            deRateCode = deRate.code!!,
            luRateCode = luRate.code!!,
        )
    }

    private fun headerCommand(
        fixture: Fixture,
        sellerVatRegistrationCountry: String?,
        invoiceFormat: String? = null,
    ) = UpdateInvoiceHeaderCommand(
        invoiceId = fixture.invoiceId,
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
        recipientId = fixture.recipientId,
        senderProfileId = fixture.senderId,
        paymentTermId = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry,
        invoiceFormat = invoiceFormat,
    )

    // ── 1. Happy path ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - choosing the LU seller VAT registration re-derives the item from the article's LU variant`() {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture(withLuVariant = true) }

        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, "LU"))

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, fixture.invoiceId)
            assertEquals(Country.LU, reloaded.sellerVatRegistrationCountry)
            // ACTIVATING a non-German-language registration (LU is not DE/AT/CH) preselects the
            // English document as a one-time default; a later explicit choice still wins (pinned
            // in InvoiceLifecycleServiceTest).
            assertEquals(InvoiceFormat.INTERNATIONAL, reloaded.invoiceFormat)

            val item = reloaded.itemList.single { it.id == fixture.itemId }
            assertNotEquals(fixture.deRateCode, item.taxRuleCode, "must not still be on the German rule")
            assertEquals(fixture.luRateCode, item.taxRuleCode)
            assertEquals(0, BigDecimal("17.00").compareTo(item.taxRateSnapshot?.value))
            assertEquals(fixture.articleWithVariantId, item.articleRef?.id)
        }
    }

    // ── 2. Blocking case — missing country variant ────────────────────────────────────────────

    @Test
    fun `blocking case - an article without an LU variant blocks the whole save, never falls back to Germany`() {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture(withLuVariant = false) }

        val ex = assertThrows(InvoiceValidationException::class.java) {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, "LU"))
        }
        assertEquals(InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING, ex.error)

        // Nothing partially saved: the transaction the blocked call ran in rolled back, so the
        // invoice still shows exactly the state persistFixture() left it in.
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, fixture.invoiceId)
            assertNull(reloaded.sellerVatRegistrationCountry, "seller VAT registration must not have been saved")
            val item = reloaded.itemList.single { it.id == fixture.itemId }
            assertEquals(fixture.deRateCode, item.taxRuleCode, "position must still carry the German rule, not a silently wrong LU one")
        }
    }

    // ── 3. Switching back to Germany ──────────────────────────────────────────────────────────

    @Test
    fun `switching back to Germany leaves the frozen LU snapshot in place and stales a captured R5 confirmation`() {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture(withLuVariant = true) }
        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, "LU"))

        // A confirmation captured while LU was active (TaxRuleResolutionRequest now carries
        // sellerVatRegistrationCountry precisely so this comparison is possible).
        val confirmedUnderLu = TaxRuleConfirmation(
            ruleCode = fixture.luRateCode,
            confirmedContext = TaxRuleResolutionRequest(
                serviceType = ServiceType.GENERAL_SERVICE,
                serviceDate = LocalDate.of(2026, 8, 1),
                sellerCountry = Country.DE,
                buyerCountry = Country.DE,
                customerType = CustomerType.BUSINESS,
                sellerVatId = "DE123456789",
                buyerVatId = "DE111111111",
                buyerVatIdVerificationStatus = null,
                sellerVatRegistrationCountry = Country.LU,
            ),
            confirmedAt = OffsetDateTime.now(),
        )

        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, null))

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, fixture.invoiceId)
            assertNull(reloaded.sellerVatRegistrationCountry)
            assertEquals(InvoiceFormat.GERMAN, reloaded.invoiceFormat)
            // The already-frozen LU snapshot from the happy-path save is left untouched — no
            // automatic re-run of the German tax-rule re-derivation path (out of scope here; that
            // path's own decision table stays unchanged). No LU position may be left hanging without reconfirmation, which
            // is exactly what isStaleFor below proves: the position still reads LU, and any
            // confirmation made under LU is now correctly stale, so a future caller re-confirming
            // this position cannot mistake it for still-valid.
            val item = reloaded.itemList.single { it.id == fixture.itemId }
            assertEquals(fixture.luRateCode, item.taxRuleCode)
        }

        val currentContextAfterSwitchBack = confirmedUnderLu.confirmedContext.copy(sellerVatRegistrationCountry = null)
        assertTrue(
            confirmedUnderLu.isStaleFor(currentContextAfterSwitchBack),
            "a confirmation captured under the LU registration must be stale once the invoice switched back to Germany",
        )
    }

    // ── 4. Invoice format choice ──────────────────────────────────────────────────────────────

    @Test
    fun `invoiceFormat is only choosable together with a non-German seller VAT registration and falls back on switch-back`() {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture(withLuVariant = true) }

        // Requesting INTERNATIONAL without a seller VAT registration is silently forced to
        // GERMAN — invoiceFormat is only changeable at all when sellerVatRegistrationCountry != null,
        // enforced server-side (InvoiceLifecycleService.updateHeader), not just by the UI.
        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, null, "INTERNATIONAL"))
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, fixture.invoiceId)
            assertEquals(InvoiceFormat.GERMAN, reloaded.invoiceFormat, "INTERNATIONAL must not stick without a seller VAT registration")
        }

        // Choosing LU + INTERNATIONAL together does take effect.
        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, "LU", "INTERNATIONAL"))
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, fixture.invoiceId)
            assertEquals(Country.LU, reloaded.sellerVatRegistrationCountry)
            assertEquals(InvoiceFormat.INTERNATIONAL, reloaded.invoiceFormat)
        }

        // Switching back to Germany forces invoiceFormat back to GERMAN automatically, even
        // without the caller explicitly asking for it (falls back to GERMAN).
        editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, null))
        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, fixture.invoiceId)
            assertNull(reloaded.sellerVatRegistrationCountry)
            assertEquals(InvoiceFormat.GERMAN, reloaded.invoiceFormat)
        }
    }

    // ── 5. Unregistered VAT country is rejected server-side (defense in depth) ───────────────

    @Test
    fun `choosing a country the sender has no registration for is rejected, not silently accepted`() {
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { persistFixture(withLuVariant = true) }

        // FR is a curated country but this sender only registered LU.
        assertThrows(InvalidParameterException::class.java) {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(fixture, "FR"))
        }
    }
}
