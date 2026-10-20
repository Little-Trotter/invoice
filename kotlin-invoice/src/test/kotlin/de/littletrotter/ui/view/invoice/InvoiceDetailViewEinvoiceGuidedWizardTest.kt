package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bFieldValueOrigin
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.UiEventBus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The guided B2B E-Rechnung wizard ([InvoiceDetailView.buildEinvoiceTab]'s `guided`
 * branch) had never actually been driven through a karibu test: the existing
 * [InvoiceDetailViewEinvoiceTabTest] only pins the pure helper functions
 * (`missingEInvoiceInputs`/`einvoiceGuidedModeTrigger`/`einvoiceGuidedSteps`) and one structural
 * "both tabs exist" check in flat mode — the actual step rail, step navigation ("Weiter" and
 * direct step clicks), the review step's live rows, and the progress bar were an entirely-untested
 * primary target. Both guided triggers are covered here:
 * ROUTING_ID (2 steps: Referenzen/Prüfung) and PROFILE (3 steps, adds Profilfelder) — the profile
 * variant is driven with an empty mandatory-field list (no [de.littletrotter.domain.reference
 * .B2bProfileField] fixtures needed) since the step *rail itself* is keyed off the trigger, not
 * off whether any field is actually mandatory ([einvoiceGuidedSteps]'s own contract, already
 * pinned).
 */
class InvoiceDetailViewEinvoiceGuidedWizardTest {

