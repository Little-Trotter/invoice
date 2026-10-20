package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.PersistenceEmulation

class InvoiceValidationTests {

    private fun taxRate(rate: String): TaxRate = TaxRate().edit { this.rate = TaxRatePercent(BigDecimal(rate)) }

    /** B1 — a sender/recipient pair that actually passes their own validate(), so a B1 test
     *  reaches the invoice-totals check under test instead of failing on unrelated master data. */
    private fun validSender() = aSenderProfile {
        id = 1L
        name = "Sender GmbH"
        invoicePrefix = "B1-"
        email = Email.ofNullable("billing@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE02120300000000202051")
    }

    private fun validRecipient() = aRecipient {
        id = 2L
        customerNumber = CustomerNumber("B1-100")
        name = "Acme GmbH"
        email = Email.ofNullable("ap@acme.example")
        customerType = CustomerType.CONSUMER
    }

    @Test
    fun `validate fails when sender missing`() {
        val invoice = Invoice()
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00001")
            recipient = aRecipient { id = 1L }
        }
        // No senderProfile set
        invoice.addItem(anInvoiceItem(
            positionId = "1",
            description = "Item",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("10.00")),
            taxRateRef = taxRate("19.00"),
        ))
        invoice.recalculateTotals()
        assertThrows(InvoiceValidationException::class.java) { invoice.validate() }
    }

    // finding-fixes (2026-08-31, PO order) — validate(requireItems = false) is what InvoiceLifecycle
    // Service.save/updateHeader/setReverseChargeDecision call on every draft save, including the
    // very first header edit right after Invoice.create(), when zero items is the normal starting
    // state. The plain validate() (default requireItems = true) stays unchanged for every other
    // caller — GenerationJobService.createJob and DomainConstraints.validateInvoice must still
    // reject an item-less invoice (see GenerationJobServiceTest/DomainConstraintsTest).
    @Test
    fun `validate with requireItems false succeeds for a header with zero items`() {
        val invoice = Invoice()
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00005")
            senderProfile = validSender()
            recipient = validRecipient()
        }
        invoice.recalculateTotals()

        invoice.validate(requireItems = false)

        assert(invoice.itemList.isEmpty())
    }

    @Test
    fun `validate with the requireItems default still rejects a header with zero items`() {
        val invoice = Invoice()
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00006")
            senderProfile = validSender()
            recipient = validRecipient()
        }
        invoice.recalculateTotals()

        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.validate() }
        assert(ex.error == de.littletrotter.domain.exception.InvoiceValidationError.NO_ITEMS)
    }

    @Test
    fun `validate fails when totals mismatch`() {
        val invoice = Invoice()
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00002")
            senderProfile = aSenderProfile { id = 1L }
            recipient = aRecipient { id = 2L }
        }
        invoice.addItem(anInvoiceItem(
            positionId = "1",
            description = "Item",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("10.00")),
            taxRateRef = taxRate("19.00"),
        ))
        invoice.recalculateTotals()
        // tamper totals
        PersistenceEmulation.assignTotals(
            invoice,
            InvoiceTotals(Money.of(BigDecimal("1.00")), invoice.totals.vatAmount, invoice.totals.grossAmount),
        )
        assertThrows(InvoiceValidationException::class.java) { invoice.validate() }
    }

    // B1 — a discount/rebate position (negative unitPriceNet) is allowed on the item, but the
    // aggregated invoice totals must stay non-negative regardless of where the negative amount
    // comes from.
    @Test
    fun `validate succeeds when a discount item keeps invoice totals non-negative`() {
        val invoice = Invoice()
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00003")
            senderProfile = validSender()
            recipient = validRecipient()
        }
        invoice.addItem(anInvoiceItem(
            positionId = "1",
            description = "Service",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("20.00")),
            taxRateRef = taxRate("19.00"),
        ))
        invoice.addItem(anInvoiceItem(
            positionId = "2",
            description = "Rabatt 5 EUR",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("-5.00")),
            taxRateRef = taxRate("19.00"),
        ))
        invoice.recalculateTotals()

        invoice.validate()

        assert(invoice.totals.netAmount.amount.signum() > 0)
    }

    @Test
    fun `validate fails when a discount item pushes invoice totals negative`() {
        val invoice = Invoice()
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00004")
            senderProfile = validSender()
            recipient = validRecipient()
        }
        invoice.addItem(anInvoiceItem(
            positionId = "1",
            description = "Service",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("5.00")),
            taxRateRef = taxRate("19.00"),
        ))
        invoice.addItem(anInvoiceItem(
            positionId = "2",
            description = "Rabatt 20 EUR",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("-20.00")),
            taxRateRef = taxRate("19.00"),
        ))
        invoice.recalculateTotals()

        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.validate() }
        assert(
            ex.error == de.littletrotter.domain.exception.InvoiceValidationError.NET_AMOUNT_NEGATIVE ||
                ex.error == de.littletrotter.domain.exception.InvoiceValidationError.GROSS_AMOUNT_NEGATIVE
        )
    }

    // PO decision 2 (2026-09-02, sign logic) — a CORRECTED_INVOICE (cancellation, BT-3 = 384) is the
    // exact negative of the original it cancels and must NOT trip the general non-negative-totals
    // rule; every other type still must (pinned above and in InvoiceCalculationEdgeCasesTest).
    @Test
    fun `validate allows negative totals for a CORRECTED_INVOICE cancellation document`() {
        val invoice = anInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE)
        invoice.edit {
            invoiceNumber = InvoiceNumber("T-2026-04-00005")
            senderProfile = validSender()
            recipient = validRecipient()
        }
        PersistenceEmulation.whileEditable(invoice) {
            invoice.addItem(
                anInvoiceItem(
                    positionId = "1",
                    description = "Storno der Beratung",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = Money.of(BigDecimal("-100.00")),
                    taxRateRef = taxRate("19.00"),
                ),
            )
            invoice.recalculateTotals()
        }

        assert(invoice.totals.netAmount.isNegative())
        assert(invoice.totals.grossAmount.isNegative())
        invoice.validate()
    }
}
