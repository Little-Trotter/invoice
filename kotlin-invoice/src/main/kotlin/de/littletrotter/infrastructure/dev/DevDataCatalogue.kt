/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.infrastructure.dev

import de.littletrotter.domain.masterdata.invoiceNoteFor
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.initialInvoiceNoteSnapshot
import de.littletrotter.shared.documents.DocumentTexts
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.Money
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.RecipientDetails
import de.littletrotter.domain.masterdata.SenderProfileDetails
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.TaxRateDefinition
import de.littletrotter.domain.reference.TravelExpenseDefinition
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.masterdata.Address
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.invoice.InvoiceDraft
import de.littletrotter.domain.invoice.InvoiceParties
import de.littletrotter.domain.invoice.InvoiceHeaderChange
import de.littletrotter.domain.invoice.InvoiceItemLineChange

/**
 * The pure data half of the dev fixture split: builds the synthetic `datagen` entities
 * (sender, recipients, invoices, items, travel entries) with no persistence and no CDI
 * dependency, so every function here is a plain unit under test. [DevDataFixtures] is the
 * loader half: it owns the persistence orchestration (idempotent lookups, `saveNumbered`,
 * cleanup) and calls into this catalogue for the data itself.
 */
object DevDataCatalogue {
    const val DEV_SENDER_EMAIL = "devedge.sender@example.invalid"
    private const val DEV_SENDER_CITY = "Koeln"
    const val DEV_CUSTOMER_PREFIX = "DEVEDGE-"
    const val POS_001 = "POS-001"

    /** The one VAT rate every fixture invoice (core and bulk) taxes its domestic items at.
     *  Named so both this catalogue and the loader (which still writes a handful of core-fixture
     *  items directly) reference it instead of repeating the literal — repeating it independently
     *  in two files is exactly the drift this split must not reintroduce. */
    const val VAT_STANDARD_RATE = "19.00"

    /** Due-date offsets the loader's two edge-case core fixtures (DRAFT, IN_REVIEW) use; named so
     *  the loader references them instead of repeating a literal this catalogue's own bulk-year
     *  loops already use for an unrelated reason (day-of-month capping, `i % 3` grouping) — two
     *  independent literals with the same value in two files is exactly the drift this split must
     *  not reintroduce. */
    const val EDGE_DRAFT_DUE_DAYS = 28L
    const val EDGE_IN_REVIEW_DUE_DAYS = 3L
    private const val PER_DIEM_GENEVA_DESC = "Verpflegungspauschale Genf"

    /** Every bulk fixture invoice's due date is 30 days (net 30) after its invoice date. */
    private const val BULK_PAYMENT_TERM_DAYS = 30L

    /** Cycles a bulk fixture's synthetic invoice date through the twelve months of its year. */
    private const val MONTHS_PER_YEAR = 12

    // ── 2024 bulk batch — named so bulkYear2024's own three helper functions never repeat a
    // literal, and to drop bulkYear2024 itself below detekt's LongMethod threshold. ──
    private const val FISCAL_YEAR_2024 = 2024
    private const val SENT_DOMESTIC_PUBLIC_COUNT_2024 = 55
    private const val DAY_OF_MONTH_SPAN_A_2024 = 28
    private const val PUBLIC_RECIPIENT_EVERY_2024 = 4
    private const val BASE_PRICE_DOMESTIC_2024 = 900
    private const val PRICE_VARIATION_STEPS_20_2024 = 20
    private const val PRICE_STEP_DOMESTIC_2024 = 50
    private const val EU_RC_COUNT_2024 = 20
    private const val BASE_PRICE_EU_RC_2024 = 1500
    private const val PRICE_VARIATION_STEPS_10_2024 = 10
    private const val VOIDED_COUNT_2024 = 25
    private const val DAY_OF_MONTH_SPAN_B_2024 = 20
    private const val DAY_OFFSET_VOIDED_2024 = 5
    private const val VOIDED_REVERSE_CHARGE_EVERY_2024 = 3

