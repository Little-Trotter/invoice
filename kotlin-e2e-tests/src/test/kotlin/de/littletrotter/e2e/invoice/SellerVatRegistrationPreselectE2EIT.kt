package de.littletrotter.e2e.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._walkAll
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.LlmAnalysisFeatureConfig
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.e2e.support.buildInvoiceDetailView
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.LlmCheckAvailability
import de.littletrotter.ui.view.invoice.ReverseChargeField
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * End-to-end proof that the real [InvoiceDetailView] entry pane (Karibu-driven, same idiom as
 * [de.littletrotter.e2e.profile.NewFieldEndToEndE2EIT]'s "SHOWN" step) actually pre-selects a
 * seller VAT registration from the recipient's country, that the choice survives the real save
 * path onto the persisted [Invoice], and that the generated e-invoice XML carries the pre-selected
 * registration's VAT id (BT-31) rather than the seller's home one — not merely that the pure
 * `resolveSellerVatRegistrationCountry` function returns the right value (that is
 * `TaxRuleUiSupportTest`'s job, in kotlin-invoice, exhaustively).
 *
 * Two cases, matching the spec's own two E2E bullets:
 * - A Luxembourg recipient: `sellerVatCountryBox` opens pre-selected to LU, the real "Speichern"
 *   button persists that onto [Invoice.sellerVatRegistrationCountry], and the Mustang-generated
 *   XML's BT-31 (`SellerTradeParty/SpecifiedTaxRegistration`) reads the LU registration's own VAT
 *   id, not the seller's German [SenderProfile.vatId].
 * - A German (home) recipient: the field opens showing the visible DE default entry — never an
 *   empty combo; `Country.DE` is a presentation-only sentinel mapped back
 *   to `null` in the binder — the real "Speichern" button still persists `null` onto
 *   [Invoice.sellerVatRegistrationCountry], the exported XML's BT-31 carries the seller's German
 *   home [SenderProfile.vatId], and reverse charge stays disabled for its pre-existing, unrelated
 *   reason (a domestic recipient is never Reverse-Charge-eligible) rather than the
 *   `sellerVatRegistrationCountry` guard — proving the visible default did not change the
 *   domestic invoice's persisted state, export or reverse-charge semantics.
 *
 * Every `SenderProfile`/`Recipient`/`Invoice` row this suite inserts is tagged and removed again in
 * [cleanup] — this module's Postgres is shared and never reset between suites/tests.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class SellerVatRegistrationPreselectE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    @Inject
    lateinit var paymentTermQueries: PaymentTermQueryPort

    @Inject
    lateinit var b2bProfileQueries: B2bProfileQueryPort

    @Inject
    lateinit var b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort

    @Inject
    lateinit var b2bFieldValueQueries: B2bFieldValueQueryPort

    @Inject
    lateinit var validationSettingsGateBean: ValidationSettingsGate

    // The entry pane reads parties and saves the header through these ports; real, Postgres-backed
    // beans so the entry workspace under test runs the actual read and save path.
    @Inject
    lateinit var recipientQueryPort: RecipientQueryPort

    @Inject
    lateinit var senderQueryPort: SenderQueryPort

    @Inject
    lateinit var editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase

    @Inject
    lateinit var invoiceDetailQueryPort: InvoiceDetailQueryPort

    private val mustang = XmlExportServiceImpl(null)

    private val createdInvoiceIds = mutableListOf<Long>()
    private val createdRecipientIds = mutableListOf<Long>()
    private val createdSenderIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            createdRecipientIds.forEach { em.find(Recipient::class.java, it)?.let(em::remove) }
            createdSenderIds.forEach { em.find(SenderProfile::class.java, it)?.let(em::remove) }
        }
    }

    private fun newInvoiceDetailView(): InvoiceDetailView {
        // The Belege tab unconditionally asks for the invoice's document budget on beforeEnter;
        // an unstubbed mock would return null into a non-null Kotlin parameter (budgetHint). Same
        // empty-budget stub every kotlin-invoice Karibu suite uses.
        val invoiceReceiptQueryPort = mock<InvoiceReceiptQueryPort>().also {
            whenever(it.invoiceDocumentBudget(org.mockito.kotlin.any()))
                .thenReturn(de.littletrotter.shared.receipt.InvoiceDocumentBudget.calculate(emptyList()))
        }
        // Real, CDI-injected, Postgres-backed facades and entry-pane ports (parties, header save,
        // detail reads) — the path under test here. The Versionen tab, the Belege tab and the
        // Positionen tab are never exercised, so their remaining collaborators stay mocked.
        return buildInvoiceDetailView(
            taxRateQueries = taxRateQueries,
            paymentTermQueries = paymentTermQueries,
            b2bProfileQueries = b2bProfileQueries,
            b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
            b2bFieldValueQueries = b2bFieldValueQueries,
            generationJobQueryService = mock(),
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            recipientQueryPort = recipientQueryPort,
            invoiceReceiptQueryPort = invoiceReceiptQueryPort,
            attachReceiptsUseCase = mock(),
            senderQueryPort = senderQueryPort,
            editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
            generationJobCreationService = mock(),
            llmCheckAvailability = LlmCheckAvailability(validationSettingsGateBean, LlmAnalysisFeatureConfig(enabled = true)),
            validationSettingsGate = validationSettingsGateBean,
        )
    }

    private fun beforeEnterEvent(invoiceId: Long): BeforeEnterEvent {
        val event: BeforeEnterEvent = mock()
        whenever(event.routeParameters).thenReturn(RouteParameters(mapOf("id" to invoiceId.toString())))
        whenever(event.location).thenReturn(Location("invoice/$invoiceId"))
        return event
    }

    private fun flatten(component: Component): List<Component> = component._walkAll().toList()

    /** [TabSheet] does not attach a non-selected tab's content as a discoverable child at all —
     *  same caveat [NewFieldEndToEndE2EIT.allTabContents] documents; duplicated here rather than
     *  shared (every suite in this package is self-contained by convention). */
    private fun TabSheet.allTabContents(): List<Component> = (0 until tabCount).map { getComponent(getTabAt(it)) }

    /** Enters the real entry pane via the "Bearbeiten" button (the only public entry point —
     *  `buildEntryPane` is `internal`, invisible from this module) and returns its own TabSheet
     *  ("Rechnung"/"E-Rechnung"). */
    private fun enterEditMode(view: InvoiceDetailView): TabSheet {
        val outerTabSheet = flatten(view).filterIsInstance<TabSheet>().single()
        val overviewPanel = outerTabSheet.allTabContents()
            .flatMap { flatten(it) }
            .filterIsInstance<de.littletrotter.ui.view.invoice.InvoiceOverviewPanel>().single()
        val editButton = flatten(overviewPanel).filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("invoiceDetail.overview.edit") }
        editButton.click()
        return flatten(view).filterIsInstance<TabSheet>().single()
    }

    // Only ever read for its value (never set), so a star-projected handle is enough — no cast
    // to the concrete ComboBox<Country> needed at all.
    private fun sellerVatCountryBoxOn(einvoiceTabContent: Component): ComboBox<*> =
        flatten(einvoiceTabContent).filterIsInstance<ComboBox<*>>()
            .single { it.label == UiTranslations.tr("invoiceDetail.sellerVatRegistrationCountry") }

    private fun saveButtonOn(view: InvoiceDetailView): Button =
        flatten(view).filterIsInstance<Button>().single { it.text == UiTranslations.tr("app.save") }

    /** Sets a star-projected [HasValue]'s value as if the client selected it — the same
     *  isFromClient=true path karibu-testing's own `_setValue` uses internally — via plain
     *  reflection into [com.vaadin.flow.component.AbstractField]'s private `fieldSupport`.
     *  Karibu's own `_setValue` is generic over the field's value type, so it cannot be called on
     *  a star-projected [box] either — narrowing a `ComboBox<*>` found in the rendered tree back
     *  to its real item type is always an unchecked generic cast (JVM erasure loses it, whatever
     *  that type's own visibility is), so this reaches the field's setter through reflection
     *  instead of ever attempting that cast. */
    private fun simulateClientSelect(box: com.vaadin.flow.component.HasValue<*, *>, value: Any?) {
        val fieldSupport = com.vaadin.flow.component.AbstractField::class.java
            .getDeclaredField("fieldSupport").apply { isAccessible = true }
            .get(box)
        fieldSupport.javaClass
            .getDeclaredMethod("setValue", Any::class.java, Boolean::class.java, Boolean::class.java)
            .apply { isAccessible = true }
            .invoke(fieldSupport, value, false, true)
    }

    // The reverse-charge decision is a ReverseChargeField (a CustomField<Boolean>, binary,
    // never null); it is the only instance of its type in the entry pane.
    private fun reverseChargeBoxOn(invoiceTabContent: Component): ReverseChargeField =
        flatten(invoiceTabContent).filterIsInstance<ReverseChargeField>().single()

    // ─── Fixture — a seller with one LU registration, alongside its mandatory DE home vatId ──────

    private fun senderWithLuRegistration(tag: String): SenderProfile {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        sender.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), java.time.LocalDate.now())
        return sender
    }

    // ─── Case 1 — LU recipient: pre-selected, saved, and the LU vatId reaches the wire ───────────

    @Test
    fun `a Luxembourg recipient opens with the LU registration pre-selected, saves it and the generated document carries the LU VAT id`() {
        val (senderId, recipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithLuRegistration("SVPL")
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("SVPL-100")
                name = "SVPL Michels S.A."
                email = Email.ofNullable("ap@svpl.example")
                vatId = VatId("LU21416127")
                customerType = CustomerType.BUSINESS
                routingId = "PO-SVPL-1"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
            em.persist(sender); em.persist(recipient); em.flush()
            val invoiceId = lifecycleService.create(sender.id!!, recipient.id!!).id!!
            Triple(sender.id!!, recipient.id!!, invoiceId)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId

        MockVaadin.setup()
        try {
            val view = newInvoiceDetailView()
            view.beforeEnter(beforeEnterEvent(invoiceId))
            val entryTabSheet = enterEditMode(view)
            // reverseChargeBox lives on Tab 1 ("Rechnung", the default-selected tab); grab it
            // before switching (a held reference stays valid and its listeners keep firing
            // regardless of which tab is currently attached).
            val reverseChargeBox = reverseChargeBoxOn(entryTabSheet.allTabContents().first())
            entryTabSheet.selectedIndex = 1 // "E-Rechnung" — sellerVatCountryBox lives there.
            val einvoiceTabContent = entryTabSheet.allTabContents()[1] // "E-Rechnung" is tab index 1 (invoice/E-Rechnung/Notizen).

            val box = sellerVatCountryBoxOn(einvoiceTabContent)
            assertEquals(Country.LU, box.value, "opening an invoice with no stored value must pre-select the seller's LU registration for an LU recipient")

            // The seller VAT country the field compares against is sellerVatCountryBox's
            // own value, i.e. the just-asserted LU pre-selection above, not the seller's German
            // home country — so this LU-recipient invoice is a same-country (LU/LU) pair and the
            // field must be hidden. (This also matches the domain rule directly: an active
            // foreign seller VAT registration makes taxRuleResolutionRequestOrNull — and with it
            // Reverse-Charge eligibility — return null; see its KDoc.)
            assertEquals(false, reverseChargeBox.isVisible, "an LU seller registration against an LU recipient is a same-country pair — the field must be hidden")
            assertEquals(false, reverseChargeBox.value, "a hidden field must carry No")

            saveButtonOn(view).click()
        } finally {
            MockVaadin.tearDown()
        }

        val persisted = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.sellerVatRegistrationCountry }
        assertEquals(Country.LU, persisted, "the real Speichern button must persist the pre-selected registration")
        val persistedReverseCharge = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.reverseCharge }
        assertEquals(false, persistedReverseCharge, "B4/B9 — the hidden-or-untouched field must never leave a null/stale decision; here it is an explicit No")

        // Give the invoice a real position under the now-LU seller-registration context, using the
        // baseline-seeded LU_STANDARD_17 rate (V2__baseline_seed.sql) — same tax rate the existing
        // ItemTaxRateForeignSellerE2EIT suite already exercises for a foreign-seller position.
        val luRate = taxRateQueries.findAllTaxRates().find { it.code == "LU_STANDARD_17" }
            ?: error("expected seeded TaxRate 'LU_STANDARD_17' (V2__baseline_seed.sql) to exist")
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "SVPL Consulting",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = luRate.id,
                ),
            )
        }
        // finding-fixes idiom (EInvoiceGenerationE2EIT/NewFieldEndToEndE2EIT) — CORE.SERVICE_PERIOD_XOR
        // and the send-date guard are cheap to satisfy directly, unrelated to what this suite proves.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        val dto: InvoiceDto = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.SENT, invoice.status, "the fixture must actually finalize for this suite to mean anything")
            InvoiceDtoMapper().mapToDto(invoice)
        }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)

        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", "LU12345678",
            "SVPL/BT-31 (the LU registration, not the seller's German home vatId)",
        )
    }

    // ─── Case 2 — DE (home) recipient: visible DE default, null persisted, DE vatId on the wire ──

    @Test
    fun `a German recipient shows the visible DE default entry, persists null and exports BT-31 with the German home VAT id`() {
        val (senderId, recipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithLuRegistration("SVPD")
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("SVPD-100")
                name = "SVPD Empfänger AG"
                email = Email.ofNullable("ap@svpd.example")
                vatId = VatId("DE256256256")
                customerType = CustomerType.BUSINESS
                routingId = "PO-SVPD-1"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender); em.persist(recipient); em.flush()
            val invoiceId = lifecycleService.create(sender.id!!, recipient.id!!).id!!
            Triple(sender.id!!, recipient.id!!, invoiceId)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId; createdInvoiceIds += invoiceId

        MockVaadin.setup()
        try {
            val view = newInvoiceDetailView()
            view.beforeEnter(beforeEnterEvent(invoiceId))
            // reverseChargeBox lives on Tab 1 ("Rechnung", the default-selected tab). `TabSheet
            // .allTabContents` (getComponent, not a tree walk) is the only reliable way to reach
            // a tab's content headlessly here — same reasoning as [NewFieldEndToEndE2EIT
            // .allTabContents]'s own KDoc: a plain `flatten(view)` walk never descends into ANY
            // tab's content at all, selected or not (confirmed empirically: it found the always-
            // attached "Speichern"/"Zurück" buttons that sit *outside* the TabSheet, but zero
            // ComboBoxes or Checkboxes from either tab).
            val entryTabSheet = enterEditMode(view)
            val reverseChargeBox = reverseChargeBoxOn(entryTabSheet.allTabContents().first())
            entryTabSheet.selectedIndex = 1
            val einvoiceTabContent = entryTabSheet.allTabContents()[1] // "E-Rechnung" is tab index 1 (invoice/E-Rechnung/Notizen).

            val box = sellerVatCountryBoxOn(einvoiceTabContent)
            assertEquals(
                Country.DE, box.value,
                "PO 2026-09-03 — a domestic recipient shows the visible DE default entry, never an empty combo",
            )

            // A German (home) recipient is domestic against the seller's own DE default:
            // the field must be hidden and carry No, both on open and after the real save.
            assertEquals(false, reverseChargeBox.isVisible, "a domestic seller/recipient VAT country pair must hide the field")
            assertEquals(
                false, reverseChargeBox.value,
                "reverse charge must stay at its own pre-existing decision (unanswered/false), " +
                    "never changed because the visible DE default populated sellerVatRegistrationCountry",
            )

            // The critical constraint's save leg: the DE sentinel goes through the real
            // "Speichern" path and must land as null — NOT as a persisted 'DE' country value.
            saveButtonOn(view).click()
        } finally {
            MockVaadin.tearDown()
        }

        val persisted = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.sellerVatRegistrationCountry }
        assertNull(persisted, "the visible DE default entry must persist as null — the unchanged German-default encoding")

        // Export leg — a plain domestic 19 % position, then the wire format: BT-31 must carry the
        // seller's German home vatId regardless of the visible DE default.
        val deRate = taxRateQueries.findAllTaxRates().find { it.code == "DE_STANDARD_19" }
            ?: error("expected seeded TaxRate 'DE_STANDARD_19' (V2__baseline_seed.sql) to exist")
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "SVPD Consulting",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = deRate.id,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        val dto: InvoiceDto = QuarkusTransaction.requiringNew().call {
            InvoiceDtoMapper().mapToDto(em.find(Invoice::class.java, invoiceId)!!)
        }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)

        EInvoiceXml.assertText(
            xml, "//ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", "DE111111111",
            "SVPD/BT-31 (the seller's German home vatId — the domestic export is unchanged by the visible DE default)",
        )
    }

    // ─── Case 3 — a Yes decided at invoice creation survives an untouched save, and only a
    // real recipient change (to a domestic one) hides the field and resets it to No ─────────────

    @Test
    fun `an invoice created with Yes reverse charge survives an untouched save, then resets to No when the recipient becomes domestic`() {
        val (senderId, crossBorderRecipientId, domesticRecipientId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                phone = "+49 30 1234567"
                name = "B4 Sender GmbH"
                invoicePrefix = "B4-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@b4.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val crossBorderRecipient = Recipient().apply {
                customerNumber = CustomerNumber("B4-100")
                name = "B4 Cross-Border SARL"
                email = Email.ofNullable("ap@b4-fr.example")
                vatId = VatId("FR12345678901")
                customerType = CustomerType.BUSINESS
                vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
                routingId = "PO-B4-1"
            }
            crossBorderRecipient.addAddress(
                Address.forRecipient(crossBorderRecipient, Address.AddressType.BILLING, "Rue Exemple 1", "75000", "Paris", Country.FR, true),
            )
            val domesticRecipient = Recipient().apply {
                customerNumber = CustomerNumber("B4-200")
                name = "B4 Domestic GmbH"
                email = Email.ofNullable("ap@b4-de.example")
                vatId = VatId("DE256256987")
                customerType = CustomerType.BUSINESS
                routingId = "PO-B4-2"
            }
            domesticRecipient.addAddress(
                Address.forRecipient(domesticRecipient, Address.AddressType.BILLING, "Kundenallee 3", "20095", "Hamburg", Country.DE, true),
            )
            em.persist(sender); em.persist(crossBorderRecipient); em.persist(domesticRecipient); em.flush()
            Triple(sender.id!!, crossBorderRecipient.id!!, domesticRecipient.id!!)
        }
        createdSenderIds += senderId
        createdRecipientIds += crossBorderRecipientId
        createdRecipientIds += domesticRecipientId
        val invoiceId = QuarkusTransaction.requiringNew()
            .call { lifecycleService.create(senderId, crossBorderRecipientId, reverseChargeDecision = true).id!! }
        createdInvoiceIds += invoiceId

        // Open + save untouched: the creation dialog's Yes must not silently flip.
        MockVaadin.setup()
        try {
            val view = newInvoiceDetailView()
            view.beforeEnter(beforeEnterEvent(invoiceId))
            val entryTabSheet = enterEditMode(view)
            val reverseChargeBox = reverseChargeBoxOn(entryTabSheet.allTabContents().first())

            assertEquals(true, reverseChargeBox.isVisible, "cross-border seller/recipient VAT countries must show the field")
            assertEquals(true, reverseChargeBox.value, "a Yes decided at creation time must not silently flip on open")

            saveButtonOn(view).click()
        } finally {
            MockVaadin.tearDown()
        }
        assertEquals(
            true, QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.reverseCharge },
            "an untouched save must not flip the creation-time Yes",
        )

        // A real, client-driven recipient change to a domestic one hides the field, resets it
        // to No, and that persists on save.
        MockVaadin.setup()
        try {
            val view = newInvoiceDetailView()
            view.beforeEnter(beforeEnterEvent(invoiceId))
            val entryTabSheet = enterEditMode(view)
            val invoiceTabContent = entryTabSheet.allTabContents().first()
            val recipientBox = flatten(invoiceTabContent).filterIsInstance<ComboBox<*>>()
                .single { it.label == UiTranslations.tr("invoiceDetail.summary.name") }
            val reverseChargeBox = reverseChargeBoxOn(invoiceTabContent)

            // The box holds recipient summaries, so select the persisted domestic recipient exactly
            // as the port hands it to the UI. Its routingId (BT-10) matters: the recipientBox
            // listener re-suggests it into buyerReferenceField, and with a routingId present the
            // guided-mode trigger (einvoiceGuidedModeTrigger) requires it non-blank — without it
            // the "Rechnungsreferenz" guard would silently abort the save below before it ever
            // reaches the header save.
            val domesticSummary = requireNotNull(recipientQueryPort.findRecipientById(domesticRecipientId)) {
                "the domestic recipient was persisted above"
            }
            simulateClientSelect(recipientBox, domesticSummary)

            assertEquals(false, reverseChargeBox.isVisible, "a domestic recipient must hide the field")
            assertEquals(false, reverseChargeBox.value, "switching to a domestic recipient must reset the decision to No")

            saveButtonOn(view).click()
        } finally {
            MockVaadin.tearDown()
        }
        assertEquals(
            false, QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.reverseCharge },
            "the reset to No triggered by the recipient change must persist",
        )
    }
}
