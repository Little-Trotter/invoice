package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The guided hint's full lifecycle on the entry pane. The regression pinned here: the hint card
 * used to be built only when something was already missing at pane-open, so a pane opened
 * complete could never surface the hint again — clearing a mandatory field later hit safe calls
 * on a component that did not exist. The card is now always built for an active guided trigger
 * and merely hidden while nothing is missing.
 */
class InvoiceDetailViewGuidedHintKaribuTest {

    private val detailQueryPort = mock<InvoiceDetailQueryPort>()
    private val refs = ReferenceDataMocks()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun stubNoProfile() {
        // Mockito's default answer for a Kotlin Long? is 0L (primitive default), which silently
        // turns every pane guided via the PROFILE trigger — stub the null explicitly.
        whenever(refs.b2bFieldValueQueries.findB2bProfileIdForInvoice(anyOrNull())).thenReturn(null)
    }

    private fun createView(): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
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
                refs = refs,
            ),
        )
    }

    private fun invoice(recipient: PartyDto?, buyerReference: String? = null): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = recipient, items = emptyList(), latestGenerationJob = null, version = 3L,
        buyerReference = buyerReference,
    )

    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto): Component {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result.component
    }

    /** Child traversal on purpose: karibu's _find skips invisible components, and the card
     *  being present-but-hidden is exactly what half these tests assert. */
    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun hintCard(root: Component): Div? =
        flatten(root).filterIsInstance<Div>().firstOrNull { it.id.orElse(null) == "guided-hint" }

    private fun buyerReferenceField(view: InvoiceDetailView, root: Component): TextField {
        // Tab 2's content stays detached until the tab is selected — select it first, exactly
        // like a user who edits the buyer reference would.
        flatten(root).filterIsInstance<TabSheet>().first().selectedIndex = 1
        MockVaadin.clientRoundtrip()
        return flatten(root).filterIsInstance<TextField>()
            .first { it.label == UiTranslations.tr(view, "invoiceDetail.buyerReference") }
    }

    @Test
    fun `an entry pane opened complete builds the hint hidden and surfaces it when a mandatory field is cleared`() {
        stubNoProfile()
        val view = createView()
        // A routing id activates the guided trigger AND auto-fills the buyer reference, so at
        // open nothing is missing — exactly the state that used to leave no hint component.
        val root = buildPane(
            view,
            invoice(PartyDto(id = 7L, name = "Behoerde", routingId = "991-33333-33"), buyerReference = "991-33333-33"),
        )

        val hint = hintCard(root)
        assertNotNull(hint, "the hint card must exist even when nothing is missing at open")
        assertFalse(hint!!.isVisible, "with nothing missing the card starts hidden")

        buyerReferenceField(view, root).value = ""
        MockVaadin.clientRoundtrip()

        assertTrue(hint.isVisible, "clearing the buyer reference must surface the hint")
    }

    @Test
    fun `refilling the cleared field hides the hint again`() {
        stubNoProfile()
        val view = createView()
        val root = buildPane(
            view,
            invoice(PartyDto(id = 7L, name = "Behoerde", routingId = "991-33333-33"), buyerReference = "991-33333-33"),
        )
        val hint = hintCard(root)!!
        val field = buyerReferenceField(view, root)

        field.value = ""
        MockVaadin.clientRoundtrip()
        assertTrue(hint.isVisible)

        field.value = "991-33333-33"
        MockVaadin.clientRoundtrip()
        assertFalse(hint.isVisible, "once nothing is missing again the hint hides")
    }

    @Test
    fun `without a guided trigger no hint card is built at all`() {
        stubNoProfile()
        val view = createView()
        val root = buildPane(view, invoice(PartyDto(id = 7L, name = "Privatkunde", routingId = null)))

        assertNull(hintCard(root), "flat mode has no guided hint component")
    }

    @Test
    fun `editing the buyer reference without a guided trigger stays a no-op, no hint card ever exists`() {
        stubNoProfile()
        val view = createView()
        val root = buildPane(view, invoice(PartyDto(id = 7L, name = "Privatkunde", routingId = null)))

        // refreshGuidedHint() fires on every buyerReference edit regardless of trigger; with no
        // guided-hint component ever built (assertNull above), its own null-safe update must
        // tolerate that instead of assuming a component always exists once a field changes.
        buyerReferenceField(view, root).value = "some value"
        MockVaadin.clientRoundtrip()

        assertNull(hintCard(root), "still no hint card after editing the field")
    }

    @Test
    fun `clicking the hint's dismiss control hides it in place`() {
        stubNoProfile()
        val view = createView()
        val root = buildPane(
            view,
            invoice(PartyDto(id = 7L, name = "Behoerde", routingId = "991-33333-33"), buyerReference = ""),
        )
        val hint = hintCard(root)!!
        assertTrue(hint.isVisible, "a missing mandatory field must start visible")

        val dismiss = flatten(hint).filterIsInstance<com.vaadin.flow.component.html.Span>().first { it.text == "×" }
        dismiss._click()

        assertFalse(hint.isVisible, "dismissing must hide the card in place")
    }
}