    // ── 2025 bulk batch — same reasoning as the 2024 block above. ──
    private const val FISCAL_YEAR_2025 = 2025
    private const val SENT_COUNT_2025 = 56
    private const val VOIDED_COUNT_2025 = 20
    private const val IN_REVIEW_COUNT_2025 = 20
    private const val DRAFT_COUNT_2025 = 4
    private const val DAY_OF_MONTH_SPAN_SENT_2025 = 28
    private const val REVERSE_CHARGE_EVERY_SENT_2025 = 5
    private const val PUBLIC_RECIPIENT_EVERY_2025 = 4
    private const val BASE_PRICE_SENT_2025 = 850
    private const val PRICE_VARIATION_STEPS_SENT_2025 = 25
    private const val PRICE_STEP_SENT_2025 = 60
    private const val DAY_OF_MONTH_SPAN_VOIDED_2025 = 25
    private const val DAY_OF_MONTH_SPAN_IN_REVIEW_2025 = 20
    private const val REVERSE_CHARGE_EVERY_IN_REVIEW_2025 = 4
    private const val BASE_PRICE_IN_REVIEW_2025 = 700
    private const val PRICE_STEP_IN_REVIEW_2025 = 30
    private const val MONTHS_PER_QUARTER_2025 = 3

    fun sender() = SenderProfile().apply {
        update(
            SenderProfileDetails(
                name = "DEV Edge Cases GmbH",
                taxNumber = TaxNumber("1234567890"),
                vatId = VatId("DE999999999"),
                iban = Iban.ofNullable("DE02120300000000202051"),
                bic = Bic("BYLADEM1001"),
                bankName = "Edge Bank",
                invoicePrefix = "DEVEDGE-",
                phone = "+49 221 000000",
                email = Email.ofNullable(DEV_SENDER_EMAIL),
                managingDirector = "Max Edge",
                courtOfJurisdiction = DEV_SENDER_CITY,
                website = "https://www.example.invalid/",
                registeredSeat = DEV_SENDER_CITY,
            ),
        )
        // Add a default billing address for the sender
        val billing =
            Address.forSender(this, Address.AddressType.BILLING, "Teststrasse 42", "50667", DEV_SENDER_CITY, Country.DE, true)
        designateBillingAddress(billing)
    }

    fun recipient(number: String, name: String, country: Country, vatId: String?, customerType: CustomerType? = null) =
        Recipient().apply {
            update(
                RecipientDetails(
                    customerNumber = CustomerNumber.of(number),
                    name = name,
                    vatId = VatId.ofNullable(vatId),
                    customerType = customerType,
                    documentLanguage = DocumentLanguage.fromCountry(country.alpha2).code,
                ),
            )
            // provide a minimal billing address for fixtures
            val billing = Address.forRecipient(this, Address.AddressType.BILLING, "", "", "", country, true)
            designateBillingAddress(billing)
        }

    /** A synthetic private customer, for invoices that carry the private-customer notice. */
    fun consumerRecipient(number: String, name: String) =
        recipient(number, name, Country.DE, null, CustomerType.CONSUMER)

    /** The two parties every dev fixture invoice needs — bundled so [invoice] takes one
     *  parameter for it instead of two (LongParameterList). */
    data class DevInvoiceParties(
        val sender: SenderProfile,
        val recipient: Recipient,
    )

