package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO requirement pin (2026-08-30) — the new-invoice dialog's sender (Anbieter) selection
 * contract: the DEFAULT sender must be shown first/initially, and every OTHER active sender
 * must be selectable instead.
 *
 * Originally pinned while the fix was still in flight on main (the dialog rendered the default
 * sender as a read-only [TextField] with its id hard-wired into `createInvoiceWithRecipient`);
 * since main's `buildSellerCombo` fix landed (c33c0ba3d) both halves run enabled:
 *  - "default sender shown first" stays component-agnostic (it also held for the pre-fix UI),
 *  - "other senders selectable" drives the sender [ComboBox] end to end.
 *
 * Same construction/reflection idiom as [InvoiceListViewKaribuTest] (the view is CDI-injected,
 * never routed to in these tests).
 */
class InvoiceCreateDialogSellerSelectionTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    private val defaultSender = aSenderProfile {
        id = 1L
        name = "Haupt-Anbieter GmbH"
        isDefault = true
    }
    private val otherSender = aSenderProfile {
        id = 2L
        name = "Zweit-Anbieter e.K."
        isDefault = false
    }
    private val recipient = aRecipient {
        id = 10L
        name = "Testkunde AG"
        customerNumber = CustomerNumber("KD-0001")
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()

        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(emptyList())
        // invoice-from-customer-row (spec amendment) — InvoiceCreateDialog reads the true recipient
        // count from countRecipients, never the page-capped findAllRecipients list.
        whenever(recipientQuery.countRecipients(anyOrNull())).thenReturn(1L)
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto(), otherSender.toSenderSummaryDto()))
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun openInvoiceCreateDialog(view: InvoiceListView) {
        val method = InvoiceListView::class.java.getDeclaredMethod("openInvoiceCreateDialog")
        method.isAccessible = true
        method.invoke(view)
    }

    /** The sender control's currently displayed text, regardless of which component the dialog
     *  uses for it: the pre-fix read-only [TextField] or the fixed sender [ComboBox]. */
    private fun displayedSenderText(): String {
        val sellerLabel = UiTranslations.tr("invoice.dialog.seller")
        val textField = com.github.mvysny.kaributesting.v10._find(TextField::class.java) { label = sellerLabel }
            .firstOrNull()
        if (textField != null) return textField.value
        val combo = com.github.mvysny.kaributesting.v10._get(ComboBox::class.java) { label = sellerLabel }
        return combo.requireAs<ComboBox<SenderSummaryDto>>().value?.name.orEmpty()
    }

    @Test
    fun `new invoice dialog shows the default sender first`() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoice.dialog.title"), dialog.headerTitle)
        assertTrue(
            displayedSenderText().contains(defaultSender.name),
            "the sender control must initially display the default sender (Standard-Anbieter)",
        )
    }

    @Test
    fun `other senders are selectable and create uses the chosen one`() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val sellerLabel = UiTranslations.tr("invoice.dialog.seller")

        val senderCombo = _get(ComboBox::class.java) { label = sellerLabel }.requireAs<ComboBox<SenderSummaryDto>>()
        assertEquals(defaultSender.toSenderSummaryDto(), senderCombo.value, "default sender must be preselected")

        senderCombo.value = otherSender.toSenderSummaryDto()

        val recipientCombo =
            _get(ComboBox::class.java) { label = UiTranslations.tr("invoice.dialog.recipient") }
                .requireAs<ComboBox<RecipientSummaryDto>>()
        recipientCombo.value = recipient.toRecipientSummaryDto()

        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenReturn(minimalCreatedDto())

        com.github.mvysny.kaributesting.v10._get<com.vaadin.flow.component.button.Button> {
            text = UiTranslations.tr("invoice.dialog.create")
        }.click()

        // A domestic recipient (default ASK preference, no VAT-id set on either party) resolves
        // to Resolved(false): the dialog now always supplies an explicit answer while invisible,
        // rather than leaving the argument null.
        org.mockito.kotlin.verify(createInvoiceUseCase).createInvoiceWithRecipient(otherSender.id!!, recipient.id!!, false)
    }

    /**
     * [InvoiceCreateDialog.buildSellerCombo] must offer every sender
     * [SenderQueryPort.findAllSenders] hands it, including one ranked well beyond the old
     * `defaultPageSize` (60) cap. The port is mocked here (the actual page-size fix is proven
     * by [de.littletrotter.application.masterdata.SenderQueryPortFindAllSendersCapIT] at the service
     * level) — this test instead pins the dialog's own combo-building code against ever
     * re-truncating a list the port already handed it in full, e.g. by reintroducing a
     * `.take(n)` or similar.
     */
    @Test
    fun `a sender beyond position 60 is present and selectable in the seller combo`() {
        val fillerSenders = (1..70).map { i ->
            aSenderProfile {
                id = 100L + i
                // Zero-padded so name-ascending order is numeric: filler-001 .. filler-070.
                name = "Filler-Anbieter ${i.toString().padStart(3, '0')}"
                isDefault = false
            }
        }
        val farBeyondSixty = fillerSenders[65].toSenderSummaryDto() // "Filler-Anbieter 066" — position 67 overall (default + 66 fillers before it).
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()) + fillerSenders.map { it.toSenderSummaryDto() })

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val sellerLabel = UiTranslations.tr("invoice.dialog.seller")
        val senderCombo = _get(ComboBox::class.java) { label = sellerLabel }.requireAs<ComboBox<SenderSummaryDto>>()

        senderCombo.value = farBeyondSixty

        assertEquals(farBeyondSixty, senderCombo.value, "a sender ranked beyond position 60 must be selectable")
    }

    /** Same minimal fixture shape as [InvoiceDetailViewCrossTabGuardTest]'s `minimalDto` — just
     *  enough for the create-click listener to navigate instead of NPE-ing on a null return. */
    private fun minimalCreatedDto(): de.littletrotter.application.dto.InvoiceDetailDto =
        de.littletrotter.application.dto.InvoiceDetailDto(
            id = 77L,
            invoiceNumber = "INV-2026-08-00077",
            status = de.littletrotter.domain.invoice.InvoiceStatus.DRAFT.ordinal,
            invoiceDate = java.time.LocalDate.now(),
            netAmount = de.littletrotter.shared.Money.ZERO,
            vatAmount = de.littletrotter.shared.Money.ZERO,
            grossAmount = de.littletrotter.shared.Money.ZERO,
            sender = null,
            recipient = null,
            items = emptyList(),
            latestGenerationJob = null,
        )
}
