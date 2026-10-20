package de.littletrotter.domain.invoice

import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.compliance.isTaxRelevantComplete
import de.littletrotter.domain.compliance.validateTaxRelevantCompleteness
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.PostalCode
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.application.invoice.toCompletenessCheckDto
import de.littletrotter.ui.view.invoice.InvoiceCompletenessCheck
import de.littletrotter.ui.view.invoice.taxRelevantCompletenessChecks
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.PersistenceEmulation

/**
 * Pins the completeness rules ([CoreCompletenessRules]) end to end through the detail view's
 * checklist: order, condition and the count of hard checks, evaluated on a real invoice and
 * mapped the way the detail query hands them to the UI.
 */
class InvoiceCompletenessTest {

    private fun Invoice.taxRelevantCompletenessChecks(): List<InvoiceCompletenessCheck> =
        CoreCompletenessRules.checks(this).map { it.toCompletenessCheckDto() }.taxRelevantCompletenessChecks()

    @Test
    fun `complete invoice passes tax completeness validation`() {
        val invoice = completeInvoice()

        assertTrue(invoice.isTaxRelevantComplete())
        assertTrue(CoreCompletenessRules.checks(invoice).filter { it.severity == ComplianceSeverity.ERROR }.all { it.isSatisfied })
        assertDoesNotThrow { invoice.validateTaxRelevantCompleteness() }
    }

    @Test
    fun `checklist exposes all default tax relevant checks`() {
        val invoice = completeInvoice()

        assertEquals(
            listOf(
                "invoiceDetail.summary.checklist.invoiceNumber",
                "invoiceDetail.summary.checklist.invoiceDate",
                "invoiceDetail.summary.checklist.serviceDate",
                "invoiceDetail.summary.checklist.servicePeriodXor",
                "invoiceDetail.summary.checklist.recipient",
                "invoiceDetail.summary.checklist.recipientEmail",
                "invoiceDetail.summary.checklist.recipientVatId",
                "invoiceDetail.summary.checklist.recipientAddress",
                "invoiceDetail.summary.checklist.recipientBillingAddress",
                "invoiceDetail.summary.checklist.recipientDeliveryAddress",
                "invoiceDetail.summary.checklist.sender",
                "invoiceDetail.summary.checklist.senderEmail",
                "invoiceDetail.summary.checklist.senderAddress",
                "invoiceDetail.summary.checklist.senderBillingAddress",
                "invoiceDetail.summary.checklist.senderIban",
                "invoiceDetail.summary.checklist.sellerTaxInfo",
                "invoiceDetail.summary.checklist.items",
                "invoiceDetail.summary.checklist.recipientVatIdValidated",
            ),
            invoice.taxRelevantCompletenessChecks().map { it.translationKey },
        )
    }

