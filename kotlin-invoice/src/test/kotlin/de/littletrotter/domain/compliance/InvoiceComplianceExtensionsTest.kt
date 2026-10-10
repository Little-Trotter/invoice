package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.asMutableForTest

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

class InvoiceComplianceExtensionsTest {

    private fun minimalInvoice(): Invoice {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-001")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
        }
        return Invoice().apply {
            invoiceNumber = InvoiceNumber("RE-2026-01-00001")
            senderProfile = sender
            this.recipient = recipient
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
            itemList.asMutableForTest().add(
                InvoiceItem().apply {
                    positionId = "1"
                    description = "Beratung"
                    quantity = BigDecimal("1.00")
                    unitPriceNet = Money(BigDecimal("100.00"))
                    netAmount = Money(BigDecimal("100.00"))
                    vatAmount = Money(BigDecimal("19.00"))
                    grossAmount = Money(BigDecimal("119.00"))
                    taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
                }.also { it.invoice = this },
            )
        }
    }

    // Every other call site (GenerationJobService.mergeBusinessStage, FinalizationPolicy)
    // passes ruleSets explicitly; this is the only place the default (ComplianceRuleSets.DEFAULT)
    // parameter itself is exercised.
    @Test
    fun `evaluateCompliance with no ruleSets argument defaults to ComplianceRuleSets DEFAULT`() {
        val findings = minimalInvoice().evaluateCompliance(today = FIXED_TODAY)

        assertTrue(findings.isNotEmpty(), "an incomplete invoice must surface at least one finding under the default rule sets")
    }
}
