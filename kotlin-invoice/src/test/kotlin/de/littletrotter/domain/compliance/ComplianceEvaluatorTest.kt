package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * The rule engine itself: that it picks the right rule sets per level, that a finding knows its
 * subject, and that the same rule yields the same `ruleId` at the master record and on the
 * invoice.
 *
 * The *content* of individual rule sets is covered by their own tests ([PeriodConsistencyRulesTest],
 * [UnitConsistencyRulesTest], `InvoiceCompletenessTest` for completeness). This file is only
 * about the mechanics above that — otherwise the same statement would exist twice and drift.
 */
class ComplianceEvaluatorTest {

    // ─── Level dispatch ──────────────────────────────────────────────────────────

    @Nested
    inner class LevelDispatch {

        private val everywhere = recordingRuleSet("ALL", ComplianceLevel.entries.toSet())

        @Test
        fun `a rule set only sees the levels it declares`() {
            val invoiceOnly = recordingRuleSet("INVOICE_ONLY", setOf(ComplianceLevel.INVOICE))

            ComplianceEvaluator.evaluate(ComplianceSubject.OfSeller(SenderProfile()), FIXED_TODAY, listOf(invoiceOnly))

            assertTrue(invoiceOnly.seen.isEmpty(), "ein Regelsatz für Rechnungen darf keinen Anbieter zu sehen bekommen")
        }

        @Test
        fun `every level reaches a rule set that declares it`() {
            val subjects = listOf(
                ComplianceSubject.OfSeller(SenderProfile()),
                ComplianceSubject.OfBuyer(Recipient()),
                ComplianceSubject.OfArticle(de.littletrotter.domain.masterdata.Article()),
                ComplianceSubject.OfInvoice(Invoice()),
            )
            subjects.forEach { ComplianceEvaluator.evaluate(it, FIXED_TODAY, listOf(everywhere)) }

            assertEquals(ComplianceLevel.entries.toSet(), everywhere.seen.map { it.level }.toSet())
        }

        @Test
        fun `findings keep the order of the rule sets`() {
            val first = constantRuleSet("A", "RULE.A")
            val second = constantRuleSet("B", "RULE.B")

            val ids = ComplianceEvaluator.evaluate(ComplianceSubject.OfInvoice(Invoice()), FIXED_TODAY, listOf(first, second))
                .map { it.ruleId }

            assertEquals(listOf("RULE.A", "RULE.B"), ids)
        }

        @Test
        fun `the built-in registry covers all four levels`() {
            val covered = ComplianceRuleSets.DEFAULT.flatMap { it.levels }.toSet()
            assertTrue(ComplianceLevel.SELLER in covered)
            assertTrue(ComplianceLevel.BUYER in covered)
            assertTrue(ComplianceLevel.INVOICE in covered)
        }

        /** Articles don't have their own rule set yet. This test pins down that it's a known
         *  state, not an overlooked case: an empty result here is correct, not a bug. */
        @Test
        fun `an article currently yields no findings from the built-in registry`() {
            val findings = ComplianceEvaluator.evaluate(
                ComplianceSubject.OfArticle(de.littletrotter.domain.masterdata.Article()), today = FIXED_TODAY,
            )
            assertTrue(findings.isEmpty())
        }
    }

    // ─── Same rule at two levels ──────────────────────────────────────