    @Test
    fun `missing customer address is reported`() {
        val invoice = completeInvoice().edit {
            deliveryAddress = null
            recipient = recipient!!.also { it.edit { addresses = emptyList() } }
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipientAddress" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `recipient billing address is used as fallback when delivery address is not set`() {
        val invoice = completeInvoice().edit {
            deliveryAddress = null
            recipient = recipient?.also {
                it.edit { addresses = emptyList() }
                it.designateBillingAddress(addressForRecipient(it, Address.AddressType.BILLING, "Billingweg 9"))
            }
        }

        val overallCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipientAddress" }
        val billingCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipientBillingAddress" }
        val deliveryCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipientDeliveryAddress" }

        assertTrue(overallCheck.isComplete)
        assertTrue(billingCheck.isComplete)
        assertFalse(deliveryCheck.isComplete)
    }

    @Test
    fun `recipient delivery address check is false when only billing address exists`() {
        val invoice = completeInvoice().edit {
            deliveryAddress = null
            recipient = recipient?.also {
                it.edit { addresses = emptyList() }
                it.designateBillingAddress(addressForRecipient(it, Address.AddressType.BILLING, "Billingweg 9"))
            }
        }

        val deliveryCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipientDeliveryAddress" }

        assertFalse(deliveryCheck.isComplete)
    }

    @Test
    fun `recipient master data delivery address is used when explicit invoice delivery address is missing`() {
        val invoice = completeInvoice().edit {
            deliveryAddress = null
            recipient = recipient!!.also {
                val existingDelivery = it.deliveryAddress
                it.edit { addresses = emptyList() }
                it.addDeliveryAddress(existingDelivery!!)
            }
        }

        val deliveryCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipientDeliveryAddress" }

        assertTrue(deliveryCheck.isComplete)
    }

    @Test
    fun `invoice billing address takes precedence over sender master data address`() {
        val invoice = completeInvoice().edit {
            billingAddress = addressForSender(senderProfile!!, street = "Rechnungsweg 5")
            senderProfile = senderProfile!!.also { it.designateBillingAddress(addressForSender(it, street = "Stammdatenweg 7")) }
        }

        val overallCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderAddress" }
        val billingCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderBillingAddress" }

        assertTrue(overallCheck.isComplete)
        assertTrue(billingCheck.isComplete)
    }

    @Test
    fun `sender address remains complete when only billing address exists`() {
        val invoice = completeInvoice().edit {
            senderProfile = senderProfile!!.also {
                it.edit { addresses = emptyList() }
                it.designateBillingAddress(addressForSender(it, street = "Rechnungsweg 5"))
            }
        }

        val addressCheck = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderAddress" }
        val keys = invoice.taxRelevantCompletenessChecks().map { it.translationKey }

        assertTrue(addressCheck.isComplete)
        assertFalse(keys.contains("invoiceDetail.summary.checklist.senderDeliveryAddress"))
    }

    @Test
    fun `sender delivery address no longer has dedicated completeness check`() {
        val invoice = completeInvoice().edit {
            senderProfile = senderProfile!!.also {
                it.addDeliveryAddress(Address.forSender(it, Address.AddressType.DELIVERY, "Lieferweg 3", "50667", "Koeln", Country.DE, false))
            }
        }

        val keys = invoice.taxRelevantCompletenessChecks().map { it.translationKey }

        assertFalse(keys.contains("invoiceDetail.summary.checklist.senderDeliveryAddress"))
    }

    @Test
    fun `missing provider tax information is reported`() {
        val invoice = completeInvoice().edit {
            senderProfile = senderProfile!!.also {
                it.edit {
                    taxNumber = null
                    vatId = null
                }
            }
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sellerTaxInfo" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `selected seller is used as effective provider`() {
        val selected = aSenderProfile {
            name = "Ausgewaehlter Anbieter"
            invoicePrefix = "SEL"
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE112233445")
            email = Email("seller@example.com")
        }.also { it.designateBillingAddress(addressForSender(it, "Sellerweg 8")) }

        val invoice = completeInvoice().edit {
            senderProfile = null
            billingAddress = null
        }.apply {
            PersistenceEmulation.assignSelectedSeller(this, selected)
        }

        val checks = invoice.taxRelevantCompletenessChecks().associateBy { it.translationKey }

        assertTrue(checks.getValue("invoiceDetail.summary.checklist.sender").isComplete)
        assertTrue(checks.getValue("invoiceDetail.summary.checklist.senderAddress").isComplete)
        assertTrue(checks.getValue("invoiceDetail.summary.checklist.sellerTaxInfo").isComplete)
    }

    @Test
    fun `vat id alone satisfies provider tax information`() {
        val invoice = completeInvoice().edit {
            senderProfile = senderProfile!!.also {
                it.edit {
                    taxNumber = null
                    vatId = VatId("DE123456789")
                }
            }
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sellerTaxInfo" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `reverse charge adds recipient vat id check`() {
        val invoice = completeInvoice().edit {
            reverseCharge = true
            recipient = recipient!!.also { it.edit { vatId = null } }
        }

        val checks = invoice.taxRelevantCompletenessChecks()

        assertTrue(checks.any { it.translationKey == "invoiceDetail.summary.checklist.reverseChargeVatId" })
        assertFalse(checks.single { it.translationKey == "invoiceDetail.summary.checklist.reverseChargeVatId" }.isComplete)
    }

    @Test
    fun `reverse charge passes when recipient vat id is present`() {
        val invoice = completeInvoice().edit { reverseCharge = true }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.reverseChargeVatId" }

        assertTrue(check.isComplete)
    }

    @Test
    fun `reverse charge fails for blank recipient vat id`() {
        val invoice = completeInvoice().edit {
            reverseCharge = true
            recipient = recipient!!.also { it.edit { vatId = null } }
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.reverseChargeVatId" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `reverse charge fails when recipient is missing`() {
        val invoice = completeInvoice().edit {
            reverseCharge = true
            recipient = null
            deliveryAddress = null
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.reverseChargeVatId" }

        assertFalse(check.isComplete)
    }

    // ── CoreCompletenessRules.BUYER_VAT_ID_VALIDATED ─────────────────────

    @Test
    fun `buyer vat id validated check is absent when the recipient has no vat id`() {
        val invoice = completeInvoice().edit { recipient = recipient!!.also { it.edit { vatId = null } } }

        val checks = CoreCompletenessRules.checks(invoice)

        assertTrue(checks.none { it.ruleId == CoreCompletenessRules.BUYER_VAT_ID_VALIDATED })
    }

    @Test
    fun `buyer vat id validated check passes once the recipient's vat id is VERIFIED`() {
        val invoice = completeInvoice().edit { recipient = recipient!!.also { it.edit { vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED } } }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.BUYER_VAT_ID_VALIDATED }

        assertTrue(check.isSatisfied)
        assertEquals(ComplianceSeverity.WARNING, check.severity)
    }

    @Test
    fun `buyer vat id validated check fails as a non-blocking WARNING while UNVERIFIED`() {
        // completeInvoice()'s recipient has a vatId but no vatIdVerificationStatus (null, the
        // domain default for "never checked").
        val invoice = completeInvoice()

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.BUYER_VAT_ID_VALIDATED }

        assertFalse(check.isSatisfied)
        assertEquals(ComplianceSeverity.WARNING, check.severity)
        assertTrue(invoice.isTaxRelevantComplete(), "an unvalidated VAT id must never block a B2C-style invoice")
    }

    @Test
    fun `buyer vat id validated check fails the same way for a FAILED automated verification`() {
        val invoice = completeInvoice().edit { recipient = recipient!!.also { it.edit { vatIdVerificationStatus = VatIdVerificationStatus.FAILED } } }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.BUYER_VAT_ID_VALIDATED }

        assertFalse(check.isSatisfied)
        assertEquals(ComplianceSeverity.WARNING, check.severity)
    }

    @Test
    fun `reverse charge raises the buyer vat id validated check to ERROR and blocks finalization`() {
        val invoice = completeInvoice().edit { reverseCharge = true }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.BUYER_VAT_ID_VALIDATED }

        assertFalse(check.isSatisfied)
        assertEquals(ComplianceSeverity.ERROR, check.severity)
        assertFalse(invoice.isTaxRelevantComplete(), "reverse charge legally depends on a validated buyer VAT id")
    }

    @Test
    fun `reverse charge passes the buyer vat id validated check once VERIFIED`() {
        val invoice = completeInvoice().edit {
            reverseCharge = true
            recipient = recipient!!.also { it.edit { vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.BUYER_VAT_ID_VALIDATED }

        assertTrue(check.isSatisfied)
        assertEquals(ComplianceSeverity.ERROR, check.severity, "severity still escalates under reverse charge, satisfied or not")
        assertTrue(invoice.isTaxRelevantComplete())
    }

    @Test
    fun `cancellation document adds original invoice number check`() {
        val invoice = completeInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE, originalInvoiceNumber = null)

        val checks = invoice.taxRelevantCompletenessChecks()

        assertTrue(checks.any { it.translationKey == "invoiceDetail.summary.checklist.originalInvoiceNumber" })
        assertFalse(checks.single { it.translationKey == "invoiceDetail.summary.checklist.originalInvoiceNumber" }.isComplete)
    }

    @Test
    fun `cancellation document passes when original invoice number is present`() {
        val invoice = completeInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE, originalInvoiceNumber = "LTI-2025-0099")

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.originalInvoiceNumber" }

        assertTrue(check.isComplete)
    }

    @Test
    fun `cancellation document fails for blank original invoice number`() {
        val invoice = completeInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE, originalInvoiceNumber = "")

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.originalInvoiceNumber" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `validation throws first failing completeness message`() {
        val invoice = completeInvoice(invoiceNumber = InvoiceNumber.UNASSIGNED).edit {
            senderProfile = senderProfile!!.also {
                it.edit {
                    taxNumber = null
                    vatId = null
                }
            }
        }

        val ex = assertThrows<InvoiceValidationException> {
            invoice.validateTaxRelevantCompleteness()
        }

        assertEquals(InvoiceValidationError.INVOICE_NUMBER_MISSING, ex.error)
    }

