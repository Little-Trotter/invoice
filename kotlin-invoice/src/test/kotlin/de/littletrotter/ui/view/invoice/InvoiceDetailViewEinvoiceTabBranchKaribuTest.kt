package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.view
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * The e-invoice entry tab's B2B visibility arms beyond the existing suites: only
 * header- and seller-party-level seller fields join the guided union, mandatory fields without a
 * term or outside the rendered set never count, stored compliance findings (header and line) are
 * listed in the review step, and Weiter on the last step stays there.
 */
class InvoiceDetailViewEinvoiceTabBranchKaribuTest {

    private val refs = ReferenceDataMocks()
    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun field(term: B2bTerm?, requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL, definition: String = "Feld") =
        B2bProfileFieldDto(term = term, requirement = requirement, businessDefinition = definition)

    private fun all(c: Component): List<Component> = listOf(c) + c.children.toList().flatMap { all(it) }

    private fun guidedTab(): Div {
        val invoice = InvoiceDetailDto(
            id = 1L, invoiceNumber = "INV-2026-03-00042", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = LocalDate.now(),
            netAmount = null, vatAmount = null, grossAmount = null, sender = null,
            recipient = PartyDto(id = 9L, name = "Kundin", routingId = "DE1234567890123"),
            items = emptyList(), latestGenerationJob = null,
        )
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(null)
        whenever(refs.b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(null)).thenReturn(emptyList())
        whenever(refs.b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(emptyList())
        val v = view(refs, detailQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort).apply { overviewEditMode = true }
        v.renderInvoice(invoice)
        val workspace = v.children.filter { it.element.classList.contains("entry-workspace") }.findFirst().orElseThrow()
        val tabSheet = all(workspace).filterIsInstance<TabSheet>().first()
        return tabSheet.getComponent(tabSheet.getTabAt(1)) as Div
    }

    private fun showReview(tab: Div) {
        val rail = all(tab).filterIsInstance<Div>().first { it.element.classList.contains("einvoice-step-rail") }
        val reviewButton = rail.children.toList()[1]
        ComponentUtil.fireEvent(reviewButton, ClickEvent(reviewButton))
    }

    @Test
    fun `happy path - the guided union keeps header and seller-party seller fields only`() {
        val header = field(B2bTerm.BT_58)
        val sellerParty = field(B2bTerm.BT_84)
        whenever(refs.b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(7L)).thenReturn(emptyList())
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(listOf(header, sellerParty, field(B2bTerm.BT_155), field(null)))
        whenever(refs.b2bFieldDefinitionQueries.findB2bRecipientFields(7L)).thenReturn(emptyList())

        assertEquals(listOf(header, sellerParty), b2bGuidedProfileFields(refs.b2bFieldDefinitionQueries, 7L))
        assertEquals(emptyList<B2bProfileFieldDto>(), b2bGuidedProfileFields(refs.b2bFieldDefinitionQueries, null))
    }

    @Test
    fun `boundary - mandatory fields without term or outside the rendered set never count`() {
        whenever(refs.b2bFieldDefinitionQueries.findB2bInvoiceHeaderFields(7L)).thenReturn(
            listOf(
                field(B2bTerm.BT_12, B2bFieldRequirement.MANDATORY, "Vertrag"),
                field(null, B2bFieldRequirement.MANDATORY),
                field(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, "nicht gerendert"),
            ),
        )
        whenever(refs.b2bFieldDefinitionQueries.findB2bSellerFields(7L)).thenReturn(emptyList())
        whenever(refs.b2bFieldDefinitionQueries.findB2bRecipientFields(7L)).thenReturn(emptyList())

        val mandatory = mandatoryB2bHeaderFields(refs.b2bFieldDefinitionQueries, 7L, setOf(B2bTerm.BT_12.officialCode))

        assertEquals(listOf(B2bTerm.BT_12.officialCode), mandatory.map { it.btCode })
    }

    @Test
    fun `boundary - stored header and line compliance findings are listed in the review step`() {
        whenever(invoiceDetailQueryPort.findB2bComplianceFindings(1L)).thenReturn(
            listOf(
                B2bFindingDto("B2B-1", listOf(B2bTerm.BT_12.officialCode), definition = null, positionId = null),
                B2bFindingDto("B2B-2", listOf(B2bTerm.BT_155.officialCode), definition = null, positionId = "3"),
            ),
        )
        val tab = guidedTab()
        showReview(tab)

        val texts = all(tab).filterIsInstance<HasText>().map { it.text }
        assertTrue(texts.any { it.contains(B2bTerm.BT_12.officialCode) }, "$texts")
        assertTrue(texts.any { it.contains("3") && it.contains(B2bTerm.BT_155.officialCode) }, "$texts")
    }

    @Test
    fun `boundary - Weiter on the last step keeps the review step`() {
        whenever(invoiceDetailQueryPort.findB2bComplianceFindings(1L)).thenReturn(emptyList())
        val tab = guidedTab()
        val weiter = all(tab).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.entry.einvoice.next") }
        showReview(tab)
        val atLast = all(tab).filterIsInstance<HasText>().map { it.text }

        weiter.click()

        assertEquals(atLast, all(tab).filterIsInstance<HasText>().map { it.text })
    }
}
