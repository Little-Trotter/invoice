package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.upload.Upload
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * [ReceiptTarget.Item] and [ReceiptTarget.Invoice] share the exact same tab-bar
 * structure (Zugeordnet / Belegpool / Hochladen), replacing the earlier split where only
 * [ReceiptTarget.Invoice] was tabbed and [ReceiptTarget.Item] stacked everything into one long
 * view. These tests pin that unified structure at the component-tree level: a writable dialog of
 * either target gets the same three live-labelled, icon-prefixed tabs, and a read-only/unsaved one
 * collapses to the attached tab alone — for both targets alike, so a future edit cannot silently
 * reintroduce the asymmetry this task removed.
 */
class ReceiptDialogKaribuTest {

    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        // IR-3 — see ReceiptDialogActionsKaribuTest's own setup() for the rationale.
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(org.mockito.kotlin.any()))
            .thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun receipt(id: Long, name: String) = ReceiptRowDto(
        id = id,
        fileName = name,
        description = "",
        documentDate = null,
        sizeBytes = 100L,
        uploadedAt = OffsetDateTime.now(),
        objectKey = "obj-$id",
    )

    private fun invoice(
        status: Int = InvoiceStatus.DRAFT.ordinal,
        typeCode: Int = InvoiceTypeCode.COMMERCIAL_INVOICE.ordinal,
    ) = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        typeCode = typeCode,
    )

    // Walks the DOM element tree, not Component.getChildren()/HasComponents: TabSheet attaches its
    // tab bar via a named slot at the Element level and isn't a HasComponents itself, so a
    // Component-API-only walk would never see into it (or into anything slotted).
    private fun flatten(component: Component): List<Component> {
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach { walk(it) }
        }
        walk(component.element)
        return found
    }

    private fun dialogRoot(dialog: ReceiptDialog): Dialog {
        val field = ReceiptDialog::class.java.getDeclaredField("dlg")
        field.isAccessible = true
        return field.get(dialog) as Dialog
    }

    // A Tab's own [Tab.getLabel] (`Element.getText()`) only sees text nodes that are
    // DIRECT children of the tab element; the live label here sits two levels down (Tab ->
    // HorizontalLayout -> Span -> text node), which `getText()` silently reports as empty.
    // `getTextRecursively()` walks every descendant instead — the correct way to read a tab's
    // rendered text once it wraps an icon and a label rather than being plain text.
    private fun Tab.renderedText(): String = element.textRecursively

    private fun tabTexts(tabSheet: TabSheet): List<String> =
        (0 until tabSheet.tabCount).map { tabSheet.getTabAt(it).renderedText() }

    @Test
    fun `a writable item dialog now mirrors the invoice dialog with three live tabs`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, null)).thenReturn(0L)

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        val tabSheet = tabSheetOf(dialog)

        assertEquals(3, tabSheet.tabCount, "attached / pool / upload — three tabs, same as the invoice dialog")
        val texts = tabTexts(tabSheet)
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "0"), texts[0])
        // IR-2 — the dialog's compile-fix now reads the invoice-wide budget's count limit
        // (ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE = 19) instead of the removed per-target
        // cap (10); the hint design itself is IR-3's job.
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.tab.pool.remaining", "19"), texts[1])
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.tab.upload"), texts[2])
        assertEachTabCarriesOneDecorativeIcon(tabSheet)
    }

    @Test
    fun `an invoice dialog carries the same three live tabs as an item dialog`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForInvoice(42L, 0, 50, null)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.countAssignableForInvoice(42L, null)).thenReturn(0L)

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, invoice(), ReceiptTarget.Invoice(42L), onChanged = {})
        dialog.open()
        val tabSheet = tabSheetOf(dialog)

        assertEquals(3, tabSheet.tabCount)
        val texts = tabTexts(tabSheet)
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "0"), texts[0])
        // IR-2 — the dialog's compile-fix now reads the invoice-wide budget's count limit
        // (ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE = 19) instead of the removed per-target
        // cap (10); the hint design itself is IR-3's job.
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.tab.pool.remaining", "19"), texts[1])
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.tab.upload"), texts[2])
        assertEachTabCarriesOneDecorativeIcon(tabSheet)
    }

    @Test
    fun `the pool tab label reads the cap as full once the item is at capacity`() {
        val full = (1L..19L).map { receipt(it, "r$it.pdf") }
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(full)
        // IR-3 — the pool tab's "full" label now reads the invoice-wide budget's remainingSlots,
        // not the target-local attached count.
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(
            InvoiceDocumentBudget.calculate(
                (1..19).map { de.littletrotter.shared.receipt.ReceiptSize("k$it", de.littletrotter.shared.receipt.DocumentSize.ofBytes(1024)) },
            ),
        )

        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        val tabSheet = tabSheetOf(dialog)

        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.attached", "19"),
            tabSheet.getTabAt(0).renderedText(),
        )
        assertEquals(
            UiTranslations.tr("invoiceDetail.items.receipts.tab.pool.full"),
            tabSheet.getTabAt(1).renderedText(),
        )
    }

    @Test
    fun `item target dialog on a read-only invoice shows only the attached tab`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(receipt(1L, "a.pdf")))

        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, invoice(status = InvoiceStatus.SENT.ordinal), ReceiptTarget.Item(10L), onChanged = {},
        )
        dialog.open()
        MockVaadin.clientRoundtrip()
        val tree = flatten(dialogRoot(dialog))
        val tabSheet = tabSheetOf(dialog)

        assertEquals(1, tabSheet.tabCount, "read-only: only the attached tab, no pool/upload tabs")
        assertTrue(tree.filterIsInstance<Upload>().isEmpty(), "still no upload on a read-only invoice")
        assertEquals(1, tree.filterIsInstance<DataTable<*>>().size, "only the attached grid")
    }

    // CANCELLED was dead code before it became reachable (no invoice ever reached it), so no
    // receipt path had ever seen it; InvoiceDetailDto.isReadOnly() covers it via
    // InvoiceStatus.isReadOnly, this proves the dialog actually honors that for a CANCELLED
    // invoice specifically, not just SENT.
    @Test
    fun `item target dialog on a cancelled invoice shows only the attached tab`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(receipt(1L, "a.pdf")))

        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort, attachReceiptsUseCase, invoice(status = InvoiceStatus.CANCELLED.ordinal), ReceiptTarget.Item(10L), onChanged = {},
        )
        dialog.open()
        MockVaadin.clientRoundtrip()
        val tree = flatten(dialogRoot(dialog))

        assertTrue(tree.filterIsInstance<Upload>().isEmpty(), "still no upload on a cancelled invoice")
        assertEquals(1, tree.filterIsInstance<DataTable<*>>().size, "only the attached grid")
        assertEquals(1, tabSheetOf(dialog).tabCount)
    }

    // A storno document (CORRECTED_INVOICE, PO decision 1 2026-09-02; previously
    // CREDIT_NOTE) is read-only by type code alone, regardless of its own status (here: DRAFT,
    // which would otherwise be fully writable).
    @Test
    fun `item target dialog on a storno document is read-only regardless of its own status`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(listOf(receipt(1L, "a.pdf")))

        val dialog = ReceiptDialog(
            invoiceReceiptQueryPort,
            attachReceiptsUseCase,
            invoice(status = InvoiceStatus.DRAFT.ordinal, typeCode = InvoiceTypeCode.CORRECTED_INVOICE.ordinal),
            ReceiptTarget.Item(10L),
            onChanged = {},
        )
        dialog.open()
        MockVaadin.clientRoundtrip()
        val tree = flatten(dialogRoot(dialog))

        assertTrue(tree.filterIsInstance<Upload>().isEmpty(), "still no upload on a storno document, DRAFT status notwithstanding")
        assertEquals(1, tree.filterIsInstance<DataTable<*>>().size, "only the attached grid")
        assertEquals(1, tabSheetOf(dialog).tabCount)
    }

    private fun tabSheetOf(dialog: ReceiptDialog): TabSheet =
        flatten(dialogRoot(dialog)).filterIsInstance<TabSheet>().single()

    /** Every tab carries exactly one icon (paperclip / archive / upload), decorative
     *  (`aria-hidden`) so screen readers announce the label text alone, not a duplicated icon name. */
    private fun assertEachTabCarriesOneDecorativeIcon(tabSheet: TabSheet) {
        (0 until tabSheet.tabCount).forEach { i ->
            val tab = tabSheet.getTabAt(i)
            val icons = flatten(tab).filterIsInstance<Icon>()
            assertEquals(1, icons.size, "tab $i must carry exactly one icon")
            assertEquals("true", icons.single().element.getAttribute("aria-hidden"), "the tab icon must be decorative")
        }
    }
}