    @Test
    fun `an incomplete invoice reports itself as not tax relevant complete`() {
        val invoice = completeInvoice(invoiceNumber = InvoiceNumber.UNASSIGNED).edit {
            senderProfile = senderProfile!!.also {
                it.edit {
                    taxNumber = null
                    vatId = null
                }
            }
        }

        assertFalse(invoice.isTaxRelevantComplete())
    }

    @Test
    fun `missing service period still passes when invoice date exists`() {
        val invoice = completeInvoice().edit {
            servicePeriodFrom = null
            servicePeriodTo = null
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.serviceDate" }

        assertTrue(check.isComplete)
    }

    // date invariants — invoiceDate is no longer nullable, so "neither invoice
    // date nor service period exists" is now structurally unreachable: SERVICE_DATE's own
    // satisfied condition (CoreCompletenessRules.kt) already accepts a bare invoiceDate as a
    // stand-in, and that is now always present. This check is therefore a vacuous pass from here
    // on — pinned below instead of deleted, so a future change that narrows the condition again
    // has a test to update. CORE.SERVICE_PERIOD_XOR (the stricter sibling rule, not tested here)
    // still can fail on the exact same input.
    @Test
    fun `service date check always passes now that invoiceDate can never be absent`() {
        val invoice = completeInvoice().edit {
            servicePeriodFrom = null
            servicePeriodTo = null
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.serviceDate" }

        assertTrue(check.isComplete)
    }

    @Test
    fun `service date check passes when only service period end exists`() {
        val invoice = completeInvoice().edit { servicePeriodFrom = null }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.serviceDate" }

        assertTrue(check.isComplete)
    }

    // ── Nekar-Rechnungsrichtlinie Ziff. 1.3 — CoreCompletenessRules.SERVICE_PERIOD_XOR ──
    // "Leistungsdatum-XOR": at least one of BT-72 (delivery date), a complete header service
    // period (BT-73 AND BT-74), or a line period (BT-134/135, either endpoint) on every single
    // line must be set. Stricter than the pre-existing SERVICE_DATE check above (which also
    // accepts a bare invoiceDate and never looks at line periods) — a separate ruleId, so it
    // does not change SERVICE_DATE's own documented behavior.

    @Test
    fun `service period XOR is satisfied by the delivery date alone`() {
        val invoice = completeInvoice().edit {
            servicePeriodFrom = null
            servicePeriodTo = null
            deliveryDate = LocalDate.of(2026, 4, 3)
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = null; servicePeriodTo = null } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertTrue(check.isSatisfied)
        assertEquals(ComplianceSeverity.ERROR, check.severity)
    }

    @Test
    fun `service period XOR is satisfied by a complete header service period alone`() {
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = LocalDate.of(2026, 4, 1)
            servicePeriodTo = LocalDate.of(2026, 4, 5)
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = null; servicePeriodTo = null } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertTrue(check.isSatisfied)
    }

    @Test
    fun `service period XOR - a header period with only the end date is one-sided too`() {
        // Mirror of the BT-73-only case below: only BT-74 set — still not a complete pair.
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = null
            servicePeriodTo = LocalDate.of(2026, 4, 5)
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = null; servicePeriodTo = null } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertFalse(check.isSatisfied)
    }

