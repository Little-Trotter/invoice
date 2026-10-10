package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.HasLabel
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Pins the "missing mandatory input"/guided-mode-trigger/step composition pure functions, and a
 * structural check that the entry pane exposes both tabs with all fields still reachable through
 * the single Binder.
 */
class InvoiceDetailViewEinvoiceTabTest {

    // ── 1. missingEInvoiceInputs ─────────────────────────────────────────────

    @Test
    fun `missingEInvoiceInputs is empty when guided mode was not triggered and there are no mandatory profile fields`() {
        val missing = missingEInvoiceInputs(
            guidedModeTriggered = false,
            buyerReference = null,
            mandatoryB2bHeaderFields = emptyList(),
            b2bHeaderFieldValues = emptyMap(),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingEInvoiceInputs counts a blank BT-10 and blank mandatory profile fields, but not a filled one`() {
        val fields = listOf(
            MandatoryB2bHeaderField("BT-91", "Zahlungsart"),
            MandatoryB2bHeaderField("BT-92", "Referenztext"),
        )
        val missing = missingEInvoiceInputs(
            guidedModeTriggered = true,
            buyerReference = "",
            mandatoryB2bHeaderFields = fields,
            b2bHeaderFieldValues = mapOf("BT-91" to "SEPA", "BT-92" to "  "),
        )
        assertEquals(setOf(BUYER_REFERENCE_INPUT_ID, "BT-92"), missing.toSet())
        assertEquals(2, missing.size)
    }

    @Test
    fun `missingEInvoiceInputs treats BT-10 as mandatory for the routing-id-only trigger, with no profile fields at all`() {
        val missing = missingEInvoiceInputs(
            guidedModeTriggered = true,
            buyerReference = null,
            mandatoryB2bHeaderFields = emptyList(),
            b2bHeaderFieldValues = emptyMap(),
        )
        assertEquals(listOf(BUYER_REFERENCE_INPUT_ID), missing)
    }

    @Test
    fun `missingEInvoiceInputs never counts BT-10 when guided mode was not triggered, even if it is blank`() {
        val missing = missingEInvoiceInputs(
            guidedModeTriggered = false,
            buyerReference = "",
            mandatoryB2bHeaderFields = emptyList(),
            b2bHeaderFieldValues = emptyMap(),
        )
        assertTrue(missing.isEmpty())
    }

    // ── 2. Guided-mode trigger decision ──────────────────────────────────────

    @Test
    fun `einvoiceGuidedModeTrigger is PROFILE when an effective B2B profile is set, regardless of routing id`() {
        assertEquals(EInvoiceGuidedTrigger.PROFILE, einvoiceGuidedModeTrigger(5L, null))
        assertEquals(EInvoiceGuidedTrigger.PROFILE, einvoiceGuidedModeTrigger(5L, "DE1234567890123"))
    }

    @Test
    fun `einvoiceGuidedModeTrigger is ROUTING_ID when only the recipient's routing id is set`() {
        assertEquals(EInvoiceGuidedTrigger.ROUTING_ID, einvoiceGuidedModeTrigger(null, "DE1234567890123"))
    }

    @Test
    fun `einvoiceGuidedModeTrigger is NONE when neither a profile nor a routing id is present`() {
        assertEquals(EInvoiceGuidedTrigger.NONE, einvoiceGuidedModeTrigger(null, null))
        assertEquals(EInvoiceGuidedTrigger.NONE, einvoiceGuidedModeTrigger(null, "   "))
    }

    // ── 2b. Tab label base ──────────────────────────────────────────────────

    @Test
    fun `einvoiceTabLabelBase returns plain label when trigger is NONE`() {
        val label = einvoiceTabLabelBase(EInvoiceGuidedTrigger.NONE) { key ->
            when (key) {
                "invoiceDetail.entry.tab.einvoice" -> "E-Rechnung"
                "invoiceDetail.entry.tab.einvoice.guided.suffix" -> "(geführt)"
                else -> ""
            }
        }
        assertEquals("E-Rechnung", label)
    }

    @Test
    fun `einvoiceTabLabelBase appends guided suffix when trigger is PROFILE`() {
        val label = einvoiceTabLabelBase(EInvoiceGuidedTrigger.PROFILE) { key ->
            when (key) {
                "invoiceDetail.entry.tab.einvoice" -> "E-Rechnung"
                "invoiceDetail.entry.tab.einvoice.guided.suffix" -> "(geführt)"
                else -> ""
            }
        }
        assertEquals("E-Rechnung (geführt)", label)
    }

    @Test
    fun `einvoiceTabLabelBase appends guided suffix when trigger is ROUTING_ID`() {
        val label = einvoiceTabLabelBase(EInvoiceGuidedTrigger.ROUTING_ID) { key ->
            when (key) {
                "invoiceDetail.entry.tab.einvoice" -> "E-Rechnung"
                "invoiceDetail.entry.tab.einvoice.guided.suffix" -> "(geführt)"
                else -> ""
            }
        }
        assertEquals("E-Rechnung (geführt)", label)
    }

    // ── 3. Step composition ──────────────────────────────────────────────────

    @Test
    fun `einvoiceGuidedSteps has 3 steps including PROFILE_FIELDS when a B2B profile is effective`() {
        val steps = einvoiceGuidedSteps(EInvoiceGuidedTrigger.PROFILE)
        assertEquals(listOf(EInvoiceStep.REFERENCES, EInvoiceStep.PROFILE_FIELDS, EInvoiceStep.REVIEW), steps)
    }

    @Test
    fun `einvoiceGuidedSteps has 2 steps without PROFILE_FIELDS for the routing-id-only trigger`() {
        val steps = einvoiceGuidedSteps(EInvoiceGuidedTrigger.ROUTING_ID)
        assertEquals(listOf(EInvoiceStep.REFERENCES, EInvoiceStep.REVIEW), steps)
    }

    @Test
    fun `einvoiceGuidedSteps is empty for flat mode`() {
        assertTrue(einvoiceGuidedSteps(EInvoiceGuidedTrigger.NONE).isEmpty())
    }

    // ── 4. Tab 1 / tab 2 field allocation ────────────────────────────────────

    private fun createView(refs: ReferenceDataMocks = ReferenceDataMocks()): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryService = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = mock(),
            generationJobQueryService = generationJobQueryService,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(mock(), mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock<InvoiceReceiptQueryPort>().apply {
                    whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
                }, mock(), ReceiptDialogLauncher(mock<InvoiceReceiptQueryPort>().apply {
                    whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
                }, mock(), invoiceDetailQueryPort)),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort, refs = refs),
        )
    }

    private fun draftInvoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L,
        invoiceNumber = "INV-2026-03-00042",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun Component.allDescendants(): List<Component> {
        val direct = children.toList()
        return direct + direct.flatMap { it.allDescendants() }
    }

    // ── outer rail removed, inner wizard's own rail untouched ────────────────────────────────

    /** Mirrors [InvoiceDetailViewEinvoiceGuidedWizardTest]'s own `routingIdFacade` — an
     *  XRechnung-routing recipient with no B2B profile at all. */
    private fun routingIdFacade(): ReferenceDataMocks = ReferenceDataMocks().apply {
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(null)).thenReturn(emptyList())
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
    }

    /** Mirrors [InvoiceDetailViewEinvoiceGuidedWizardTest]'s own `profileFacade` — an effective
     *  B2B profile ("Neckar" in practice, standing in as "B2B-Standard" here). */
    private fun profileFacade(profileId: Long = 7L): ReferenceDataMocks = ReferenceDataMocks().apply {
        val profile = B2bProfileDto(id = profileId, name = "B2B-Standard")
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(profileId)
        whenever(b2bProfileQueries.findB2bProfileById(profileId)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(profileId)).thenReturn(emptyList())
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))
    }

    private fun routingIdInvoice(buyerReference: String? = null): InvoiceDetailDto = draftInvoice().copy(
        recipient = PartyDto(id = 9L, name = "Kundin", customerNumber = "K-9", routingId = "DE1234567890123"),
        buyerReference = buyerReference,
    )

    private fun profileInvoice(): InvoiceDetailDto = draftInvoice().copy(
        recipient = PartyDto(id = 9L, name = "Kundin", customerNumber = "K-9", routingId = null),
    )

    private fun workspaceOf(view: InvoiceDetailView): Component =
        view.children.filter { it.element.classList.contains("entry-workspace") }.findFirst().orElseThrow()

    /** All component-tree nodes reachable from the rendered entry pane, INCLUDING each tab's own
     *  content: [TabSheet] slots tab content via low-level element slotting, not as a normal
     *  Component-tree child of the TabSheet (see the pre-existing "one binder binds fields from
     *  all tabs" test below) — `workspace.allDescendants()` alone never finds anything inside a
     *  tab, so the outer rail (a real workspace child, gone now) and the inner wizard's rail (only
     *  reachable through tab 2's own content) both need this combined search space. */
    private fun entryPaneSearchSpace(view: InvoiceDetailView): Pair<TabSheet, List<Component>> {
        val workspace = workspaceOf(view)
        val tabSheet = workspace.allDescendants().filterIsInstance<TabSheet>().first()
        val tabContents = (0 until tabSheet.tabCount).map { tabSheet.getComponent(tabSheet.getTabAt(it)) }
        val nodes = workspace.allDescendants() + tabContents + tabContents.flatMap { it.allDescendants() }
        return tabSheet to nodes
    }

    /** Every element carrying the shared CSS class `einvoice-step-rail` — the now-removed outer
     *  rail used it, and the inner wizard (untouched) still does; at most one may remain. */
    private fun railElements(searchSpace: List<Component>): List<Component> =
        searchSpace.filter { it.element.classList.contains("einvoice-step-rail") }

    /** The outer rail's buttons were literally labelled "01  Rechnung" / "02  E-Rechnung" /
     *  "03  Freitext & Hinweise" (a Vaadin [Button], not the inner wizard's [NativeButton]) — none
     *  of those may remain anywhere in the entry pane. */
    private fun numberedRailButtonTexts(searchSpace: List<Component>): List<String> =
        searchSpace.filterIsInstance<Button>()
            .mapNotNull { it.text }
            .filter { text -> listOf("01", "02", "03").any { prefix -> text.startsWith(prefix) } }

    private fun fireClick(component: Component) {
        ComponentUtil.fireEvent(component, ClickEvent(component))
    }

    @Test
    fun `B1 entry pane has no step rail at all when guided mode is not triggered`() {
        val refs = ReferenceDataMocks()
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        val view = createView(refs).apply { overviewEditMode = true }
        val invoice = draftInvoice().copy(
            recipient = PartyDto(id = 9L, name = "Kundin", customerNumber = "K-9", routingId = null),
        )

        view.renderInvoice(invoice)

        val (tabSheet, nodes) = entryPaneSearchSpace(view)
        assertTrue(railElements(nodes).isEmpty(), "flat (unguided) mode must not render any einvoice-step-rail")
        assertTrue(numberedRailButtonTexts(nodes).isEmpty(), "flat mode must not render numbered step buttons")
        assertEquals(3, tabSheet.tabCount)
    }

    @Test
    fun `B2 entry pane exposes only the inner wizards rail and the native 3 tabs when triggered by a B2B profile`() {
        val view = createView(profileFacade()).apply { overviewEditMode = true }

        view.renderInvoice(profileInvoice())

        val (tabSheet, nodes) = entryPaneSearchSpace(view)
        val rails = railElements(nodes)
        assertEquals(1, rails.size, "exactly the inner wizard's step rail must remain")
        assertTrue(numberedRailButtonTexts(nodes).isEmpty(), "the outer numbered step buttons must be gone")

        assertEquals(3, tabSheet.tabCount)
        assertEquals(UiTranslationsHelper.tr(view, "invoiceDetail.entry.tab.invoice"), tabSheet.getTabAt(0).label)
        assertEquals(UiTranslationsHelper.tr(view, "invoiceDetail.entry.tab.notes"), tabSheet.getTabAt(2).label)

        val tab1Content = tabSheet.getComponent(tabSheet.getTabAt(0))
        val tab2Content = tabSheet.getComponent(tabSheet.getTabAt(1))
        val tab3Content = tabSheet.getComponent(tabSheet.getTabAt(2))
        val stepButtons = rails.single().children.toList()
        assertEquals(3, stepButtons.size, "PROFILE trigger must render all 3 inner steps including Profilfelder")

        val labelsBeforeStep2 = (listOf(tab1Content, tab2Content, tab3Content) +
            tab1Content.allDescendants() + tab2Content.allDescendants() + tab3Content.allDescendants())
            .fieldLabels()
        // The step content container detaches step 1 when navigating to step 2 (documented in
        // InvoiceDetailViewEinvoiceGuidedWizardTest) — visit step 2 to also collect its fields.
        fireClick(stepButtons[1])
        val labelsAfterStep2 = tab2Content.allDescendants().fieldLabels()
        val allLabels = labelsBeforeStep2 + labelsAfterStep2

        listOf(
            "invoiceDetail.summary.name", // tab 1
            "invoiceDetail.card.sender", // tab 1
            "invoiceDetail.invoiceDate", // tab 1
            "invoiceDetail.paymentTerm", // tab 1
            "invoiceDetail.currency", // tab 1
            "invoiceDetail.freeText", // tab 3
            "invoiceDetail.invoiceNote", // tab 3
            "invoiceDetail.buyerReference", // tab 2, step Referenzen
            "invoiceDetail.purchaseOrderReference", // tab 2, step Referenzen
            "invoiceDetail.orderReference", // tab 2, step Referenzen
            "invoiceDetail.b2bProfile", // tab 2, step Profilfelder
        ).forEach { key ->
            val expected = UiTranslationsHelper.tr(view, key)
            assertTrue(allLabels.contains(expected), "expected a field labelled '$expected' ($key) somewhere in the entry pane")
        }
    }

    @Test
    fun `B3 entry pane exposes only the inner wizards rail and the native 3 tabs when triggered by an XRechnung routing id`() {
        val view = createView(routingIdFacade()).apply { overviewEditMode = true }

        view.renderInvoice(routingIdInvoice())

        val (tabSheet, nodes) = entryPaneSearchSpace(view)
        val rails = railElements(nodes)
        assertEquals(1, rails.size, "exactly the inner wizard's step rail must remain")
        assertTrue(numberedRailButtonTexts(nodes).isEmpty(), "the outer numbered step buttons must be gone")

        assertEquals(3, tabSheet.tabCount)
        assertEquals(2, rails.single().children.toList().size, "ROUTING_ID trigger has no Profilfelder step")

        val tab1Content = tabSheet.getComponent(tabSheet.getTabAt(0))
        val tab2Content = tabSheet.getComponent(tabSheet.getTabAt(1))
        val tab3Content = tabSheet.getComponent(tabSheet.getTabAt(2))
        val labels = (listOf(tab1Content, tab2Content, tab3Content) +
            tab1Content.allDescendants() + tab2Content.allDescendants() + tab3Content.allDescendants())
            .fieldLabels()

        listOf(
            "invoiceDetail.summary.name",
            "invoiceDetail.card.sender",
            "invoiceDetail.invoiceDate",
            "invoiceDetail.paymentTerm",
            "invoiceDetail.currency",
            "invoiceDetail.freeText",
            "invoiceDetail.invoiceNote",
            "invoiceDetail.buyerReference",
            "invoiceDetail.purchaseOrderReference",
            "invoiceDetail.orderReference",
        ).forEach { key ->
            val expected = UiTranslationsHelper.tr(view, key)
            assertTrue(labels.contains(expected), "expected a field labelled '$expected' ($key) somewhere in the entry pane")
        }
    }

    @Test
    fun `B4 guided hint banner keeps its warning text and dismiss control but drops the zur Fuehrung button`() {
        val view = createView(routingIdFacade()).apply { overviewEditMode = true }

        view.renderInvoice(routingIdInvoice(buyerReference = null))

        val (_, nodes) = entryPaneSearchSpace(view)
        val hint = nodes.first { it.id.orElse(null) == "guided-hint" }

        assertTrue(hint.isVisible, "hint banner must be visible while the buyer reference is missing")
        val warningText = hint.allDescendants().filterIsInstance<Span>().joinToString(" ") { it.text ?: "" }
        assertTrue(
            warningText.contains(UiTranslationsHelper.tr(view, "invoiceDetail.entry.einvoice.guidedHint", "1")),
            "expected the missing-input warning text in: $warningText",
        )
        assertTrue(hint.allDescendants().filterIsInstance<Button>().isEmpty(), "the 'zur Fuehrung' action button must be gone")

        val dismiss = hint.allDescendants().first { it.element.classList.contains("line-advice-dismiss") }
        assertEquals(
            UiTranslationsHelper.tr(view, "invoiceDetail.entry.einvoice.guidedHint.dismiss"),
            dismiss.element.getAttribute("aria-label"),
        )
    }

    @Test
    fun `entry pane exposes the final notes tab and one binder binds fields from all tabs`() {
        val refs = ReferenceDataMocks()
        // Mockito's default answer returns 0L (not null) for an unstubbed method returning a
        // boxed Long — explicitly stubbing null here is what actually keeps this scenario in
        // flat (unguided) mode, same reasoning as InvoiceDetailViewB2bProfileTest.
        org.mockito.kotlin.whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        val view = createView(refs).apply { overviewEditMode = true }
        val invoice = draftInvoice().copy(
            // No effective B2B profile and no routing id — flat (unguided) tab 2, so every field
            // is directly reachable without stepping through the guided rail.
            recipient = PartyDto(id = 9L, name = "Kundin", customerNumber = "K-9", routingId = null),
        )

        view.renderInvoice(invoice)

        val workspace = view.children.filter { it.element.classList.contains("entry-workspace") }.findFirst().orElseThrow()
        val tabSheet = workspace.allDescendants().filterIsInstance<TabSheet>().first()
        assertEquals(3, tabSheet.tabCount)
        assertEquals(UiTranslationsHelper.tr(view, "invoiceDetail.entry.tab.invoice"), tabSheet.getTabAt(0).label)

        val tab2Caption = tabSheet.getTabAt(1)
        val captionText = (tab2Caption.allDescendants() + listOf(tab2Caption)).filterIsInstance<Span>().joinToString(" ") { it.text ?: "" }
        assertTrue(captionText.contains(UiTranslationsHelper.tr(view, "invoiceDetail.entry.tab.einvoice")))

        // TabSheet slots each tab's content component via low-level element slotting, not as a
        // Component-tree child of the TabSheet itself — getComponent(Tab) is the supported way
        // to reach it, then its own descendants traverse normally from there.
        val tab1Content = tabSheet.getComponent(tabSheet.getTabAt(0))
        val tab2Content = tabSheet.getComponent(tabSheet.getTabAt(1))
        val tab3Content = tabSheet.getComponent(tabSheet.getTabAt(2))
        assertEquals(UiTranslationsHelper.tr(view, "invoiceDetail.entry.tab.notes"), tabSheet.getTabAt(2).label)

        // Tab 1 fields (parties/dates), tab 2 fields (references/format/B2B), and the final
        // free-text/note fields must all
        // still be reachable — i.e. bound by the one shared Binder, not dropped in the split.
        val labels = (listOf(tab1Content, tab2Content, tab3Content) +
            tab1Content.allDescendants() + tab2Content.allDescendants() + tab3Content.allDescendants())
            .fieldLabels()
        listOf(
            "invoiceDetail.summary.name", // recipientBox — tab 1
            "invoiceDetail.card.sender", // senderBox — tab 1
            "invoiceDetail.invoiceDate", // tab 1
            "invoiceDetail.paymentTerm", // tab 1
            "invoiceDetail.currency", // tab 1
            "invoiceDetail.freeText", // tab 3
            "invoiceDetail.invoiceNote", // tab 3
            "invoiceDetail.buyerReference", // tab 2
            "invoiceDetail.purchaseOrderReference", // tab 2
            "invoiceDetail.orderReference", // tab 2
            "invoiceDetail.b2bProfile", // tab 2
        ).forEach { key ->
            val expected = UiTranslationsHelper.tr(view, key)
            assertTrue(labels.contains(expected), "expected a field labelled '$expected' ($key) somewhere in the entry pane")
        }
    }

    @Test
    fun `document mode still shows no entry tabs at all`() {
        val view = createView()
        view.renderInvoice(draftInvoice())

        assertFalse(view.children.anyMatch { it.element.classList.contains("entry-workspace") })
    }
}

/** Thin wrapper so the test can resolve the same German i18n text the view itself renders,
 *  without duplicating messages.properties values as string literals. */
private object UiTranslationsHelper {
    fun tr(view: InvoiceDetailView, key: String, vararg params: Any): String =
        de.littletrotter.ui.i18n.UiTranslations.tr(view, key, *params)
}

/** Labels of Vaadin fields plus the free-text editor, which renders its label as its own span
 *  instead of implementing [HasLabel]. */
private fun Iterable<com.vaadin.flow.component.Component>.fieldLabels(): Set<String> {
    fun com.vaadin.flow.component.Component.spans(): List<Span> =
        children.toList().flatMap { child ->
            (if (child is Span && child.hasClassName("lt-html-freetext-editor__label")) listOf(child) else emptyList()) +
                child.spans()
        }
    val editorLabels = filterIsInstance<de.littletrotter.ui.components.freetext.HtmlFreeTextEditor>().flatMap { it.spans() }.map { it.text }
    return (filterIsInstance<HasLabel>().mapNotNull { it.label } + editorLabels).toSet()
}
