package de.littletrotter.domain.compliance

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * The contract [ComplianceRuleSet] describes but nothing tested until this file: **every** rule
 * set names a stable id and returns an empty list for a subject it isn't responsible for —
 * instead of throwing or inventing findings.
 *
 * Not a paper exercise: [ComplianceEvaluator] filters by [ComplianceRuleSet.levels], but
 * [de.littletrotter.domain.compliance.evaluateCompliance] and
 * [de.littletrotter.application.invoice.InvoiceLifecycleService] pass through rule-set lists a
 * caller assembles itself (`B2bProfileRuleSet` is appended explicitly by one such caller). A rule
 * set that throws `ClassCastException` on a foreign subject would break that contract outright,
 * not just misjudge a field. [BuyerDataRules.evaluate] itself was never exercised before this
 * test — only its `checks` function via [CoreCompletenessRules].
 */
class ComplianceRuleSetDispatchTest {

    /** No built-in rule set is responsible for this subject today — exactly why it works as a
     *  probe for "what do you do when nobody asked you?". */
    private val foreign = ComplianceSubject.OfArticle(anArticle { name = "Beratung" })

    @Test
    fun `no built-in rule set reacts to a subject it does not declare`() {
        ComplianceRuleSets.DEFAULT.forEach { ruleSet ->
            assertTrue(
                ruleSet.evaluate(foreign, today = FIXED_TODAY).isEmpty(),
                "${ruleSet.id} liefert Befunde zu einem Gegenstand, für den es nicht zuständig ist",
            )
        }
    }

    @Test
    fun `every built-in rule set carries a distinct, non-blank id`() {
        val ids = ComplianceRuleSets.DEFAULT.map { it.id }

        assertTrue(ids.none { it.isBlank() }, "eine leere Kennung macht einen Befund unzuordenbar: $ids")
        assertEquals(ids.size, ids.toSet().size, "Kennungen müssen eindeutig sein: $ids")
        assertEquals(
            listOf("SELLER", "BUYER", "CORE", "PERIOD", "DUE_DATE", "UNIT", "TAX", "SCHEME_ID"),
            ids,
            "Kennung und Reihenfolge sind Teil der Zusage — Befunde werden danach angezeigt und gefiltert",
        )
    }

    @Test
    fun `the seller rule set evaluates the master record it is handed`() {
        val findings = SellerDataRules.evaluate(ComplianceSubject.OfSeller(SenderProfile()), today = FIXED_TODAY)

        assertTrue(
            findings.any { it.ruleId == SellerDataRules.IDENTITY },
            "ein leerer Anbieter muss an seiner Identität scheitern: $findings",
        )
        assertTrue(findings.all { it.target == FindingTarget.Seller }, "Befunde hängen am Anbieter")
    }

    /**
     * The counterpart case, and the actual reason for this file: `BuyerDataRules` is listed in
     * [ComplianceRuleSets.DEFAULT] but was never exercised through its own entry point.
     */
    @Test
    fun `the buyer rule set evaluates the master record it is handed`() {
        val recipient = aRecipient {
            customerNumber = CustomerNumber("K-1")
            name = "Acme GmbH"
            email = Email.ofNullable("ap@acme.example")
            vatId = VatId("DE987654321")
        }
        recipient.addAddress(
            Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg 1", "10115", "Berlin", Country.DE, true),
        )

        assertTrue(
            BuyerDataRules.evaluate(ComplianceSubject.OfBuyer(recipient), today = FIXED_TODAY).isEmpty(),
            "ein vollständiger Empfänger erzeugt keinen Befund",
        )
        assertTrue(
            BuyerDataRules.evaluate(ComplianceSubject.OfBuyer(Recipient()), today = FIXED_TODAY).any { it.ruleId == BuyerDataRules.IDENTITY },
            "ein leerer Empfänger muss an seiner Identität scheitern",
        )
    }

    // ST-B3 follow-up — IDENTITY/ADDRESS boundary tests below: 's CustomerNumber/PostalCode
    // conversions turned `recipient.customerNumber.isNotBlank()` (NOT NULL String) into
    // `recipient.customerNumber != null` (CustomerNumber?), and every existing fixture either sets
    // BOTH name and customerNumber (satisfied) or NEITHER (Recipient()'s own blank name
    // short-circuits before customerNumber is ever evaluated) — the "name present but no customer
    // number yet" arm of that `&&` chain, and the ADDRESS `||`'s "billing missing, delivery
    // present" arm, went untested, dropping SellerDataRules/BuyerDataRules branch coverage below
    // this module's own calibrated per-class jacoco thresholds (kotlin-invoice/pom.xml,
    // check-calculation-core-100).

    @Test
    fun `seller identity needs a non-blank invoice prefix even with a name present`() {
        val profile = aSenderProfile { name = "Anbieter"; invoicePrefix = "" }

        assertFalse(
            SellerDataRules.checks(profile, null).single { it.ruleId == SellerDataRules.IDENTITY }.isSatisfied,
            "ein leeres Rechnungspräfix darf die Identitätsprüfung nicht bestehen lassen",
        )
    }

    @Test
    fun `buyer identity needs a customer number even with a name present`() {
        val recipient = aRecipient { name = "Acme GmbH" }

        assertFalse(
            BuyerDataRules.checks(recipient, null, null).single { it.ruleId == BuyerDataRules.IDENTITY }.isSatisfied,
            "ohne Kundennummer darf die Identitätsprüfung nicht bestehen, auch mit gesetztem Namen",
        )
    }