    @Test
    fun `service period XOR - a line period consisting of only an end date still counts for its line`() {
        // The line-level alternative accepts either endpoint (BT-134 OR BT-135) — unlike the
        // header pair; covers the it-servicePeriodTo branch of the all-lines check.
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = null
            servicePeriodTo = null
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = null; servicePeriodTo = LocalDate.of(2026, 4, 4) } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertTrue(check.isSatisfied)
    }

    @Test
    fun `service period XOR is not satisfied by a one-sided header service period alone`() {
        // Only BT-73, no BT-74 — the directive treats the header period as a pair, not two
        // independently sufficient dates (unlike the line-level alternative, see below).
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = LocalDate.of(2026, 4, 1)
            servicePeriodTo = null
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = null; servicePeriodTo = null } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertFalse(check.isSatisfied)
    }

    @Test
    fun `service period XOR is satisfied when every line carries a line period`() {
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = null
            servicePeriodTo = null
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = LocalDate.of(2026, 4, 2) } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertTrue(check.isSatisfied)
    }

    @Test
    fun `service period XOR fails when none of the three alternatives is set`() {
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = null
            servicePeriodTo = null
        }.apply {
            itemList.forEach { it.edit { servicePeriodFrom = null; servicePeriodTo = null } }
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertFalse(check.isSatisfied)
        assertFalse(invoice.isTaxRelevantComplete(), "an ERROR-severity check must block completeness")
    }

    @Test
    fun `service period XOR does not vacuously pass an invoice with no items via the line-period alternative`() {
        val invoice = completeInvoice().edit {
            deliveryDate = null
            servicePeriodFrom = null
            servicePeriodTo = null
        }.apply {
            itemList.asMutableForTest().clear()
            recalculateTotals()
        }

        val check = CoreCompletenessRules.checks(invoice).single { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR }

        assertFalse(check.isSatisfied, "`all {}` on an empty item list must never be read as 'every line has a period'")
    }

    @Test
    fun `recipient check fails when recipient is missing`() {
        val invoice = completeInvoice().edit {
            recipient = null
            deliveryAddress = null
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipient" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `recipient check fails for blank name`() {
        val invoice = completeInvoice().edit { recipient = recipient!!.also { it.edit { name = "" } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipient" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `recipient check fails for blank customer number`() {
        val invoice = completeInvoice().edit { recipient = recipient!!.also { it.edit { customerNumber = null } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.recipient" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender check fails for blank name`() {
        val invoice = completeInvoice().edit { senderProfile = senderProfile!!.also { it.edit { name = "" } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sender" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender check fails for blank invoice prefix`() {
        val invoice = completeInvoice().edit { senderProfile = senderProfile!!.also { it.edit { invoicePrefix = "" } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sender" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender check fails when provider is missing entirely`() {
        val invoice = completeInvoice().edit {
            senderProfile = null
            billingAddress = null
        }.apply {
            PersistenceEmulation.assignSelectedSeller(this, null)
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sender" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender tax info fails when provider is missing entirely`() {
        val invoice = completeInvoice().edit {
            senderProfile = null
            billingAddress = null
        }.apply {
            PersistenceEmulation.assignSelectedSeller(this, null)
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sellerTaxInfo" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender address check fails for blank city`() {
        val invoice = completeInvoice().edit { billingAddress = addressForSender(senderProfile!!).also { it.edit { city = "" } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderAddress" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender address check fails for blank postal code`() {
        val invoice = completeInvoice().edit { billingAddress = addressForSender(senderProfile!!).also { it.edit { postalCode = PostalCode("") } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderAddress" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `sender address check fails for blank street`() {
        val invoice = completeInvoice().edit { billingAddress = addressForSender(senderProfile!!).also { it.edit { street = "" } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderAddress" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `seller tax info fails for blank tax identifiers`() {
        val invoice = completeInvoice().edit {
            senderProfile = senderProfile!!.also {
                it.edit {
                    taxNumber = null
                    vatId = null
                }
            }
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.sellerTaxInfo" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `senderIban check fails when sender has no iban`() {
        val invoice = completeInvoice().edit { senderProfile = senderProfile!!.also { it.edit { iban = null } } }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderIban" }

        assertFalse(check.isComplete)
    }

    @Test
    fun `senderIban check passes when sender iban is set`() {
        val invoice = completeInvoice()

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.senderIban" }

        assertTrue(check.isComplete)
    }

    @Test
    fun `items check fails when invoice has no positions`() {
        val invoice = completeInvoice().apply {
            itemList.asMutableForTest().clear()
            recalculateTotals()
        }

        val check = invoice.taxRelevantCompletenessChecks().single { it.translationKey == "invoiceDetail.summary.checklist.items" }

        assertFalse(check.isComplete)
    }

    private fun completeInvoice(
        invoiceNumber: InvoiceNumber = InvoiceNumber("LTI-2026-0001"),
        typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
        originalInvoiceNumber: String? = null,
    ): Invoice {
        val sender = aSenderProfile {
            name = "Muster Anbieter GmbH"
            invoicePrefix = "LTI"
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            email = Email("sender@example.com")
            iban = Iban("DE89370400440532013000")
        }
        val recipient = aRecipient {
            name = "Musterkunde AG"
            customerNumber = CustomerNumber("KD-1000")
            vatId = VatId("DE998877665")
            email = Email("kunde@example.com")
        }
        val senderAddress = addressForSender(sender)
        val deliveryAddress = addressForRecipient(recipient, Address.AddressType.DELIVERY, "Kundenweg 12")
        sender.designateBillingAddress(senderAddress)
        recipient.addDeliveryAddress(deliveryAddress)

        return anInvoice(
            invoiceNumber = invoiceNumber,
            typeCode = typeCode,
            originalInvoiceNumber = originalInvoiceNumber,
            invoiceDate = LocalDate.of(2026, 4, 6),
            servicePeriodFrom = LocalDate.of(2026, 4, 1),
            servicePeriodTo = LocalDate.of(2026, 4, 5),
            senderProfile = sender,
            recipient = recipient,
            billingAddress = senderAddress,
            deliveryAddress = deliveryAddress,
            items = listOf(
                anInvoiceItem(
                    positionId = "1",
                    description = "Beratung",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = Money.of("100.00"),
                    taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) },
                ),
            ),
            recalculate = true,
        )
    }

    private fun addressForSender(sender: SenderProfile, street: String = "Anbieterweg 1"): Address =
        Address.forSender(sender, Address.AddressType.BILLING, street, "50667", "Koeln", Country.DE, true)

    private fun addressForRecipient(recipient: Recipient, type: Address.AddressType, street: String): Address =
        Address.forRecipient(recipient, type, street, "50668", "Koeln", Country.DE, type == Address.AddressType.BILLING)
}