    @Nested
    inner class SameRuleAcrossLevels {

        @Test
        fun `a seller without a VAT id is reported at the master record and on the invoice`() {
            val incomplete = SenderProfile().apply { name = "Anbieter"; invoicePrefix = "AN-" }

            val atMasterData = ComplianceEvaluator.evaluate(ComplianceSubject.OfSeller(incomplete), today = FIXED_TODAY)
                .map { it.ruleId }
            val onInvoice = ComplianceEvaluator.evaluate(invoiceWith(seller = incomplete), today = FIXED_TODAY)
                .map { it.ruleId }

            assertTrue(SellerDataRules.TAX_INFO in atMasterData, atMasterData.toString())
            assertTrue(SellerDataRules.TAX_INFO in onInvoice, onInvoice.toString())
        }

        @Test
        fun `a seller finding is targeted at the seller, not at the invoice header`() {
            val finding = ComplianceEvaluator.evaluate(invoiceWith(seller = SenderProfile()), today = FIXED_TODAY)
                .first { it.ruleId == SellerDataRules.TAX_INFO }
            assertEquals(FindingTarget.Seller, finding.target)
        }

        @Test
        fun `a buyer finding is targeted at the buyer`() {
            val finding = ComplianceEvaluator.evaluate(invoiceWith(buyer = Recipient()), today = FIXED_TODAY)
                .first { it.ruleId == BuyerDataRules.VAT_ID }
            assertEquals(FindingTarget.Buyer, finding.target)
        }

        /** The difference between the levels is the subject checked, not the rule: on the
         *  invoice, the *effective* party counts, i.e. `selectedSeller` before `senderProfile`. */
        @Test
        fun `the invoice level evaluates the selected seller, not the stored one`() {
            val stored = completeSeller("ST-")
            val selected = SenderProfile().apply { name = "Ausgewählt"; invoicePrefix = "AU-" }
            val invoice = invoiceWith(seller = stored).invoice.apply { selectedSeller = selected }

            val ids = ComplianceEvaluator.evaluate(ComplianceSubject.OfInvoice(invoice), today = FIXED_TODAY).map { it.ruleId }

            assertTrue(SellerDataRules.TAX_INFO in ids, "der ausgewählte Anbieter ist unvollständig: $ids")
        }
    }

    // ─── Findings and checks ───────────────────────────────────────────────

    @Nested
    inner class ChecksAndFindings {

        @Test
        fun `a satisfied check produces no finding`() {
            val check = ComplianceCheck("X", ComplianceSeverity.ERROR, "k", isSatisfied = true)
            assertEquals(null, check.findingOrNull())
        }

        @Test
        fun `an unsatisfied check carries rule id, severity, key and target over`() {
            val check = ComplianceCheck(
                "X", ComplianceSeverity.WARNING, "k", FindingTarget.Position("3"), listOf(B2bTerm.BT_1), isSatisfied = false,
            )
            val finding = check.findingOrNull()!!
            assertEquals("X", finding.ruleId)
            assertEquals(ComplianceSeverity.WARNING, finding.severity)
            assertEquals("k", finding.messageKey)
            assertEquals(listOf(B2bTerm.BT_1), finding.businessTerms)
            assertEquals("3", finding.positionId)
        }

        @Test
        fun `only ERROR blocks`() {
            assertTrue(finding(ComplianceSeverity.ERROR).isBlocking)
            assertFalse(finding(ComplianceSeverity.WARNING).isBlocking)
            assertFalse(finding(ComplianceSeverity.INFO).isBlocking)
        }

        /** The checklist shows more items than there are hard requirements — that's what `INFO`
         *  is for. An `INFO` finding must never hold up finalization. */
        @Test
        fun `a missing delivery address is INFO and does not block`() {
            // Billing address complete, delivery address absent entirely: the hard requirement
            // (BuyerDataRules.ADDRESS) is satisfied, the finer point stays informational.
            val findings = CoreCompletenessRules.checks(invoiceWith().invoice).findings()
            val single = findings.filter { it.ruleId == CoreCompletenessRules.BUYER_DELIVERY_ADDRESS }
            assertEquals(1, single.size)
            assertEquals(ComplianceSeverity.INFO, single.single().severity)
            assertFalse(findings.hasBlocking(), findings.map { it.ruleId }.toString())
        }

        @Test
        fun `on filters by target`() {
            val list = listOf(
                finding(ComplianceSeverity.ERROR, FindingTarget.Position("1")),
                finding(ComplianceSeverity.ERROR, FindingTarget.Position("2")),
                finding(ComplianceSeverity.ERROR, FindingTarget.Header),
            )
            assertEquals(1, list.on(FindingTarget.Position("2")).size)
            assertEquals(1, list.on(FindingTarget.Header).size)
        }

        private fun finding(severity: ComplianceSeverity, target: FindingTarget = FindingTarget.Header) =
            ComplianceFinding("X", severity, "k", target = target)
    }

