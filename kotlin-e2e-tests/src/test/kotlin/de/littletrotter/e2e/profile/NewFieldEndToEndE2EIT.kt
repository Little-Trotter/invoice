package de.littletrotter.e2e.profile

import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.application.reference.B2bProfileUsageService
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._walkAll
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.invoice.B2bProfileRuleSet
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
import de.littletrotter.e2e.support.buildInvoiceDetailView
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.compliance.B2bProfileRules
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.e2e.support.buildInvoiceDetailView
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.LlmCheckAvailability
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import de.littletrotter.xpdf.validation.CiiSchematronValidator
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * Proves the critical B2B-profile chain end to end, on a genuinely NEW catalog field added to a
 * profile: **shown** (the real [InvoiceDetailView] entry pane, Karibu-driven) -> **validated**
 * (the finalization gate, [B2bProfileRules]) -> **mapped** (the Mustang e-invoice export, XPath
 * against the real EN-16931 position, KoSIT) -> **negative proof** (a field NOT in the profile
 * never reaches the XML). Reuses the shared profile fixtures ([B2bProfileLifecycleService]) and the
 * e-invoice generation plumbing ([EInvoiceXml], [EmbeddedKositValidator], [CiiSchematronValidator] — same idiom as [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT], duplicated
 * locally rather than shared across files: every suite in this package is self-contained, matching
 * [B2bProfileE2EIT]'s own fixture-per-file convention).
 *
 * The two catalog terms exercised are picked to prove the whole chain, not just one field:
 * - **BT-12** (contract reference) — HEADER, FREE, TEXT — the profile's MANDATORY field.
 * - **BT-18** (invoiced object identifier) — HEADER, FREE, TEXT — CONDITIONAL_IF_EMPTY on BT-12,
 *   guarding against a regression where this branch of [B2bProfileRules] would fire regardless of
 *   BT-12's actual state; this suite's own fill/re-evaluate step would fail if that guard were
 *   vacuously true again.
 * - **BT-58** (buyer contact email) — deliberately never added to the profile: the negative-mapping
 *   proof (step 5).
 *
 * Neither BT-12 nor BT-18 is exercised by the shared e-invoice generation suite (which covers
 * totals/parties/tax, not the catalog's FREE header terms), so this suite adds genuinely new XPath
 * coverage rather than duplicating it.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@TestProfile(ComplianceBlockStageProfile::class)
class NewFieldEndToEndE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var b2bProfileLifecycleService: B2bProfileLifecycleService

    @Inject
    lateinit var b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    @Inject
    lateinit var b2bProfileUsageService: B2bProfileUsageService

    @Inject
    lateinit var b2bProfileRuleSet: B2bProfileRuleSet

    @Inject
    lateinit var b2bProfileQueries: B2bProfileQueryPort

    @Inject
    lateinit var paymentTermQueries: PaymentTermQueryPort

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

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

    companion object {
        /** Same embedded, real KoSIT validator this package's suites share across their own test methods. */
        private lateinit var kosit: EmbeddedKositValidator
        private lateinit var schematron: CiiSchematronValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
            schematron = CiiSchematronValidator().apply { init() }
        }
    }

    private val mustang = XmlExportServiceImpl(null)

    private fun mustangXml(dto: InvoiceDto): String = String(mustang.generate(dto), Charsets.UTF_8)

    private fun assertKositValid(xml: String, label: String) {
        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "$label: KoSIT must be VALID, was ${result.status} — ${result.details}")
        val schematronResult = schematron.validate(xml.toByteArray(Charsets.UTF_8))
        assertEquals(KositOutcome.VALID, schematronResult.outcome, "$label: CII Schematron must be VALID, was ${schematronResult.outcome} - ${schematronResult.findings}")
    }

    // ─── Fixture — profile build and standing recipient assignment ──────────────────────────────

    private data class Fixture(
        val invoiceId: Long,
        val profileId: Long,
        val bt12Label: String,
        val bt18Label: String,
    )

    /**
     * Builds one B2B profile with the two new FREE header fields (BT-12 MANDATORY, BT-18
     * CONDITIONAL_IF_EMPTY on BT-12), assigns it to a fresh recipient
     * ([Recipient.b2bProfile]), then creates an invoice for that recipient through the real
     * [InvoiceLifecycleService.create] — confirming that the invoice derives its own B2B
     * profile from the recipient's assignment at creation time, rather than assuming it the way
     * [B2bProfileE2EIT]'s own fixture deliberately does (see that class's KDoc). One SERVICE
     * position is added through the real [InvoiceLifecycleService.addItem] so the invoice is
     * finalizable and mappable.
     */
    private fun buildFixture(tag: String): Fixture {
        val bt12Label = "Vertragsreferenz ($tag)"
        val bt18Label = "Belegobjekt-Kennung ($tag)"

        val profile = b2bProfileLifecycleService.saveProfile(B2bProfile().edit { name = "$tag-Profil"; description = "EP-3 new-field fixture" })
        val profileId = profile.id!!
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                this.profile = profile
                term = B2bTerm.BT_12
                businessDefinition = bt12Label
                requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.INVOICE
                sortOrder = 1
            },
        )
        b2bProfileFieldDefinitionService.saveField(
            B2bProfileField().edit {
                this.profile = profile
                term = B2bTerm.BT_18
                businessDefinition = bt18Label
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY
                conditionTerm = B2bTerm.BT_12
                placement = B2bPlacement.INVOICE
                sortOrder = 2
            },
        )

        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "EP-3 Sender $tag GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
                phone = "+49 30 1234567"
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "EP-3 Empfänger $tag AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                // The recipient carries the standing profile assignment; the invoice
                // derives its own from this the moment it is created (checked below).
                b2bProfile = em.getReference(B2bProfile::class.java, profileId)
                // create()'s BT-10 default reads this — see B2bProfileE2EIT's own fixture KDoc for
                // why a real buyerReference must exist regardless of the B2B profile under test
                // (BR-DE-15 is an always-on gate).
                routingId = "PO-$tag-STANDING"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().edit {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        // The service-period XOR compliance rule (CORE.SERVICE_PERIOD_XOR, BLOCK stage via
        // ComplianceBlockStageProfile) is not what this scenario exercises, so satisfy it
        // cheaply with BT-72.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 1, 15) } }

        QuarkusTransaction.requiringNew().run {
            assertEquals(
                profileId, em.find(Invoice::class.java, invoiceId)!!.b2bProfile?.id,
                "AP-9 rule 1: the invoice must derive its B2B profile from the recipient's own assignment at creation",
            )
        }

        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "EP-3 Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }

        return Fixture(invoiceId, profileId, bt12Label, bt18Label)
    }

    // ─── Step 2 — SHOWN: the real InvoiceDetailView entry pane, Karibu-driven ────────────────────

    /**
     * Karibu-Testing's own [_walkAll] rather than a hand-rolled `HasComponents`-gated tree walk
     * (the idiom [de.littletrotter.ui.view.invoice.InvoiceDetailViewValidationBannerTest],
     * kotlin-invoice, uses for its simpler, TabSheet-free trees): this suite's entry pane nests
     * [TabSheet]s (the "Rechnung"/"E-Rechnung" tabs, and the guided
     * B2B stepper inside the latter) — `TabSheet` does not implement `HasComponents` (only
     * `add(caption, content)`/`getComponent(tab)`), so a `HasComponents`-gated walk silently stops
     * at every `TabSheet` boundary and never finds anything inside a tab (reproduced empirically:
     * the "Bearbeiten" button and the B2B fields were both invisible to that walk). `_walkAll`
     * traverses the real Vaadin element tree instead, which is exactly what it exists for.
     */
    private fun flatten(component: Component): List<Component> = component._walkAll().toList()

    private fun newInvoiceDetailView(): InvoiceDetailView {
        // The Belege tab fetches the invoice-wide budget unconditionally on every render; an
        // unstubbed mock returns null there and NPEs (same blanket stub every kotlin-invoice
        // Karibu test that builds the real view already carries).
        val invoiceReceiptQueryPort = mock<InvoiceReceiptQueryPort>().apply {
            whenever(invoiceDocumentBudget(any()))
                .thenReturn(de.littletrotter.shared.receipt.InvoiceDocumentBudget.calculate(emptyList()))
        }
        // Real, CDI-injected, Postgres-backed facades and entry-pane ports (parties, header save,
        // detail reads) — the path under test here. The Versionen tab, the Belege tab and the
        // Positionen tab are never exercised, so their remaining collaborators stay mocked.
        return buildInvoiceDetailView(
            taxRateQueries = taxRateQueries,
            generationJobQueryPort = mock(),
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            recipientQueryPort = recipientQueryPort,
            invoiceReceiptQueryPort = invoiceReceiptQueryPort,
            attachReceiptsUseCase = mock(),
            senderQueryPort = senderQueryPort,
            editInvoiceHeaderUseCase = editInvoiceHeaderUseCase,
            paymentTermQueries = paymentTermQueries,
            b2bProfileQueries = b2bProfileQueries,
            b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
            b2bFieldValueQueries = b2bFieldValueQueries,
            generationJobCreationService = mock(),
            llmCheckAvailability = LlmCheckAvailability(validationSettingsGateBean, LlmAnalysisFeatureConfig(enabled = true)),
        )
    }

    private fun beforeEnterEvent(invoiceId: Long): BeforeEnterEvent {
        val event: BeforeEnterEvent = mock()
        org.mockito.kotlin.whenever(event.routeParameters).thenReturn(RouteParameters(mapOf("id" to invoiceId.toString())))
        org.mockito.kotlin.whenever(event.location).thenReturn(Location("invoice/$invoiceId"))
        return event
    }

    /**
     * [TabSheet] does not attach a non-selected tab's content as a
     * discoverable child at all (confirmed empirically: `_walkAll`/a plain tree walk find the
     * `TabSheet` and its `Tab` header instances but none of the four tabs' own content, even for
     * the initially-selected one) — its own [TabSheet.getComponent] is the one reliable way to
     * reach a tab's content headlessly, so this suite uses that instead of hoping a tree walk finds
     * it.
     */
    private fun TabSheet.allTabContents(): List<Component> =
        (0 until tabCount).map { getComponent(getTabAt(it)) }

    // Callers must walk INTO each tab content, never assume a panel IS the content: the outer tab
    // contents are stable wrapper Divs whose children get swapped, and removing and re-adding the
    // tabs on every refresh re-selects index 0, which leaves the Versionen tab's Grid sizing itself
    // to zero rows inside a hidden container. A `filterIsInstance` straight over the tab contents
    // finds nothing — an implementation detail these tests never meant to assert in the first place.

    @Test
    fun `EP-3 shown - the entry pane renders the new HEADER fields via the real InvoiceDetailView, respecting requirement and placement`() {
        val fixture = buildFixture("EP3S")

        MockVaadin.setup()
        try {
            val view = newInvoiceDetailView()
            view.beforeEnter(beforeEnterEvent(fixture.invoiceId))

            // Overview mode first — the real "Bearbeiten" button is the only public entry point
            // into the entry pane (InvoiceDetailView.enterEntryMode/buildEntryPane are `internal`,
            // invisible from this module — see this class's KDoc). It sits on the "Übersicht" tab
            // (index 0) of the outer TabSheet, reached via TabSheet.getComponent (see
            // allTabContents' KDoc) rather than a tree walk.
            val outerTabSheet = flatten(view).filterIsInstance<TabSheet>().single()
            val overviewPanel = outerTabSheet.allTabContents()
                .flatMap { flatten(it) }
                .filterIsInstance<de.littletrotter.ui.view.invoice.InvoiceOverviewPanel>().single()
            val editButton = flatten(overviewPanel).filterIsInstance<Button>()
                .single { it.text == UiTranslations.tr("invoiceDetail.overview.edit") }
            editButton.click()

            // Entry mode now: InvoiceDetailView.renderInvoice replaced the whole tree with the
            // entry pane, itself another TabSheet ("Rechnung"/"E-Rechnung" — see
            // InvoiceDetailViewOverview.buildEntryPane), same non-attached-content caveat applies.
            val entryTabSheet = flatten(view).filterIsInstance<TabSheet>().single()
            // Select the "E-Rechnung" tab for real (index 1) — a click's server-side listener
            // only fires reliably once its component is the actually-selected tab's content.
            entryTabSheet.selectedIndex = 1
            val einvoiceTabContent = entryTabSheet.allTabContents()[1] // "E-Rechnung" is tab index 1 (invoice/E-Rechnung/Notizen).

            // The E-Rechnung tab is a guided stepper (InvoiceDetailViewEinvoiceTab.showStep):
            // only the CURRENTLY ACTIVE step's content is attached to the tree at all — the
            // others are rebuilt in on "Weiter" (`stepContentContainer.removeAll(); add(...)`),
            // not merely hidden. An effective B2B profile always starts on step 01 "Referenzen";
            // one "Weiter" click reaches step 02 "Profilfelder", where b2bHeaderSection lives.
            flatten(einvoiceTabContent).filterIsInstance<Button>()
                .single { it.text == UiTranslations.tr("invoiceDetail.entry.einvoice.next") }
                .click()

            val section = flatten(einvoiceTabContent).filterIsInstance<B2bProfileFieldsSection>().single()
            val textFields = flatten(section).filterIsInstance<TextField>()

            val bt12Field = textFields.single { it.label == fixture.bt12Label }
            val bt18Field = textFields.single { it.label == fixture.bt18Label }
            assertTrue(
                textFields.none { it.label?.contains("BT-58") == true || it.label == "Käufer-Kontakt-E-Mail" },
                "BT-58 is not part of this profile: no widget for it must render at all",
            )

            assertTrue(bt12Field.isRequiredIndicatorVisible, "BT-12 is MANDATORY: the entry pane must flag it required")
            // Known UI contract gap (not a production-code fix in scope here): the
            // widget factory (B2bFieldWidgetFactory.createWidget) only ever flags MANDATORY as
            // required — CONDITIONAL_IF_EMPTY never gets the visual indicator even while its
            // trigger is currently empty (i.e. currently mandatory per B2bProfileRules). The field
            // still renders (placement/presence honored), just without the live required cue.
            assertFalse(
                bt18Field.isRequiredIndicatorVisible,
                "documented gap EP3-F1: CONDITIONAL_IF_EMPTY never shows the required indicator, even though B2bProfileRules " +
                    "currently requires it while BT-12 is empty",
            )
        } finally {
            MockVaadin.tearDown()
        }
    }

    // ─── Step 3 — VALIDATED: MANDATORY blocks by name, CONDITIONAL_IF_EMPTY fires and clears ────

    @Test
    fun `EP-3 validated - MANDATORY blocks naming its BT code, CONDITIONAL_IF_EMPTY fires while empty and clears once filled`() {
        val fixture = buildFixture("EP3V")

        // Both BT-12 (own MANDATORY) and BT-18 (CONDITIONAL_IF_EMPTY, trigger BT-12 empty) must
        // fire simultaneously — the "empty trigger" branch of B2bProfileRules.evaluate.
        val findingsBothEmpty = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, fixture.invoiceId)!!)
        }
        val mandatoryFindings = findingsBothEmpty.filter { it.ruleId == B2bProfileRules.MANDATORY_FIELD }
        assertEquals(2, mandatoryFindings.size, "BT-12 and BT-18 must both fire while BT-12 is empty: $mandatoryFindings")
        assertTrue(mandatoryFindings.any { it.businessTerms == listOf(B2bTerm.BT_12) }, "BT-12's own MANDATORY finding is missing")
        assertTrue(
            mandatoryFindings.any { it.businessTerms == listOf(B2bTerm.BT_18, B2bTerm.BT_12) },
            "BT-18's CONDITIONAL_IF_EMPTY finding (naming its trigger BT-12) is missing",
        )

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(fixture.invoiceId, InvoiceStatus.IN_REVIEW) }
        val blocked = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(fixture.invoiceId, InvoiceStatus.ISSUED) }
        }
        val blockingB2bFindings = blocked.findings.filter { it.ruleId == B2bProfileRules.MANDATORY_FIELD }
        assertEquals(2, blockingB2bFindings.size, "the finalization gate must block on both findings")
        assertTrue(blockingB2bFindings.any { it.args?.contains("BT-12") == true }, "the gate must name BT-12")
        assertTrue(blockingB2bFindings.any { it.args?.contains("BT-18") == true }, "the gate must name BT-18")
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.IN_REVIEW, em.find(Invoice::class.java, fixture.invoiceId)!!.status, "a blocked attempt persists nothing")
        }

        // Fill ONLY the trigger (BT-12) — BT-18's CONDITIONAL_IF_EMPTY requirement must clear too,
        // proving the branch reacts to live state rather than being vacuously true.
        b2bFieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, fixture.invoiceId, mapOf("BT-12" to "VERTRAG-EP3V-001"))
        val findingsAfterFillingTrigger = QuarkusTransaction.requiringNew().call {
            b2bProfileRuleSet.evaluate(em.find(Invoice::class.java, fixture.invoiceId)!!)
        }
        assertTrue(findingsAfterFillingTrigger.isEmpty(), "filling BT-12 must clear both findings: $findingsAfterFillingTrigger")

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(fixture.invoiceId, InvoiceStatus.ISSUED) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.ISSUED, em.find(Invoice::class.java, fixture.invoiceId)!!.status)
        }
    }

    // ─── Steps 4/5 — MAPPED (both engines) + negative-mapping proof ──────────────────────────────

    private fun finalizeAndMapToDto(invoiceId: Long): InvoiceDto {
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return QuarkusTransaction.requiringNew().call<InvoiceDto> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.ISSUED, invoice.status, "the fixture must actually finalize for this suite to mean anything")
            // The B2B FREE header values (BT-12/BT-18) this suite maps only reach the DTO through
            // InvoiceDtoMapper's real b2bFieldValueService collaborator (its plain no-arg convenience
            // constructor — the one EInvoiceGenerationE2EIT's own scenarios use — leaves it null
            // and always yields an empty b2bValues list, since none of those scenarios needed
            // a catalog FREE value on the wire).
            InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)
        }
    }

    private fun assertMappedAndNegativeProof(xml: String, label: String, bt12Value: String, bt18Value: String) {
        // BT-12 — //ram:ApplicableHeaderTradeAgreement/ram:ContractReferencedDocument/ram:IssuerAssignedID
        // (B2bTerm.BT_12.xpath).
        EInvoiceXml.assertText(
            xml, "//ram:ApplicableHeaderTradeAgreement/ram:ContractReferencedDocument/ram:IssuerAssignedID", bt12Value, "$label/BT-12",
        )
        // BT-18 — //ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='130']/ram:IssuerAssignedID
        // (B2bTerm.BT_18.xpath).
        EInvoiceXml.assertText(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:AdditionalReferencedDocument[ram:TypeCode='130']/ram:IssuerAssignedID",
            bt18Value,
            "$label/BT-18",
        )
        // Negative-mapping proof (spec step 5) — BT-58 was never added to this profile and never
        // captured a value; its own EN-16931 position must be entirely absent from the XML, not
        // merely empty.
        EInvoiceXml.assertAbsent(
            xml,
            "//ram:ApplicableHeaderTradeAgreement/ram:BuyerTradeParty/ram:DefinedTradeContact/ram:EmailURIUniversalCommunication/ram:URIID",
            "$label/BT-58 negative proof",
        )
    }

    @Test
    fun `EP-3 mapped - Mustang engine writes BT-12 and BT-18 at their EN-16931 positions, KoSIT stays green, BT-58 stays absent`() {
        val fixture = buildFixture("EP3MM")
        b2bFieldValueService.saveFieldValues(
            B2bFieldOwnerType.INVOICE, fixture.invoiceId,
            mapOf("BT-12" to "VERTRAG-EP3MM-1", "BT-18" to "OBJ-EP3MM-1"),
        )

        val dto = finalizeAndMapToDto(fixture.invoiceId)
        val xml = mustangXml(dto)

        assertMappedAndNegativeProof(xml, "EP-3/Mustang", "VERTRAG-EP3MM-1", "OBJ-EP3MM-1")
        assertKositValid(xml, "EP-3/Mustang")
    }
}
