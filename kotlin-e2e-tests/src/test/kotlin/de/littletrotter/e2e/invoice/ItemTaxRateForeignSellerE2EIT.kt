package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * finding-fixes (2026-09-01) — [de.littletrotter.ui.view.invoice.BaseItemDialog] built, validated
 * and saved from a `taxGroup` `ComboBox<TaxRate>`, but never added it to the dialog's component
 * tree (`buildFormLayout` used to take only the buyer-item-number field). For a foreign seller VAT
 * registration ([Invoice.sellerVatRegistrationCountry] non-null) and a NEW position, `taxGroup`'s
 * initial value is `null` (`createTaxRateComboBox`'s `else -> null` branch — only the German tax-rule
 * path and the reverse-charge lock pre-select anything) and, with the field invisible, no user
 * interaction could ever change that: the save path's own guard (`taxGroup.value == null` for a
 * foreign seller) then rejected *every* such position with "a tax rate is required" for a control
 * that was not on screen — a foreign-seller goods/service position could not be created at all,
 * regardless of what the user intended to pick. Fixed in commit 72d70e07a: `buildFormLayout(
 * buyerItemNumberField, taxGroup)` now takes `taxGroup` as a parameter, and both `GoodsItemDialog`/
 * `ServiceItemDialog` place it in their layout, so a real user can finally select a value.
 *
 * `BaseItemDialog`/`GoodsItemDialog`/`ServiceItemDialog` are `internal` to `kotlin-invoice` and not
 * visible from this module, so this suite exercises the fix one layer down, at the real save path
 * the dialog's "Save" button ultimately calls — [InvoiceLifecycleService.addItem] (specifically its
 * `applyItemTaxAndEntries` PRODUCT/SERVICE branch): "does this land a value in `command.taxRateId`
 * actually save" is exactly what was previously unreachable for a foreign seller. The border cases
 * cover the two UI-level defaults the fix must not have disturbed ([TaxRateDto.isDefault] for a German
 * seller, [reverseChargeTaxRate] for the reverse-charge lock — both real, non-internal production
 * functions/flags, invoked/read directly rather than re-implemented) plus the one case that must
 * still be rejected: no tax rate at all.
 *
 * Uses the real baseline-seeded [TaxRate] rows (`V2__baseline_seed.sql`) throughout — `LU_STANDARD_17
 * `/`LU_REDUCED_8` for the foreign-seller happy paths, `DE_STANDARD_19` (the sole `is_default = TRUE`
 * row) for the German-seller border case, `EU_B2B_RC_SERVICE` for the reverse-charge lock — instead
 * of inserting fixture `TaxRate` rows: the previous attempt at this suite built a fresh `AE`-category
 * `TaxRate` without an exemption reason and hit `chk_tax_rate_ae_reason`
 * (`category <> 'AE' OR exemption_reason_code IS NOT DISTINCT FROM 'VATEX-EU-AE'`, V1 baseline
 * schema); reading the real seed row sidesteps that whole class of mistake. Every `Invoice`/
 * `SenderProfile`/`Recipient` row this suite inserts is tagged and removed again in [cleanup];
 * reading [Invoice.totals] happens inside the same [QuarkusTransaction.requiringNew] that loaded the
 * entity (the previous attempt's other failure mode: touching the entity graph outside an active
 * session threw `LazyInitializationException`).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ItemTaxRateForeignSellerE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    private val createdInvoiceIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
        }
    }

    private fun taxRateByCode(code: String): TaxRateDto =
        taxRateQueries.findAllTaxRates().find { it.code == code }
            ?: error("expected seeded TaxRate '$code' (V2__baseline_seed.sql) to exist")

    /** A bare sender/recipient invoice, [tag]-marked, with no items yet — mirrors
     *  [RecalculationSweepMalformedRowE2EIT]'s own fixture shape. */
    private fun freshInvoice(tag: String, sellerVatRegistrationCountry: Country?, reverseCharge: Boolean = false): Long {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            em.flush()
            Pair(sender.id!!, recipient.id!!)
        }
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        if (sellerVatRegistrationCountry != null || reverseCharge) {
            QuarkusTransaction.requiringNew().run {
                val invoice = em.find(Invoice::class.java, invoiceId)!!
                invoice.sellerVatRegistrationCountry = sellerVatRegistrationCountry
                invoice.reverseCharge = reverseCharge
            }
        }
        createdInvoiceIds += invoiceId
        return invoiceId
    }

    private fun reloadTotals(invoiceId: Long): Triple<Money, Money, Money> =
        QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            Triple(invoice.totals.netAmount, invoice.totals.vatAmount, invoice.totals.grossAmount)
        }

    // ── Happy path — a foreign seller VAT registration with a chosen tax rate saves ─────────────

    @Test
    fun `a foreign seller country goods item with a chosen tax rate saves with the expected taxRateId and recomputed amounts`() {
        val invoiceId = freshInvoice("ITR1G", sellerVatRegistrationCountry = Country.LU)
        val rate = taxRateByCode("LU_STANDARD_17")

        val saved = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.PRODUCT,
                    description = "ITR1G Ware",
                    quantity = java.math.BigDecimal("2"),
                    unit = InvoiceItemUnit.PIECE,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = rate.id,
                ),
            )
        }

        val item = saved.itemList.single()
        assertEquals(rate.id, item.taxRateRef?.id, "the position must carry exactly the tax rate the (now visible) ComboBox supplied")
        assertEquals(Money.of("200.00"), item.netAmount)
        assertEquals(Money.of("34.00"), item.vatAmount, "17 % of 200.00")
        assertEquals(Money.of("234.00"), item.grossAmount)

        val (net, vat, gross) = reloadTotals(invoiceId)
        assertEquals(Money.of("200.00"), net)
        assertEquals(Money.of("34.00"), vat)
        assertEquals(Money.of("234.00"), gross)
    }

    @Test
    fun `a foreign seller country service item with a chosen tax rate saves with the expected taxRateId and recomputed amounts`() {
        val invoiceId = freshInvoice("ITR1S", sellerVatRegistrationCountry = Country.LU)
        val rate = taxRateByCode("LU_REDUCED_8")

        val saved = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "ITR1S Beratung",
                    quantity = java.math.BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = rate.id,
                ),
            )
        }

        val item = saved.itemList.single()
        assertEquals(rate.id, item.taxRateRef?.id)
        assertEquals(Money.of("50.00"), item.netAmount)
        assertEquals(Money.of("4.00"), item.vatAmount, "8 % of 50.00")
        assertEquals(Money.of("54.00"), item.grossAmount)
    }

    // ── Border — the pre-existing German-seller default must survive the fix unchanged ─────────

    @Test
    fun `a German seller item still resolves the default tax rate the dialog falls back to, and saves correctly`() {
        // sellerVatRegistrationCountry stays null: the German tax-rule path BaseItemDialog.defaultTaxRate()
        // reads (TaxRate.isDefault, DE_STANDARD_19 in the real seed) is untouched by this fix -
        // it is a different branch of createTaxRateComboBox entirely.
        val invoiceId = freshInvoice("ITR2", sellerVatRegistrationCountry = null)
        val defaultRate = taxRateQueries.findAllTaxRates().find { it.isDefault }
        assertNotNull(defaultRate, "V2__baseline_seed.sql seeds exactly one is_default = TRUE tax_rate row")
        assertEquals("DE_STANDARD_19", defaultRate!!.code)

        val saved = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "ITR2 Beratung",
                    quantity = java.math.BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = defaultRate.id,
                ),
            )
        }

        val item = saved.itemList.single()
        assertEquals(defaultRate.id, item.taxRateRef?.id)
        assertEquals(Money.of("19.00"), item.vatAmount)
    }

    // ── Border — a reverse-charge-locked invoice keeps the locked rate, and it still saves ──────

    @Test
    fun `a reverse-charge-locked invoice's item saves with the same locked reverse-charge tax rate`() {
        // reverseChargeLocked in BaseItemDialog is `sellerCountry == null && invoice.reverseCharge`
        // - the lock only ever applies to the German/no-seller-VAT-registration path.
        val invoiceId = freshInvoice("ITR3", sellerVatRegistrationCountry = null, reverseCharge = true)
        val lockedRate = taxRateQueries.findAllTaxRates().find { it.categoryEnum == VatCategory.REVERSE_CHARGE }
        assertNotNull(lockedRate, "V2__baseline_seed.sql seeds exactly one REVERSE_CHARGE (AE) tax_rate row")
        assertEquals("EU_B2B_RC_SERVICE", lockedRate!!.code)
        assertEquals(Money.ZERO, Money.of(lockedRate.rate.value), "AE is a zero-rate category by construction (chk_tax_rate_zero_rate_categories)")

        val saved = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "ITR3 Beratung (Reverse Charge)",
                    quantity = java.math.BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("500.00"),
                    taxRateId = lockedRate.id,
                ),
            )
        }

        val item = saved.itemList.single()
        assertEquals(lockedRate.id, item.taxRateRef?.id, "the locked rate must be exactly what the position saves with")
        assertEquals(Money.of("500.00"), item.netAmount)
        assertEquals(Money.ZERO, item.vatAmount)
        assertEquals(Money.of("500.00"), item.grossAmount)
    }

    // ── Border — no tax rate at all is still rejected, with the real validation message ─────────

    @Test
    fun `saving a foreign seller country item with no tax rate selected is rejected with the proper message`() {
        val invoiceId = freshInvoice("ITR4", sellerVatRegistrationCountry = Country.LU)

        val ex = assertThrows(InvalidParameterException::class.java) {
            QuarkusTransaction.requiringNew().run {
                itemMutationService.addItem(
                    invoiceId,
                    InvoiceItemCommand(
                        category = InvoiceItemCategory.PRODUCT,
                        description = "ITR4 Ware ohne Steuersatz",
                        quantity = java.math.BigDecimal("1"),
                        unit = InvoiceItemUnit.PIECE,
                        unitPriceNet = Money.of("10.00"),
                        taxRateId = null,
                    ),
                )
            }
        }
        assertEquals("Tax rate is required for this position.", ex.message)
    }
}