    fun invoice(
        status: InvoiceStatus,
        parties: DevInvoiceParties,
        date: LocalDate,
        items: List<InvoiceItem>,
        terms: DevInvoiceTerms = DevInvoiceTerms(),
    ): Invoice {
        // N5 — no number set here; the loader's saveNumbered() draws the real one via
        // InvoiceNumberAssigner right before persisting, exactly like
        // InvoiceLifecycleService.create() does.
        val documentLanguage = parties.recipient.documentLanguage
        val invoice = Invoice.draft(
            InvoiceDraft(
                parties = InvoiceParties(
                    senderProfile = parties.sender,
                    recipient = parties.recipient,
                    documentLanguage = documentLanguage,
                ),
                header = InvoiceHeaderChange(
                    invoiceDate = date,
                    dueDate = terms.dueDate,
                    paymentTermsDays = terms.paymentTermsDays,
                ),
                invoiceNote = initialInvoiceNoteSnapshot(
                    parties.sender.id,
                    documentLanguage,
                    parties.sender.invoiceNoteFor(documentLanguage),
                ),
                // Dev instances run the craft-trade wording profile by default, so a private-customer
                // fixture carries the default notice exactly as a real creation would.
                consumerNote = initialInvoiceNoteSnapshot(
                    parties.sender.id,
                    documentLanguage,
                    DocumentTexts.text("consumerNote.default", documentLanguage).takeIf { parties.recipient.customerType == CustomerType.CONSUMER },
                    parties.recipient.customerType?.name.orEmpty(),
                ),
                freeText = terms.freeText,
                // reverseCharge is the invoice's own leading decision now, no longer derived by
                // recalculateTotals() from the items' tax category (see Invoice.reverseCharge's own
                // KDoc), so this fixture writes it directly.
                reverseCharge = terms.reverseCharge,
                status = status,
                typeCode = terms.typeCode,
                originalInvoiceNumber = terms.originalInvoiceNumber,
                validationStatus = terms.validationStatus,
            ),
        )
        // Still also tags the first item's snapshot category when the fixture asks for it, so the
        // fixture's data stays consistent with the new finalization gate
        // (validateReverseChargeItemConsistency) exactly like real usage would — these dev-only
        // items never go through InvoiceItem.snapshotTaxRule(), so nothing else would set it.
        if (terms.reverseCharge && items.isNotEmpty()) {
            items.first().let { it.changeTax(it.asTaxChange().copy(taxCategory = VatCategory.REVERSE_CHARGE.code)) }
        }
        invoice.addInitialItems(items)
        return invoice
    }

    /** Bundles the four amount-shaped [item] fields into one parameter object — same "drop below
     *  the LongParameterList threshold" pattern [DevInvoiceTerms] already uses for the fixture
     *  invoice's own terms. */
    data class ItemAmounts(
        val qty: String,
        val unit: InvoiceItemUnit,
        val price: String,
        val tax: String,
    )

    fun item(
        posId: String,
        category: InvoiceItemCategory,
        description: String,
        amounts: ItemAmounts,
    ) = InvoiceItem.draft(
        InvoiceItemLineChange(
            category = category,
            description = description,
            quantity = BigDecimal(amounts.qty),
            unit = amounts.unit,
            unitPriceNet = Money.of(amounts.price),
        ),
        positionId = posId,
    ).apply {
        applyTaxRule(devTaxRate(amounts.tax))
        calculateAmounts()
    }

    /** A transient rule for the in-memory fixtures: standard rate when positive, zero-rated otherwise. */
    private fun devTaxRate(percent: String): TaxRate {
        val rate = BigDecimal(percent)
        val category = if (rate.signum() > 0) VatCategory.STANDARD else VatCategory.ZERO_RATED
        return TaxRate.create(TaxRateDefinition(rate = TaxRatePercent(rate), category = category.code))
    }

    private fun serviceItem(description: String, price: String, tax: String) =
        item(POS_001, InvoiceItemCategory.SERVICE, description, ItemAmounts("1", InvoiceItemUnit.FLAT_RATE, price, tax))

    /** The ISSUED-fixture's per-diem item (`POS-003`); the loader attaches [genfTravelEntries] to it
     *  itself, since those need a "today"-relative start date the catalogue does not own. */
    fun perDiemItem() = InvoiceItem.draft(
        InvoiceItemLineChange(
            category = InvoiceItemCategory.TRIP,
            description = PER_DIEM_GENEVA_DESC,
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.FLAT_RATE,
            unitPriceNet = Money.of("96.00"),
        ),
        positionId = "POS-003",
    ).apply {
        applyTaxRule(devTaxRate("0.00"))
        calculateAmounts()
    }

    /** Days before [today] the Geneva trip's first per-diem day falls on; owned here so the
     *  loader never has to carry this offset as its own literal. */
    private const val GENEVA_TRIP_START_OFFSET_DAYS = 3L

    private const val GENEVA_PARTICIPANT = "Geneva"
    private const val GENEVA_FULL_DAY_RATE = "64.00"
    private const val GENEVA_ARRIVAL_DEPARTURE_RATE = "32.00"

