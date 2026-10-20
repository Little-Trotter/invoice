package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10._findAll
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.ui.UiEventBus
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The entry pane's B2B profile override control ([de.littletrotter.ui.view.invoice
 * .buildEntryPane]'s `b2bProfileBox`). Only the wiring is verified here (population from the
 * facade, resolution of the current server-side value): the derivation/override/finalization
 * *rules* themselves are proven at the service layer
 * ([de.littletrotter.application.InvoiceLifecycleServiceTest]) — this test only guards against the
 * UI silently losing that wiring, e.g. on a refactor of `buildEntryPane`.
 */
class InvoiceDetailViewB2bProfileTest {

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

    private fun draftInvoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L,
        invoiceNumber = "INV-2026-03-00042",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    @Test
    fun `entering entry mode on a draft invoice resolves the current B2B profile and populates the override options server-side`() {
        val refs = ReferenceDataMocks()
        val profile = B2bProfileDto(id = 5L, name = "B2B-Standard")
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile))
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(5L)
        whenever(refs.b2bProfileQueries.findB2bProfileById(5L)).thenReturn(profile)
        val view = createView(refs).apply { overviewEditMode = true }

        view.renderInvoice(draftInvoice())

        // The calls buildEntryPane's b2bProfileBox needs to show "which profile is this invoice's
        // own right now" and "what could it be overridden to" — a refactor that drops the box (or
        // stops resolving it server-side) would leave one of these uncalled.
        // findB2bProfileIdForInvoice is also called once by the pre-existing b2bHeaderSection,
        // so at least once — not exactly once — is the real contract here.
        verify(refs.b2bProfileQueries).findAllActiveB2bProfiles()
        verify(refs.b2bFieldValueQueries, org.mockito.kotlin.atLeastOnce()).findB2bProfileIdForInvoice(1L)
        verify(refs.b2bProfileQueries).findB2bProfileById(5L)
    }

    @Test
    fun `an invoice without a B2B profile resolves to no override id, never calling findB2bProfileById`() {
        val refs = ReferenceDataMocks()
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        val view = createView(refs).apply { overviewEditMode = true }

        view.renderInvoice(draftInvoice())

        verify(refs.b2bFieldValueQueries, org.mockito.kotlin.atLeastOnce()).findB2bProfileIdForInvoice(1L)
        org.mockito.kotlin.verify(refs.b2bProfileQueries, org.mockito.kotlin.never()).findB2bProfileById(org.mockito.kotlin.any())
    }

    // ── b2bProfileBox preselection ────────────────────────────────────────────

    private fun Component.allDescendants(): List<Component> {
        val direct = children.toList()
        return direct + direct.flatMap { it.allDescendants() }
    }

    // TabSheet slots each tab's content component via low-level element slotting, not as a
    // Component-tree child of the TabSheet itself (see InvoiceDetailViewEinvoiceTabTest's own
    // comment) — getComponent(Tab) is the supported way to reach a tab's content, then its own
    // descendants traverse normally from there. b2bProfileBox lives on tab 2 (E-Rechnung); in
    // guided mode (effectiveB2bProfileId set, as in every test here) it sits on step 02
    // PROFILFELDER, whose content is only attached to the tree once that step becomes active (see
    // InvoiceDetailViewEinvoiceGuidedWizardTest's own showStep note) — so this navigates the step
    // rail to PROFILFELDER (index 1) first, exactly like that test's own `fireClick` helper.
    private fun b2bProfileBox(view: InvoiceDetailView): ComboBox<B2bProfileDto> {
        val workspace = view.children.filter { it.element.classList.contains("entry-workspace") }.findFirst().orElseThrow()
        val tabSheet = workspace.allDescendants().filterIsInstance<com.vaadin.flow.component.tabs.TabSheet>().first()
        val tab2Content = tabSheet.getComponent(tabSheet.getTabAt(1))
        val stepRail = tab2Content.allDescendants().filterIsInstance<com.vaadin.flow.component.html.Div>()
            .firstOrNull { it.element.classList.contains("einvoice-step-rail") }
        stepRail?.children?.toList()?.getOrNull(1)?.let { profileFieldsStepButton ->
            com.vaadin.flow.component.ComponentUtil.fireEvent(profileFieldsStepButton, com.vaadin.flow.component.ClickEvent(profileFieldsStepButton))
        }
        return (listOf(tab2Content) + tab2Content.allDescendants()).filterIsInstance<ComboBox<*>>()
            .single { it.label == de.littletrotter.ui.i18n.UiTranslations.tr(view, "invoiceDetail.b2bProfile") }.requireAs()
    }

    @Test
    fun `b2bProfileBox preselects the effective profile even as a different object instance from the one in the item list — the regression`() {
        val refs = ReferenceDataMocks()
        // Two distinct B2bProfileDto instances, same id — exactly the "different persistence-context
        // load" shape this test guards against: the item list comes from findAllActiveB2bProfiles, the
        // preselected value from a separate findB2bProfileById call.
        val itemListInstance = B2bProfileDto(id = 5L, name = "Neckar")
        val effectiveInstance = B2bProfileDto(id = 5L, name = "Neckar")
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(itemListInstance))
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(5L)
        whenever(refs.b2bProfileQueries.findB2bProfileById(5L)).thenReturn(effectiveInstance)
        val view = createView(refs).apply { overviewEditMode = true }

        view.renderInvoice(draftInvoice())

        assertEquals(effectiveInstance, b2bProfileBox(view).value, "the box must preselect the effective profile by id, not by object identity")
    }

    @Test
    fun `b2bProfileBox still shows the effective profile when it is deactivated - EP4-F2`() {
        val refs = ReferenceDataMocks()
        val inactiveProfile = B2bProfileDto(id = 9L, name = "Ausgemustert", active = false)
        // Deactivated -> not among findAllActiveB2bProfiles's own result, but still
        // referenced from the invoice.
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(9L)
        whenever(refs.b2bProfileQueries.findB2bProfileById(9L)).thenReturn(inactiveProfile)
        val view = createView(refs).apply { overviewEditMode = true }

        view.renderInvoice(draftInvoice())

        val box = b2bProfileBox(view)
        assertEquals(inactiveProfile, box.value, "the inactive profile must still be preselected")
        assertTrue(box.dataProvider._findAll().contains(inactiveProfile), "and must be among the box's own items, or it would render empty")
    }
}