    // ─── Transport shape ───────────────────────────────────────────────────────

    @Nested
    inner class ValidationMessageMapping {

        @Test
        fun `a finding maps onto the transport shape the UI already reads`() {
            val message = ComplianceFinding(
                "LT-UNIT-01", ComplianceSeverity.ERROR, "compliance.unit.unitCodeMismatch",
                target = FindingTarget.Position("7"),
            ).toValidationMessage()

            assertEquals("LT-UNIT-01", message.ruleId)
            assertEquals("compliance.unit.unitCodeMismatch", message.message, "message trägt den Schlüssel, nicht den Text")
            assertEquals("ERROR", message.severity)
            assertEquals("position:7", message.location)
            assertEquals("compliance-rules", message.validator, "daran unterscheidet die UI Schlüssel von KoSIT-Klartext")
        }

        @Test
        fun `a header finding has no location`() {
            assertEquals(null, ComplianceFinding("X", ComplianceSeverity.ERROR, "k").toValidationMessage().location)
        }

        @Test
        fun `args pass through so a resolving UI can fill the messageKey's placeholders`() {
            val message = ComplianceFinding(
                "TAX.CATEGORY_MISMATCH", ComplianceSeverity.ERROR, "compliance.tax.categoryMismatch",
                args = listOf("S", "AE"),
            ).toValidationMessage()

            assertEquals(listOf("S", "AE"), message.args)
        }

        @Test
        fun `a null arg element maps to an empty string, not the literal 'null'`() {
            val message = ComplianceFinding(
                "TAX.CATEGORY_MISMATCH", ComplianceSeverity.ERROR, "compliance.tax.categoryMismatch",
                args = listOf(42, null),
            ).toValidationMessage()

            assertEquals(listOf("42", ""), message.args)
        }
    }

    // ─── Test doubles and fixtures ──────────────────────────────────────────────

    private class RecordingRuleSet(
        override val id: String,
        override val levels: Set<ComplianceLevel>,
    ) : ComplianceRuleSet {
        val seen = mutableListOf<ComplianceSubject>()
        override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
            seen += subject
            return emptyList()
        }
    }

    private fun recordingRuleSet(id: String, levels: Set<ComplianceLevel>) = RecordingRuleSet(id, levels)

    private fun constantRuleSet(id: String, ruleId: String) = object : ComplianceRuleSet {
        override val id = id
        override val levels = setOf(ComplianceLevel.INVOICE)
        override fun evaluate(subject: ComplianceSubject, today: LocalDate) =
            listOf(ComplianceFinding(ruleId, ComplianceSeverity.WARNING, "k"))
    }

    private fun completeSeller(prefix: String) = SenderProfile().apply {
        name = "Anbieter GmbH"
        invoicePrefix = prefix
        email = Email.ofNullable("billing@anbieter.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE02120300000000202051")
        addAddress(Address.forSender(this, Address.AddressType.BILLING, "Anbieterweg 1", "50667", "Koeln", Country.DE, true))
    }

    private fun completeBuyer() = Recipient().apply {
        customerNumber = CustomerNumber("K-1")
        name = "Kunde GmbH"
        email = Email.ofNullable("ap@kunde.example")
        vatId = VatId("DE987654321")
        addAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Kundenweg 2", "50668", "Koeln", Country.DE, true))
    }

    private fun invoiceWith(
        seller: SenderProfile = completeSeller("AN-"),
        buyer: Recipient = completeBuyer(),
    ): ComplianceSubject.OfInvoice {
        val invoice = Invoice().apply {
            senderProfile = seller
            recipient = buyer
            invoiceNumber = InvoiceNumber("AN-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            // D-R1 (CORE.SERVICE_PERIOD_XOR) needs one of its three alternatives; this fixture
            // is about delivery-address completeness, not service dates — set BT-72 so it stays
            // unrelated noise-free for that assertion.
            deliveryDate = LocalDate.of(2026, 2, 10)
        }
        invoice.addItem(
            InvoiceItem().apply {
                positionId = "1"
                description = "Beratung"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
            },
        )
        return ComplianceSubject.OfInvoice(invoice)
    }
}