    @Test
    fun `buyer address is satisfied via the delivery address alone when billing is incomplete`() {
        val recipient = aRecipient { name = "Acme GmbH" }
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Weg 1", "10115", "Berlin", Country.DE, true)

        assertTrue(
            BuyerDataRules.checks(recipient, null, delivery).single { it.ruleId == BuyerDataRules.ADDRESS }.isSatisfied,
            "eine vollständige Lieferadresse muss ausreichen, wenn die Rechnungsadresse fehlt",
        )
    }

    // The remaining, pre-existing branch on each of these lines — the isBlank() half of
    // `!x?.value.isNullOrBlank()` for a *live, non-null* Email/Iban instance — looked
    // structurally dead per this module's own jacoco.xml calibration comment (kotlin-invoice/
    // pom.xml check-calculation-core-100: "Email/Iban validate Nichtleere im Konstruktor"), but
    // both validators actually accept blank at construction (EmailValidator/IbanValidator KDoc:
    // "a blank or null value is always considered valid") — Email("")/Iban("") really do
    // construct without throwing, exactly as RecipientValidationTest already relies on
    // (`email = Email("")`) — so the branch is reachable, not dead. Shrinking the denominator
    // elsewhere (the customerNumber/invoicePrefix arms just closed above) pushed this
    // already-untested arm over the per-class threshold; closing it here rather than declaring
    // it unreachable, since it demonstrably isn't.

    @Test
    fun `seller email is unsatisfied for a blank but present email value`() {
        val profile = aSenderProfile { name = "Anbieter"; invoicePrefix = "AN-"; email = Email("") }

        assertFalse(
            SellerDataRules.checks(profile, null).single { it.ruleId == SellerDataRules.EMAIL }.isSatisfied,
            "eine leere E-Mail-Adresse darf die Prüfung nicht bestehen lassen",
        )
    }

    @Test
    fun `seller iban is unsatisfied for a blank but present iban value`() {
        val profile = aSenderProfile { name = "Anbieter"; invoicePrefix = "AN-"; iban = Iban("") }

        assertFalse(
            SellerDataRules.checks(profile, null).single { it.ruleId == SellerDataRules.IBAN }.isSatisfied,
            "eine leere IBAN darf die Prüfung nicht bestehen lassen",
        )
    }

    @Test
    fun `buyer email is unsatisfied for a blank but present email value`() {
        val recipient = aRecipient { name = "Acme GmbH"; email = Email("") }

        assertFalse(
            BuyerDataRules.checks(recipient, null, null).single { it.ruleId == BuyerDataRules.EMAIL }.isSatisfied,
            "eine leere E-Mail-Adresse darf die Prüfung nicht bestehen lassen",
        )
    }

    /**
     * Both master-data rule sets are also called with `null` — from [CoreCompletenessRules] when
     * an invoice doesn't carry a seller or recipient yet. Findings instead of a
     * `NullPointerException` is what makes the checklist displayable on a fresh draft at all.
     */
    @Test
    fun `both party rule sets treat an absent party as a finding, not as a crash`() {
        val sellerChecks = SellerDataRules.checks(null, null)
        val buyerChecks = BuyerDataRules.checks(null, null, null)

        assertTrue(sellerChecks.none { it.isSatisfied }, "ohne Anbieter ist keine Prüfung erfüllt: $sellerChecks")
        assertTrue(buyerChecks.none { it.isSatisfied }, "ohne Empfänger ist keine Prüfung erfüllt: $buyerChecks")
    }

    // /d — the "a party whose value objects are absent fails the same checks as one
    // without them" test that used to live here (constructing TaxNumber("  ")) is gone: a blank
    // TaxNumber/VatId is unrepresentable now (both reject blank at construction), so the "set but
    // blank" state it exercised no longer exists — a value-object field is either absent (null)
    // or genuinely present. SellerTaxInfo's own "a blank tax number is unsatisfied the same as a
    // missing one" test below covers the exact same rule (TAX_INFO) via .ofNullable's blank->null
    // mapping, so no coverage was lost.

    /** [SellerDataRules.TAX_INFO] needs *both* a tax number and a VAT id; every combination
     *  of present/blank/absent on each field is walked separately so a caller can never satisfy
     *  it with only one of the two, in either order. */
    @Nested
    inner class SellerTaxInfo {

        private fun profile(taxNumber: String?, vatId: String?) = aSenderProfile {
            name = "Anbieter"
            invoicePrefix = "AN-"
            this.taxNumber = TaxNumber.ofNullable(taxNumber)
            this.vatId = VatId.ofNullable(vatId)
        }

        private fun taxInfoSatisfied(taxNumber: String?, vatId: String?) =
            SellerDataRules.checks(profile(taxNumber, vatId), null)
                .single { it.ruleId == SellerDataRules.TAX_INFO }.isSatisfied

        @Test
        fun `both a tax number and a vat id present is satisfied`() {
            assertTrue(taxInfoSatisfied("12/345/67890", "DE123456789"))
        }

        @Test
        fun `a missing tax number is unsatisfied even with a vat id present`() {
            assertFalse(taxInfoSatisfied(null, "DE123456789"))
        }

        @Test
        fun `a blank tax number is unsatisfied the same as a missing one`() {
            assertFalse(taxInfoSatisfied("  ", "DE123456789"))
        }

        @Test
        fun `a missing vat id is unsatisfied even with a tax number present`() {
            assertFalse(taxInfoSatisfied("12/345/67890", null))
        }

        @Test
        fun `a blank vat id is unsatisfied the same as a missing one`() {
            assertFalse(taxInfoSatisfied("12/345/67890", "  "))
        }
    }
}