    /** Builds the three per-diem entries for the ISSUED fixture's Geneva trip, relative to [today]. */
    fun genfTravelEntries(invoiceItem: InvoiceItem, today: LocalDate): List<TravelExpenseEntry> {
        val startDate = today.minusDays(GENEVA_TRIP_START_OFFSET_DAYS)
        return listOf(
            genfEntry(
                invoiceItem,
                TravelExpenseDefinition(
                    shortName = "CH Geneva $startDate",
                    country = Country.CH,
                    description = PER_DIEM_GENEVA_DESC,
                    participant = GENEVA_PARTICIPANT,
                    travelDate = startDate,
                    isArrivalDeparture = true,
                    fullDayRateSnapshot = Money.of(GENEVA_FULL_DAY_RATE),
                    arrivalDepartureRateSnapshot = Money.of(GENEVA_ARRIVAL_DEPARTURE_RATE),
                    calculatedAmount = Money.of(GENEVA_ARRIVAL_DEPARTURE_RATE),
                ),
            ),
            genfEntry(
                invoiceItem,
                TravelExpenseDefinition(
                    shortName = "CH Geneva ${startDate.plusDays(1)}",
                    country = Country.CH,
                    description = PER_DIEM_GENEVA_DESC,
                    participant = GENEVA_PARTICIPANT,
                    travelDate = startDate.plusDays(1),
                    breakfastDeduction = true,
                    fullDayRateSnapshot = Money.of(GENEVA_FULL_DAY_RATE),
                    arrivalDepartureRateSnapshot = Money.of(GENEVA_ARRIVAL_DEPARTURE_RATE),
                    calculatedAmount = Money.of(GENEVA_ARRIVAL_DEPARTURE_RATE),
                ),
            ),
            genfEntry(
                invoiceItem,
                TravelExpenseDefinition(
                    shortName = "CH Geneva ${startDate.plusDays(2)}",
                    country = Country.CH,
                    description = PER_DIEM_GENEVA_DESC,
                    participant = GENEVA_PARTICIPANT,
                    travelDate = startDate.plusDays(2),
                    isArrivalDeparture = true,
                    lunchDeduction = true,
                    fullDayRateSnapshot = Money.of(GENEVA_FULL_DAY_RATE),
                    arrivalDepartureRateSnapshot = Money.of(GENEVA_ARRIVAL_DEPARTURE_RATE),
                    calculatedAmount = Money.of(GENEVA_ARRIVAL_DEPARTURE_RATE),
                ),
            ),
        )
    }

    private fun genfEntry(invoiceItem: InvoiceItem, def: TravelExpenseDefinition): TravelExpenseEntry =
        TravelExpenseEntry.create(def).also { it.assignTo(invoiceItem) }

    // ── Bulk historical data ───────────────────────────────────────────────────

    /** 100 invoices for the 2024 year-comparison batch: 55 ISSUED (domestic/public), 20 ISSUED EU
     *  reverse-charge, 25 VOIDED — built, not persisted; the loader's saveNumbered() does that in
     *  this same order so the fixture numbers still draw gaplessly. */
    fun bulkYear2024(
        sender: SenderProfile,
        domestic: Recipient,
        euRc: Recipient,
        public: Recipient,
    ): List<Invoice> = buildList {
        // N5 — numbered through the loader's saveNumbered() like every other fixture; the real
        // allocator always dates the number from "today" (InvoiceNumberService
        // .generateNextNumber), never from the invoice's own historical invoiceDate, so these no
        // longer carry a year-matching number the way the old hand-rolled devNum() literal did —
        // that was never something production could actually produce either.
        // 55 ISSUED – domestic / public (indices 1..55)
        for (i in 1..SENT_DOMESTIC_PUBLIC_COUNT_2024) add(sentDomesticInvoice2024(i, sender, domestic, public))
        // 20 ISSUED – EU reverse charge (indices 56..75)
        for (i in 1..EU_RC_COUNT_2024) add(sentEuRcInvoice2024(i, sender, euRc))
        // 25 VOIDED (indices 76..100)
        for (i in 1..VOIDED_COUNT_2024) add(voidedInvoice2024(i, sender, domestic, euRc))
    }

