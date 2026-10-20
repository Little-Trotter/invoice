package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The guided E-Rechnung wizard's review step ("Prüfung") lists the saved B2B compliance
 * findings of the invoice below the live rows: a header finding by its business term, a line
 * finding with its position prefix, a conditional finding with its "if empty" suffix, and nothing
 * but the caption when there are no findings.
 */
class InvoiceDetailViewEinvoiceReviewFindingsTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()

    private fun createView(): InvoiceDetailView {
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort, mock())),
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
                refs = ReferenceDataMocks().apply {
                    whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
                    whenever(b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(null)).thenReturn(emptyList())
                    whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
                },
            ),
        )
    }

    private fun routingIdInvoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-2026-03-00042", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = LocalDate.now(),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = PartyDto(id = 9L, name = "Kundin", customerNumber = "K-9", routingId = "DE1234567890123"),
        items = emptyList(), latestGenerationJob = null,
    )

    private fun Component.allDescendants(): List<Component> {
        val direct = children.toList()
        return direct + direct.flatMap { it.allDescendants() }
    }

    /** Renders the guided E-Rechnung tab and opens its last step (Prüfung). */
    private fun reviewText(findings: List<B2bFindingDto>): String {
        whenever(invoiceDetailQueryPort.findB2bComplianceFindings(1L)).thenReturn(findings)
        val view = createView().apply { overviewEditMode = true }
        view.renderInvoice(routingIdInvoice())
        val workspace = view.children.filter { it.element.classList.contains("entry-workspace") }.findFirst().orElseThrow()
        val tabSheet = workspace.allDescendants().filterIsInstance<TabSheet>().first()
        val root = tabSheet.getComponent(tabSheet.getTabAt(1)) as Div
        val rail = root.allDescendants().filterIsInstance<Div>().first { it.element.classList.contains("einvoice-step-rail") }
        val reviewButton = rail.children.toList().last()
        ComponentUtil.fireEvent(reviewButton, ClickEvent(reviewButton))
        return root.allDescendants().filterIsInstance<Div>()
            .filter { it.element.classList.contains("einvoice-review-block") }
            .joinToString("\n") { it.element.textRecursively }
    }

    private fun finding(vararg terms: B2bTerm, positionId: String? = null) =
        B2bFindingDto(ruleId = "B2B-RULE", businessTermCodes = terms.map { it.officialCode }, definition = null, positionId = positionId)

    @Test
    fun `happy path - a saved header finding is listed by its business term in the review step`() {
        val text = reviewText(listOf(finding(B2bTerm.BT_19)))

        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.entry.einvoice.findings.savedState")), text)
        assertTrue(text.contains(B2bTerm.BT_19.officialCode), text)
    }

    @Test
    fun `boundary - a line finding carries its position prefix`() {
        val text = reviewText(listOf(finding(B2bTerm.BT_19, positionId = "2")))

        assertTrue(text.contains(UiTranslations.tr("invoiceDetail.summary.checklist.b2bLinePrefix", "2", B2bTerm.BT_19.officialCode)), text)
    }

    @Test
    fun `boundary - a conditional finding names its condition term`() {
        val text = reviewText(listOf(finding(B2bTerm.BT_19, B2bTerm.BT_12)))

        assertTrue(text.contains(UiTranslations.tr("compliance.b2b.conditionalIfEmptySuffix", B2bTerm.BT_12.officialCode)), text)
    }

    @Test
    fun `boundary - without saved findings only the caption is shown below the live rows`() {
        val withNone = reviewText(emptyList())
        val withOne = reviewText(listOf(finding(B2bTerm.BT_19)))

        assertTrue(withNone.contains(UiTranslations.tr("invoiceDetail.entry.einvoice.findings.savedState")), withNone)
        assertEquals(
            withNone.split(B2bTerm.BT_19.officialCode).size + 1,
            withOne.split(B2bTerm.BT_19.officialCode).size,
            "exactly one additional row per saved finding",
        )
    }
}
