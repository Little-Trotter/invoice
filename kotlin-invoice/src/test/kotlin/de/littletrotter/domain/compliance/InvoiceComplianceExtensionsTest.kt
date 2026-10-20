package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.anInvoice

class InvoiceComplianceExtensionsTest {

    private fun minimalInvoice(): Invoice {
        val sender = aSenderProfile {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-001")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
        }
        return anInvoice(
            invoiceNumber = InvoiceNumber("RE-2026-01-00001"),
            senderProfile = sender,
            recipient = recipient,
            items = listOf(
                anInvoiceItem(
                    positionId = "1",
                    description = "Beratung",
                    quantity = BigDecimal("1.00"),
                    unitPriceNet = Money(BigDecimal("100.00")),
                    taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) },
                ),
            ),
            recalculate = true,
        )
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
