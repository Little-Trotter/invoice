/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.infrastructure.dev

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.application.invoice.InvoiceNumberAssigner
import de.littletrotter.application.invoice.PartyRepositories
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.ConfigProvider
import java.time.LocalDate

private val LOG = Logger.getLogger(DevDataFixtures::class.java)

private const val COLOGNE_POSTAL_CODE = "50667"
private const val IN_REVIEW_INVOICE_AGE_DAYS = 7L
private const val CANCELLED_INVOICE_AGE_DAYS = 14L
private const val EDGE_VOIDED_DUE_DAYS = 14L

/**
 * The persistence-orchestration half of the dev fixture split: guards the dev profile,
 * makes seeding idempotent (recipient/sender lookup-before-insert, [cleanup] before every run),
 * numbers every invoice through the real [InvoiceNumberAssigner] seam, and persists. All fixture
 * *data* (the synthetic `datagen` entities themselves) lives in [DevDataCatalogue], which this
 * class only calls into — never a fabricated literal that could drift from the baseline schema.
 */
@ApplicationScoped
class DevDataFixtures @Inject constructor(
    private val em: EntityManager,
    private val invoiceRepo: InvoiceRepository,
    private val parties: PartyRepositories,
    private val addressManagementService: AddressManagementService,
    private val maintenanceService: DatabaseMaintenanceService,
    // N5 — every fixture is numbered through the same production seam
    // (InvoiceLifecycleService.create() uses this too), never a fabricated literal, so the
    // fixtures can never drift from the real allocation rule.
    private val numberAssigner: InvoiceNumberAssigner,
    private val time: TimeSource,
) {
    private val recipientRepo get() = parties.recipientRepo
    private val senderRepo get() = parties.senderRepo
    /** N5 — draws the fixture's real number (idempotent, no-op if already assigned) before
     *  persisting it, exactly like InvoiceLifecycleService.create() does. */
    private fun saveNumbered(invoice: Invoice): Invoice {
        numberAssigner.assignIfNeeded(invoice)
        return invoiceRepo.save(invoice)
    }

    // The StartupEvent parameter is required by the CDI observer contract (it's what makes ARC
    // invoke this method at startup) even though the body never reads it — named `ignored` (not
    // `event`) so detekt's UnusedParameter allowedNames convention recognizes the omission as
    // deliberate; same shape as DefaultLocaleInitializer.onStart's own @Observes parameter.
    fun onStart(@Observes ignored: StartupEvent, launchMode: LaunchMode) {
        if (launchMode != LaunchMode.DEVELOPMENT) return
        seed()
        LOG.info("DevDataFixtures: seed complete")

        val shouldRecalculate = ConfigProvider.getConfig().getOptionalValue("littletrotter.datagen.recalculate", Boolean::class.java).orElse(false)
        if (shouldRecalculate) {
            LOG.info("DevDataFixtures: trigger recalculate totals")
            maintenanceService.performRecalculateTotals()
        }
    }

    @Transactional
    fun seed() {
        cleanup()

        val sender = senderRepo.findDefault() ?: senderRepo.save(DevDataCatalogue.sender())
        addressManagementService.saveAddress(sender, Address.AddressType.BILLING, "Teststrasse 42", COLOGNE_POSTAL_CODE, "Köln", "DE")
        addressManagementService.saveAddress(sender, Address.AddressType.DELIVERY, "Lagerstrasse 1", COLOGNE_POSTAL_CODE, "Köln", "DE")

        val domestic = recipientRepo.findByCustomerNumber("DEVEDGE-DOMESTIC")
            ?: recipientRepo.save(DevDataCatalogue.recipient("DEVEDGE-DOMESTIC", "Musterkunde Inland GmbH", Country.DE, "DE111111111"))
        val euRc = recipientRepo.findByCustomerNumber("DEVEDGE-EU-RC")
            ?: recipientRepo.save(DevDataCatalogue.recipient("DEVEDGE-EU-RC", "Nordic Reverse Charge AB", Country.SE, "SE556677889901"))
        val publicRecipient = recipientRepo.findByCustomerNumber("DEVEDGE-PUBLIC")
            ?: recipientRepo.save(DevDataCatalogue.recipient("DEVEDGE-PUBLIC", "Stadtverwaltung Beispielstadt", Country.DE, null))
        val consumer = recipientRepo.findByCustomerNumber("DEVEDGE-CONSUMER")
            ?: recipientRepo.save(DevDataCatalogue.consumerRecipient("DEVEDGE-CONSUMER", "Erika Musterfrau"))

        addressManagementService.saveAddress(consumer, Address.AddressType.BILLING, "Gartenweg 2", COLOGNE_POSTAL_CODE, "Köln", "DE")
        addressManagementService.saveAddress(domestic, Address.AddressType.BILLING, "Hauptstr. 1", COLOGNE_POSTAL_CODE, "Köln", "DE")
        addressManagementService.saveAddress(domestic, Address.AddressType.DELIVERY, "Lagerweg 5", COLOGNE_POSTAL_CODE, "Köln", "DE")
        addressManagementService.saveAddress(euRc, Address.AddressType.BILLING, "Södergatan 7", "11122", "Stockholm", "SE")
        addressManagementService.saveAddress(publicRecipient, Address.AddressType.BILLING, "Marktplatz 3", "01067", "Dresden", "DE")

        val today = time.today()

        seedDraftEdgeCase(sender, publicRecipient, today)
        seedConsumerDraft(sender, consumer, today)
        seedInReviewEdgeCase(sender, euRc, today)
        seedCancelledPairWithTravel(sender, domestic, today)
        seedVoidedEdgeCase(sender, publicRecipient, today)

        // Bulk historical data for year comparison — built by the catalogue, persisted here in
        // the same order so the fixture numbers still draw gaplessly.
        DevDataCatalogue.bulkYear2024(sender, domestic, euRc, publicRecipient).forEach { saveNumbered(it) }
        DevDataCatalogue.bulkYear2025(sender, domestic, euRc, publicRecipient).forEach { saveNumbered(it) }
    }

    /** DRAFT for a private customer, carrying the private-customer notice. */
    private fun seedConsumerDraft(sender: SenderProfile, consumer: Recipient, today: LocalDate) {
        saveNumbered(
            DevDataCatalogue.invoice(
                status = InvoiceStatus.DRAFT,
                parties = DevDataCatalogue.DevInvoiceParties(sender, consumer),
                date = today.minusDays(1),
                items = listOf(
                    DevDataCatalogue.item(
                        DevDataCatalogue.POS_001, InvoiceItemCategory.SERVICE, "Reparatur Heizkörper",
                        amounts = DevDataCatalogue.ItemAmounts(qty = "2", unit = InvoiceItemUnit.HOUR, price = "65.00", tax = DevDataCatalogue.VAT_STANDARD_RATE),
                    ),
                ),
            ),
        )
    }

    /** DRAFT – edge-case with two items. N5: numbered from birth too, via saveNumbered(). Split out
     *  of [seed] purely to stay under detekt's `LongMethod` threshold — no behavior change. */
    private fun seedDraftEdgeCase(sender: SenderProfile, publicRecipient: Recipient, today: LocalDate) {
        saveNumbered(
            DevDataCatalogue.invoice(
                status = InvoiceStatus.DRAFT,
                parties = DevDataCatalogue.DevInvoiceParties(sender, publicRecipient),
                date = today.minusDays(2),
                items = listOf(
                    DevDataCatalogue.item(
                        DevDataCatalogue.POS_001, InvoiceItemCategory.SERVICE, "Beratung unger. Laufzeit",
                        amounts = DevDataCatalogue.ItemAmounts(qty = "12.5", unit = InvoiceItemUnit.HOUR, price = "95.00", tax = DevDataCatalogue.VAT_STANDARD_RATE),
                    ),
                    DevDataCatalogue.item(
                        "POS-002", InvoiceItemCategory.PRODUCT, "Hardware Rundungsgrenze",
                        amounts = DevDataCatalogue.ItemAmounts(qty = "3", unit = InvoiceItemUnit.PIECE, price = "49.99", tax = DevDataCatalogue.VAT_STANDARD_RATE),
                    ),
                ),
                terms = DevInvoiceTerms(
                    dueDate = today.plusDays(DevDataCatalogue.EDGE_DRAFT_DUE_DAYS),
                    reverseCharge = false,
                    freeText = "Edge-case draft with multiline notes and a service period crossing month boundaries.",
                ),
            ),
        )
    }

    /** IN_REVIEW – reverse charge EU. Split out of [seed], same reasoning as [seedDraftEdgeCase]. */
    private fun seedInReviewEdgeCase(sender: SenderProfile, euRc: Recipient, today: LocalDate) {
        saveNumbered(
            DevDataCatalogue.invoice(
                status = InvoiceStatus.IN_REVIEW,
                parties = DevDataCatalogue.DevInvoiceParties(sender, euRc),
                date = today.minusDays(IN_REVIEW_INVOICE_AGE_DAYS),
                items = listOf(
                    DevDataCatalogue.item(
                        DevDataCatalogue.POS_001, InvoiceItemCategory.SERVICE, "Retainer under reverse charge",
                        amounts = DevDataCatalogue.ItemAmounts(qty = "2", unit = InvoiceItemUnit.DAY, price = "800.00", tax = "0.00"),
                    ),
                ),
                terms = DevInvoiceTerms(
                    dueDate = today.plusDays(DevDataCatalogue.EDGE_IN_REVIEW_DUE_DAYS),
                    reverseCharge = true,
                    freeText = "Reverse-charge review invoice for EU customer.",
                ),
            ),
        )
    }

    /** SENT-turned-CANCELLED happy path with travel expenses, plus its storno/corrected-invoice
     *  document. Split out of [seed], same reasoning as [seedDraftEdgeCase]; kept as one function
     *  since the storno document references the first invoice's just-drawn number. */
    private fun seedCancelledPairWithTravel(sender: SenderProfile, domestic: Recipient, today: LocalDate) {
        val perDiemItem = DevDataCatalogue.perDiemItem()
        val finalInvoice = DevDataCatalogue.invoice(
            // ST-3 — CANCELLED, not SENT: the credit note right below references this invoice,
            // and an invoice that has been storno'd is CANCELLED by definition. The fixtures carry
            // plenty of other SENT invoices; this pair is the only place they show a storno.
            status = InvoiceStatus.CANCELLED,
            parties = DevDataCatalogue.DevInvoiceParties(sender, domestic),
            date = today.minusDays(CANCELLED_INVOICE_AGE_DAYS),
            items = listOf(
                DevDataCatalogue.item(
                    DevDataCatalogue.POS_001, InvoiceItemCategory.SERVICE, "Projektabschluss",
                    amounts = DevDataCatalogue.ItemAmounts(qty = "1", unit = InvoiceItemUnit.FLAT_RATE, price = "1250.00", tax = DevDataCatalogue.VAT_STANDARD_RATE),
                ),
                DevDataCatalogue.item(
                    "POS-002", InvoiceItemCategory.TRIP, "Auslandsreise Genf",
                    amounts = DevDataCatalogue.ItemAmounts(qty = "1", unit = InvoiceItemUnit.FLAT_RATE, price = "96.00", tax = DevDataCatalogue.VAT_STANDARD_RATE),
                ),
                perDiemItem.also { it.perDiemEntries.addAll(DevDataCatalogue.genfTravelEntries(it, today)) },
            ),
            terms = DevInvoiceTerms(
                dueDate = today,
                reverseCharge = false,
                freeText = "Cancelled invoice with travel expense edge cases; see its storno document.",
                validationStatus = ValidationStatus.VALID,
            ),
        )
        saveNumbered(finalInvoice)

        // CANCELLED – the storno document for the invoice above. ST-3: born terminal, never a
        // draft, numbered from the moment it exists. PO decision 1/2 (2026-09-02): CORRECTED_INVOICE
        // (`384`), not CREDIT_NOTE (`381`), and no due date/payment terms. N5 — references the
        // ORIGINAL's real, just-drawn number, not a fabricated literal.
        saveNumbered(
            DevDataCatalogue.invoice(
                status = InvoiceStatus.CANCELLED,
                parties = DevDataCatalogue.DevInvoiceParties(sender, domestic),
                date = today.minusDays(1),
                items = listOf(
                    DevDataCatalogue.item(
                        DevDataCatalogue.POS_001, InvoiceItemCategory.SERVICE, "Teilgutschrift Leistungsabweichung",
                        amounts = DevDataCatalogue.ItemAmounts(qty = "1", unit = InvoiceItemUnit.FLAT_RATE, price = "200.00", tax = DevDataCatalogue.VAT_STANDARD_RATE),
                    ),
                ),
                terms = DevInvoiceTerms(
                    dueDate = null,
                    reverseCharge = false,
                    typeCode = InvoiceTypeCode.CORRECTED_INVOICE,
                    originalInvoiceNumber = finalInvoice.invoiceNumber.value,
                    paymentTermsDays = 0,
                    freeText = "Storno/corrected-invoice edge case for the correction workflow.",
                    validationStatus = ValidationStatus.INVALID,
                ),
            ),
        )
    }

    /** VOIDED – discarded before it was ever sent, no credit note. N5 — still carries the number it
     *  drew as a draft (see InvoiceStateMachine.isIssued's KDoc). Split out of [seed], same
     *  reasoning as [seedDraftEdgeCase]. */
    private fun seedVoidedEdgeCase(sender: SenderProfile, publicRecipient: Recipient, today: LocalDate) {
        saveNumbered(
            DevDataCatalogue.invoice(
                status = InvoiceStatus.VOIDED,
                parties = DevDataCatalogue.DevInvoiceParties(sender, publicRecipient),
                date = today,
                items = emptyList(),
                terms = DevInvoiceTerms(
                    dueDate = today.plusDays(EDGE_VOIDED_DUE_DAYS),
                    reverseCharge = false,
                    freeText = "Voided edge case with unavailable validation backend.",
                    validationStatus = ValidationStatus.UNAVAILABLE,
                ),
            ),
        )
    }

    private fun cleanup() {
        // Delete travel_expense_entry rows referencing dev invoice items
        em.createNativeQuery("DELETE FROM travel_expense_entry WHERE invoice_item_id IN (SELECT ii.id FROM invoice_item ii JOIN invoice i ON ii.invoice_id = i.id JOIN sender_profile sp ON i.sender_profile_id = sp.id WHERE sp.email = ?)")
            .setParameter(1, DevDataCatalogue.DEV_SENDER_EMAIL)
            .executeUpdate()

        // Delete invoices (and cascade invoice_items)
        em.createNativeQuery("DELETE FROM invoice WHERE sender_profile_id IN (SELECT id FROM sender_profile WHERE email = ?)")
            .setParameter(1, DevDataCatalogue.DEV_SENDER_EMAIL)
            .executeUpdate()

        // Delete address rows tied to dev sender / recipients first to avoid FK violations
        em.createNativeQuery("DELETE FROM address WHERE sender_profile_id IN (SELECT id FROM sender_profile WHERE email = ?) OR recipient_id IN (SELECT id FROM recipient WHERE customer_number LIKE ?)")
            .setParameter(1, DevDataCatalogue.DEV_SENDER_EMAIL)
            .setParameter(2, "${DevDataCatalogue.DEV_CUSTOMER_PREFIX}%")
            .executeUpdate()

        // Delete sender profiles
        em.createNativeQuery("DELETE FROM sender_profile WHERE email = ?")
            .setParameter(1, DevDataCatalogue.DEV_SENDER_EMAIL)
            .executeUpdate()

        // Delete recipients
        em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE ?")
            .setParameter(1, "${DevDataCatalogue.DEV_CUSTOMER_PREFIX}%")
            .executeUpdate()

        // Clear the first-level cache so subsequent loads get a fresh snapshot from DB.
        // Without this, any entity already in the session (e.g. the datagen default sender)
        // could have a null @Version, causing an NPE when Hibernate tries to auto-flush.
        em.flush()
        em.clear()
    }
}