    private fun sentDomesticInvoice2024(i: Int, sender: SenderProfile, domestic: Recipient, public: Recipient): Invoice {
        val month = ((i - 1) % MONTHS_PER_YEAR) + 1
        val day = ((i - 1) % DAY_OF_MONTH_SPAN_A_2024) + 1
        return invoice(
            status = InvoiceStatus.ISSUED,
            parties = DevInvoiceParties(sender, if (i % PUBLIC_RECIPIENT_EVERY_2024 == 0) public else domestic),
            date = LocalDate.of(FISCAL_YEAR_2024, month, day),
            items = listOf(
                serviceItem(
                    "Dienstleistung 2024",
                    "${BASE_PRICE_DOMESTIC_2024 + (i % PRICE_VARIATION_STEPS_20_2024) * PRICE_STEP_DOMESTIC_2024}.00",
                    VAT_STANDARD_RATE,
                ),
            ),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2024, month, day).plusDays(BULK_PAYMENT_TERM_DAYS),
                reverseCharge = false,
                validationStatus = ValidationStatus.VALID,
            ),
        )
    }

    private fun sentEuRcInvoice2024(i: Int, sender: SenderProfile, euRc: Recipient): Invoice {
        val month = ((i - 1) % MONTHS_PER_YEAR) + 1
        val day = ((i - 1) % EU_RC_COUNT_2024) + 1
        return invoice(
            status = InvoiceStatus.ISSUED,
            parties = DevInvoiceParties(sender, euRc),
            date = LocalDate.of(FISCAL_YEAR_2024, month, day),
            items = listOf(
                serviceItem(
                    "EU RC Service 2024",
                    "${BASE_PRICE_EU_RC_2024 + (i % PRICE_VARIATION_STEPS_10_2024) * 100}.00",
                    "0.00",
                ),
            ),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2024, month, day).plusDays(BULK_PAYMENT_TERM_DAYS),
                reverseCharge = true,
                validationStatus = ValidationStatus.VALID,
            ),
        )
    }

    private fun voidedInvoice2024(i: Int, sender: SenderProfile, domestic: Recipient, euRc: Recipient): Invoice {
        val month = ((i - 1) % MONTHS_PER_YEAR) + 1
        val day = ((i - 1) % DAY_OF_MONTH_SPAN_B_2024) + DAY_OFFSET_VOIDED_2024
        val rc = i % VOIDED_REVERSE_CHARGE_EVERY_2024 == 0
        return invoice(
            status = InvoiceStatus.VOIDED,
            parties = DevInvoiceParties(sender, if (rc) euRc else domestic),
            date = LocalDate.of(FISCAL_YEAR_2024, month, day),
            items = listOf(serviceItem("Annullierte Leistung 2024", "750.00", VAT_STANDARD_RATE)),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2024, month, day).plusDays(BULK_PAYMENT_TERM_DAYS),
                // never validated (voided before/without ever running a generation job) — the
                // fixture's own default already covers this now.
                reverseCharge = rc,
            ),
        )
    }

    /** 100 invoices for the 2025 year-comparison batch: 56 ISSUED, 20 VOIDED, 20 IN_REVIEW, 4 DRAFT —
     *  same "build, don't persist" contract as [bulkYear2024]. */
    fun bulkYear2025(
        sender: SenderProfile,
        domestic: Recipient,
        euRc: Recipient,
        public: Recipient,
    ): List<Invoice> = buildList {
        // 56 ISSUED (indices 1..56)
        val sentRecipients = SentRecipients2025(domestic, euRc, public)
        for (i in 1..SENT_COUNT_2025) add(sentInvoice2025(i, sender, sentRecipients))
        // 20 VOIDED (indices 57..76)
        for (i in 1..VOIDED_COUNT_2025) add(voidedInvoice2025(i, sender, domestic))
        // 20 IN_REVIEW – N5: numbered from birth too now, like every other fixture.
        for (i in 1..IN_REVIEW_COUNT_2025) add(inReviewInvoice2025(i, sender, domestic, euRc))
        // 4 DRAFT – one per quarter (months 3, 6, 9, 12)
        for (i in 1..DRAFT_COUNT_2025) add(draftInvoice2025(i, sender, domestic))
    }

    /** The three recipient options [sentInvoice2025] rotates through, bundled so that function
     *  takes one parameter for them instead of three (LongParameterList). */
    private data class SentRecipients2025(
        val domestic: Recipient,
        val euRc: Recipient,
        val public: Recipient,
    )

    private fun sentInvoice2025(
        i: Int,
        sender: SenderProfile,
        recipients: SentRecipients2025,
    ): Invoice {
        val month = ((i - 1) % MONTHS_PER_YEAR) + 1
        val day = ((i - 1) % DAY_OF_MONTH_SPAN_SENT_2025) + 1
        val rc = i % REVERSE_CHARGE_EVERY_SENT_2025 == 0
        return invoice(
            status = InvoiceStatus.ISSUED,
            parties = DevInvoiceParties(
                sender,
                when {
                    i % REVERSE_CHARGE_EVERY_SENT_2025 == 0 -> recipients.euRc
                    i % PUBLIC_RECIPIENT_EVERY_2025 == 0 -> recipients.public
                    else -> recipients.domestic
                },
            ),
            date = LocalDate.of(FISCAL_YEAR_2025, month, day),
            items = listOf(
                serviceItem(
                    "Dienstleistung 2025",
                    "${BASE_PRICE_SENT_2025 + (i % PRICE_VARIATION_STEPS_SENT_2025) * PRICE_STEP_SENT_2025}.00",
                    if (rc) "0.00" else VAT_STANDARD_RATE,
                ),
            ),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2025, month, day).plusDays(BULK_PAYMENT_TERM_DAYS),
                reverseCharge = rc,
                validationStatus = ValidationStatus.VALID,
            ),
        )
    }

    private fun voidedInvoice2025(i: Int, sender: SenderProfile, domestic: Recipient): Invoice {
        val month = ((i - 1) % MONTHS_PER_YEAR) + 1
        val day = ((i - 1) % DAY_OF_MONTH_SPAN_VOIDED_2025) + 1
        return invoice(
            status = InvoiceStatus.VOIDED,
            parties = DevInvoiceParties(sender, domestic),
            date = LocalDate.of(FISCAL_YEAR_2025, month, day),
            items = listOf(serviceItem("Annullierte Leistung 2025", "600.00", VAT_STANDARD_RATE)),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2025, month, day).plusDays(BULK_PAYMENT_TERM_DAYS),
                // never validated — same reasoning as the 2024 VOIDED batch above.
                reverseCharge = false,
            ),
        )
    }

    private fun inReviewInvoice2025(i: Int, sender: SenderProfile, domestic: Recipient, euRc: Recipient): Invoice {
        val month = ((i - 1) % MONTHS_PER_YEAR) + 1
        val day = ((i - 1) % DAY_OF_MONTH_SPAN_IN_REVIEW_2025) + 1
        val rc = i % REVERSE_CHARGE_EVERY_IN_REVIEW_2025 == 0
        return invoice(
            status = InvoiceStatus.IN_REVIEW,
            parties = DevInvoiceParties(sender, if (rc) euRc else domestic),
            date = LocalDate.of(FISCAL_YEAR_2025, month, day),
            items = listOf(
                serviceItem(
                    "In Prüfung 2025",
                    "${BASE_PRICE_IN_REVIEW_2025 + i * PRICE_STEP_IN_REVIEW_2025}.00",
                    if (rc) "0.00" else VAT_STANDARD_RATE,
                ),
            ),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2025, month, day).plusDays(BULK_PAYMENT_TERM_DAYS),
                reverseCharge = rc,
            ),
        )
    }

    private fun draftInvoice2025(i: Int, sender: SenderProfile, domestic: Recipient): Invoice {
        val month = i * MONTHS_PER_QUARTER_2025
        return invoice(
            status = InvoiceStatus.DRAFT,
            parties = DevInvoiceParties(sender, domestic),
            date = LocalDate.of(FISCAL_YEAR_2025, month, 1),
            items = listOf(serviceItem("Entwurf 2025", "500.00", VAT_STANDARD_RATE)),
            terms = DevInvoiceTerms(
                dueDate = LocalDate.of(FISCAL_YEAR_2025, month, 1).plusDays(BULK_PAYMENT_TERM_DAYS),
                reverseCharge = false,
            ),
        )
    }
}