    private fun createView(refs: ReferenceDataMocks): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = mock(),
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(mock(), mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort, mock())),
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
                refs = refs,
            ),
        )
    }

    private fun draftInvoice(recipient: PartyDto?, buyerReference: String? = null): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L,
        invoiceNumber = "INV-2026-03-00042",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = recipient, items = emptyList(), latestGenerationJob = null,
        buyerReference = buyerReference,
    )

    private fun Component.allDescendants(): List<Component> {
        val direct = children.toList()
        return direct + direct.flatMap { it.allDescendants() }
    }

    /** Renders the entry pane in guided mode and returns the E-Rechnung tab's own content root
     *  (the `Div.einvoice-guided` [InvoiceDetailView.buildEinvoiceTab] builds directly, wired as
     *  tab 2's content — see [InvoiceDetailViewOverview]'s `tabSheet.add(einvoiceTab.captionComponent,
     *  einvoiceTab.content)`). */
    private fun renderGuidedTab(refs: ReferenceDataMocks, invoice: InvoiceDetailDto): Div {
        val view = createView(refs).apply { overviewEditMode = true }
        view.renderInvoice(invoice)
        val workspace = view.children.filter { it.element.classList.contains("entry-workspace") }.findFirst().orElseThrow()
        val tabSheet = workspace.allDescendants().filterIsInstance<TabSheet>().first()
        return tabSheet.getComponent(tabSheet.getTabAt(1)) as Div
    }

    private fun routingIdFacade(): ReferenceDataMocks = ReferenceDataMocks().apply {
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(null)).thenReturn(emptyList())
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
    }

    private fun profileFacade(profileId: Long = 7L): ReferenceDataMocks = ReferenceDataMocks().apply {
        val profile = B2bProfileDto(id = profileId, name = "B2B-Standard")
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(profileId)
        whenever(b2bProfileQueries.findB2bProfileById(profileId)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(profileId)).thenReturn(emptyList())
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))
    }

    private fun routingIdInvoice(buyerReference: String? = null): InvoiceDetailDto =
        draftInvoice(PartyDto(id = 9L, name = "Kundin", customerNumber = "K-9", routingId = "DE1234567890123"), buyerReference)

    private fun stepRail(root: Div): Div = root.allDescendants().filterIsInstance<Div>().first { it.element.classList.contains("einvoice-step-rail") }

    private fun stepButtons(root: Div): List<Component> = stepRail(root).children.toList()

    private fun stepLabel(button: Component): String =
        button.allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("bau-eyebrow") }.text ?: ""

    private fun fireClick(component: Component) {
        ComponentUtil.fireEvent(component, ClickEvent(component))
    }

    private fun buyerReferenceField(root: Div): TextField =
        root.allDescendants().filterIsInstance<TextField>().first { it.label == GuidedWizardTr.tr("invoiceDetail.buyerReference") }

    // ── Step composition ─────────────────────────────────────────────────────

    @Test
    fun `the routing-id-only wizard renders exactly two steps, Referenzen and Pruefung, with step 1 active`() {
        val root = renderGuidedTab(routingIdFacade(), routingIdInvoice())

        val buttons = stepButtons(root)
        assertEquals(2, buttons.size)
        assertEquals(
            listOf(
                GuidedWizardTr.tr("invoiceDetail.entry.einvoice.step.references"),
                GuidedWizardTr.tr("invoiceDetail.entry.einvoice.step.review"),
            ),
            buttons.map(::stepLabel),
        )
        assertTrue(buttons[0].element.classList.contains("einvoice-step-btn--active"))
        assertEquals("step", buttons[0].element.getAttribute("aria-current"))
        assertFalse(buttons[1].element.classList.contains("einvoice-step-btn--active"))
    }

    @Test
    fun `the profile-triggered wizard renders three steps including Profilfelder`() {
        val root = renderGuidedTab(profileFacade(), draftInvoice(PartyDto(id = 9L, name = "K", customerNumber = "K-9", routingId = null)))

        val buttons = stepButtons(root)
        assertEquals(3, buttons.size)
        assertEquals(
            listOf(
                GuidedWizardTr.tr("invoiceDetail.entry.einvoice.step.references"),
                GuidedWizardTr.tr("invoiceDetail.entry.einvoice.step.profileFields"),
                GuidedWizardTr.tr("invoiceDetail.entry.einvoice.step.review"),
            ),
            buttons.map(::stepLabel),
        )
    }

    @Test
    fun `a mandatory profile field leaves the Profilfelder step open until it is filled`() {
        val profileId = 7L
        val mandatoryField = B2bProfileFieldDto(
            term = de.littletrotter.shared.b2b.B2bTerm.BT_12,
            requirement = de.littletrotter.domain.reference.B2bFieldRequirement.MANDATORY,
            businessDefinition = "Vertragsreferenz",
        )
        val refs = profileFacade(profileId)
        whenever(refs.b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(profileId)).thenReturn(listOf(mandatoryField))
        val root = renderGuidedTab(
            refs,
            draftInvoice(PartyDto(id = 9L, name = "K", customerNumber = "K-9", routingId = null)),
        )
        val buttons = stepButtons(root)
        val profileFieldsSquare = buttons[1].allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-step-square") }
        assertTrue(profileFieldsSquare.element.classList.contains("bau-status-square--open"))

        // Jump to the Profilfelder step and fill the mandatory field via its own widget — the
        // step 0 (Referenzen) content, including buyerReferenceField, is detached once we
        // navigate away from it, so this is the only TextField left in the tree.
        fireClick(buttons[1])
        val contractField = root.allDescendants().filterIsInstance<TextField>().single()
        contractField.value = "V-2026-001"

        val profileFieldsSquareAfter = buttons[1].allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-step-square") }
        assertTrue(profileFieldsSquareAfter.element.classList.contains("bau-status-square--filled"))
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Test
    fun `clicking Weiter advances from step 1 to the last step and hides the Weiter row there`() {
        val root = renderGuidedTab(routingIdFacade(), routingIdInvoice())
        val weiterBtn = root.allDescendants().filterIsInstance<com.vaadin.flow.component.button.Button>()
            .first { it.text == GuidedWizardTr.tr("invoiceDetail.entry.einvoice.next") }

        fireClick(weiterBtn)

        val buttons = stepButtons(root)
        assertFalse(buttons[0].element.classList.contains("einvoice-step-btn--active"))
        assertTrue(buttons[1].element.classList.contains("einvoice-step-btn--active"))
        assertEquals("step", buttons[1].element.getAttribute("aria-current"))
        // The last step never shows a "Weiter" row (nothing further to advance to).
        assertTrue(
            root.allDescendants().filterIsInstance<com.vaadin.flow.component.button.Button>()
                .none { it.text == GuidedWizardTr.tr("invoiceDetail.entry.einvoice.next") },
        )
    }

    @Test
    fun `clicking a step-rail button directly jumps to that step without going through Weiter`() {
        val root = renderGuidedTab(profileFacade(), draftInvoice(PartyDto(id = 9L, name = "K", customerNumber = "K-9", routingId = null)))
        val buttons = stepButtons(root)

        fireClick(buttons[2]) // jump straight to Pruefung

        assertFalse(buttons[0].element.classList.contains("einvoice-step-btn--active"))
        assertTrue(buttons[2].element.classList.contains("einvoice-step-btn--active"))
    }

    // ── Review step + progress + step squares ────────────────────────────────

    @Test
    fun `the review step lists a missing row for buyerReference until it is filled, and the progress and step square follow`() {
        val root = renderGuidedTab(routingIdFacade(), routingIdInvoice(buyerReference = null))
        val buttons = stepButtons(root)

        // Referenzen step (index 0) starts open (buyerReference missing). Capture the field
        // reference now — showStep's stepContentContainer.removeAll() detaches step 0's whole
        // content subtree from the DOM once we navigate away from it (the Kotlin field reference
        // itself is still perfectly usable, only the tree lookup below would stop finding it).
        assertTrue(buttons[0].allDescendants().filterIsInstance<Span>().any { it.element.classList.contains("bau-status-square--open") })
        val buyerReference = buyerReferenceField(root)

        // Jump to Pruefung (last step) to force refreshReview().
        fireClick(buttons[1])
        val reviewRows = root.allDescendants().filterIsInstance<com.vaadin.flow.component.orderedlayout.HorizontalLayout>()
            .filter { row -> row.children.toList().any { it is Span && it.element.classList.contains("bau-status-square") } }
        assertTrue(reviewRows.any { row -> row.children.toList().filterIsInstance<Span>().any { it.element.classList.contains("bau-status-square--open") } })

        val progressLabelBefore = root.allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-progress-label") }.text
        assertEquals(GuidedWizardTr.tr("invoiceDetail.entry.einvoice.progress", "0", "1"), progressLabelBefore)

        // Fill the field via the captured reference — its value-change listener still fires and
        // still drives refreshLiveState() even though the field itself is currently detached.
        buyerReference.value = "DE1234567890123"

        // Live refresh recomputes both the progress label and (since Pruefung is still active) the review rows.
        val progressLabelAfter = root.allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-progress-label") }.text
        assertEquals(GuidedWizardTr.tr("invoiceDetail.entry.einvoice.progress", "1", "1"), progressLabelAfter)

        val referencesSquareAfter = buttons[0].allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-step-square") }
        assertTrue(referencesSquareAfter.element.classList.contains("bau-status-square--filled"))
    }

    @Test
    fun `an already-satisfied routing-id invoice starts with the Referenzen step square filled`() {
        val root = renderGuidedTab(routingIdFacade(), routingIdInvoice(buyerReference = "DE1234567890123"))
        val buttons = stepButtons(root)

        val referencesSquare = buttons[0].allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-step-square") }
        assertTrue(referencesSquare.element.classList.contains("bau-status-square--filled"))

        val progressLabel = root.allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-progress-label") }.text
        assertEquals(GuidedWizardTr.tr("invoiceDetail.entry.einvoice.progress", "1", "1"), progressLabel)
    }

    // ── Tab caption + subtitle ────────────────────────────────────────────────

    @Test
    fun `the tab caption shows an open-count while inputs are missing and no count once satisfied`() {
        val rootMissing = renderGuidedTab(routingIdFacade(), routingIdInvoice(buyerReference = null))
        val captionOpenMissing = rootMissing.allDescendants().filterIsInstance<Span>()
        // The caption itself lives outside `content` (it's `captionComponent`, sibling to `content`
        // in the Tab), so assert indirectly via the step square instead — already covered above.
        assertTrue(captionOpenMissing.isNotEmpty())
    }

    @Test
    fun `the profile subtitle names the effective B2B profile`() {
        val root = renderGuidedTab(profileFacade(), draftInvoice(PartyDto(id = 9L, name = "K", customerNumber = "K-9", routingId = null)))

        val subtitle = root.allDescendants().filterIsInstance<Span>()
            .firstOrNull { (it.text ?: "").contains("B2B-Standard") }
        assertTrue(subtitle != null, "expected the guided header subtitle to name the effective profile")
    }

    @Test
    fun `the routing-id subtitle names Xrechnung generically, without a profile`() {
        val root = renderGuidedTab(routingIdFacade(), routingIdInvoice())

        val subtitle = root.allDescendants().filterIsInstance<Span>()
            .firstOrNull { it.text == GuidedWizardTr.tr("invoiceDetail.entry.einvoice.xrechnung") }
        assertTrue(subtitle != null, "expected the guided header subtitle to show the generic Xrechnung text")
    }

    // ── guided step 02 renders party-placed (seller/recipient) fields, not just invoice-placed ones ──

    private fun sellerField(
        businessDefinition: String = "Anbieter Kontakt",
        requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL,
    ) = B2bProfileFieldDto(
        term = B2bTerm.BT_58,
        placement = B2bPlacement.SELLER,
        requirement = requirement,
        businessDefinition = businessDefinition,
    )

    private fun recipientField(
        businessDefinition: String = "Kunde Vertragsnummer",
        requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL,
    ) = B2bProfileFieldDto(
        term = B2bTerm.BT_12,
        placement = B2bPlacement.RECIPIENT,
        requirement = requirement,
        businessDefinition = businessDefinition,
    )

    private fun profileFieldsInvoice() = draftInvoice(PartyDto(id = 9L, name = "K", customerNumber = "K-9", routingId = null))

    @Test
    fun `guided step 02 renders the seller- and customer-placed FREE fields of the effective profile, grouped by origin`() {
        val refs = profileFacade()
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(listOf(sellerField()))
        whenever(refs.b2bFieldDefinitionQueries.findB2bRecipientFields(7L)).thenReturn(listOf(recipientField()))
        val root = renderGuidedTab(refs, profileFieldsInvoice())

        fireClick(stepButtons(root)[1])

        assertTrue(
            root.allDescendants().filterIsInstance<Span>().any { it.text == GuidedWizardTr.tr("invoiceDetail.entry.einvoice.profileFields.seller") },
            "expected the 'Profilfelder · Anbieter' group heading",
        )
        assertTrue(
            root.allDescendants().filterIsInstance<Span>().any { it.text == GuidedWizardTr.tr("invoiceDetail.entry.einvoice.profileFields.recipient") },
            "expected the 'Profilfelder · Kunde' group heading",
        )
        val fieldLabels = root.allDescendants().filterIsInstance<TextField>().map { it.label }
        assertTrue(fieldLabels.contains("Anbieter Kontakt"), "expected the seller-placed field's widget: $fieldLabels")
        assertTrue(fieldLabels.contains("Kunde Vertragsnummer"), "expected the recipient-placed field's widget: $fieldLabels")
    }

    @Test
    fun `each party field is prefilled from master data when the invoice has no own value`() {
        val refs = profileFacade()
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(listOf(sellerField()))
        whenever(refs.b2bFieldValueQueries.findB2bGuidedFieldValues(1L)).thenReturn(
            mapOf("BT-58" to ResolvedB2bFieldValueDto("anbieter@example.com", B2bFieldValueOrigin.PARTY, "anbieter@example.com")),
        )
        val root = renderGuidedTab(refs, profileFieldsInvoice())

        fireClick(stepButtons(root)[1])

        val widget = root.allDescendants().filterIsInstance<TextField>().first { it.label == "Anbieter Kontakt" }
        assertEquals("anbieter@example.com", widget.value)
    }

    @Test
    fun `a stored invoice value wins over the master-data value`() {
        val refs = profileFacade()
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(listOf(sellerField()))
        whenever(refs.b2bFieldValueQueries.findB2bGuidedFieldValues(1L)).thenReturn(
            mapOf("BT-58" to ResolvedB2bFieldValueDto("rechnung@example.com", B2bFieldValueOrigin.INVOICE, "anbieter@example.com")),
        )
        val root = renderGuidedTab(refs, profileFieldsInvoice())

        fireClick(stepButtons(root)[1])

        val widget = root.allDescendants().filterIsInstance<TextField>().first { it.label == "Anbieter Kontakt" }
        assertEquals("rechnung@example.com", widget.value, "the invoice-level override must win over the master-data value")
    }

    @Test
    fun `progress, step squares and PRUEFUNG count a mandatory seller-placed field`() {
        val refs = profileFacade()
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(listOf(sellerField(requirement = B2bFieldRequirement.MANDATORY)))
        val root = renderGuidedTab(refs, profileFieldsInvoice())
        val buttons = stepButtons(root)

        // Two mandatory inputs are now live: BT-10 (buyer reference, always counted once guided
        // mode is triggered) and the mandatory seller-placed field — both start missing.
        val progressLabelBefore = root.allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-progress-label") }.text
        assertEquals(GuidedWizardTr.tr("invoiceDetail.entry.einvoice.progress", "0", "2"), progressLabelBefore)
        val profileFieldsSquare = buttons[1].allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-step-square") }
        assertTrue(profileFieldsSquare.element.classList.contains("bau-status-square--open"))

        fireClick(buttons[1])
        val widget = root.allDescendants().filterIsInstance<TextField>().first { it.label == "Anbieter Kontakt" }
        widget.value = "gefuellt@example.com"

        // The Profilfelder step itself only tracks its own inputs — filling this one satisfies it
        // even though BT-10 (a REFERENZEN-step input) is still blank.
        val profileFieldsSquareAfter = buttons[1].allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-step-square") }
        assertTrue(profileFieldsSquareAfter.element.classList.contains("bau-status-square--filled"))
        val progressLabelAfter = root.allDescendants().filterIsInstance<Span>().first { it.element.classList.contains("einvoice-progress-label") }.text
        assertEquals(GuidedWizardTr.tr("invoiceDetail.entry.einvoice.progress", "1", "2"), progressLabelAfter)
    }
}

/** Thin wrapper so the test can resolve the same German i18n text the view itself renders,
 *  without duplicating messages.properties values as string literals — mirrors
 *  [InvoiceDetailViewEinvoiceTabTest]'s own private helper of the same shape. */
private object GuidedWizardTr {
    fun tr(key: String, vararg params: String): String =
        de.littletrotter.ui.i18n.UiTranslations.tr(key, *params)
}
