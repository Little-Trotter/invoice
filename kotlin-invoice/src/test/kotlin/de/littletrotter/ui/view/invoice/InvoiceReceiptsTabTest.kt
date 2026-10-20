package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.router.HasErrorParameter
import de.littletrotter.fixture.aReceipt
import org.junit.jupiter.api.Assertions.assertEquals
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.dto.TripExpenseDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.shared.Money
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.SelectionActionBar
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.OffsetDateTime

/** Two visible receipt tables retain scope and position identity, including empty states, plus
 *  detach ergonomics: contextual selection bars, single/bulk detach, undo, read-only
 *  suppression and the trip-expense exclusion. */
class InvoiceReceiptsTabTest {

    @BeforeEach
    fun setUp() {
        // Real route registration for ReceiptPoolView — the too-large banner's RouterLink throws
        // unless the target route is actually registered (same reasoning as
        // PrintJobListViewKaribuTest's own setup).
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(ReceiptPoolView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
    }

    @AfterEach
    fun tearDown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // The Belege tab fetches the invoice-wide budget unconditionally on every render; an
    // unstubbed mock returns null there and NPEs. Individual tests below only care about
    // findReceiptsForInvoice/findItemReceiptsForInvoice, so a blanket empty budget is enough here.
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock<InvoiceReceiptQueryPort>().apply {
        whenever(invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val generationJobQueryPort: GenerationJobQueryPort = mock()

    // The Belege tab and the receipt-dialog launcher are CDI beans of their own now — built here,
    // over the same mocked receipt ports every test in this file already stubs, and shared by
    // every view [createView] builds (JUnit creates a fresh instance of this whole test class per
    // test method, so this is still "one launcher/tab per test", same as before).
    private val receiptDialogLauncher = ReceiptDialogLauncher(invoiceReceiptQueryPort, attachReceiptsUseCase, invoiceDetailQueryPort, mock())
    private val invoiceReceiptsTab = InvoiceReceiptsTab(invoiceReceiptQueryPort, attachReceiptsUseCase, receiptDialogLauncher)

    private fun createView(): InvoiceDetailView {
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = invoiceReceiptsTab,
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
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

    /** The one test below that exercises [ReceiptLinkService] directly (through a mocked
     *  [de.littletrotter.domain.repository.ReceiptRepository]) still deals in the domain entity —
     *  the port/DTO boundary sits above that service, not inside it. */
    private fun domainReceipt(id: Long, name: String) = aReceipt {
        this.id = id
        fileName = name
        sizeBytes = 100L
        uploadedAt = OffsetDateTime.now()
    }

    private fun itemDto(id: Long, positionId: String, description: String): ItemDto = ItemDto(
        id = id, positionId = positionId, description = description, quantity = java.math.BigDecimal.ONE,
        unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null,
    )

    private fun invoiceDto(id: Long, items: List<ItemDto>, status: Int = InvoiceStatus.DRAFT.ordinal): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "RE-2026-0001", status = status, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    // ── detach helpers ───────────────────────────────────────────────────────────

    /** Renderer-invocation, same idiom as ReceiptDialogActionsKaribuTest's own `actionsComponent`:
     *  a Grid's `addComponentColumn` `ComponentRenderer` only ever runs while Grid paints an actual
     *  row on the client, so the trailing action column's component is fetched directly instead of
     *  requiring a real client repaint. */
    private fun lastColumnComponent(grid: Grid<Any>, item: Any): Component =
        grid.columns.last().componentFor(item)

    private fun findDetachButton(component: Component): com.vaadin.flow.component.button.Button {
        val candidates = if (component is HorizontalLayout) {
            component.children.toArray().filterIsInstance<com.vaadin.flow.component.button.Button>()
        } else {
            listOf(component as com.vaadin.flow.component.button.Button)
        }
        return candidates.single { it.element.getProperty("title") == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detach") }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun tables(tree: List<Component>): List<Grid<Any>> =
        tree.filterIsInstance<Grid<*>>().map { it.requireAs() }

    private fun rows(grid: Grid<Any>): List<List<String>> =
        grid.genericDataView.items.toList().map { grid._getFormattedRow(it) }

    @Test
    fun `receipt assignment refreshes the committed invoice version before reopening the picker`() {
        val original = invoiceDto(42L, emptyList()).copy(version = 3L)
        val committed = original.copy(version = 4L)
        val assigned = receipt(1L, "synthetic-receipt.pdf")
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(committed)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        val view = createView()
        view.invoice = original
        val content = invoiceReceiptsTab.apply { render(original) }
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(assigned))

        view.detailTabs.invoiceReceiptsTab.receiptDialogLauncher.reloadAndNotify(42L)

        assertEquals(4L, view.invoice?.version)
        assertEquals(listOf("synthetic-receipt.pdf"), rows(tables(flatten(content))[0]).map { it.first() })
    }

    @Test
    fun `belege tab shows both the invoice-level card and the grouped position card`() {
        val invoiceLevelReceipt = receipt(1L, "invoice-level.pdf")
        val positionReceipt = receipt(2L, "position.pdf")
        val item = itemDto(10L, "1", "Beratung")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(invoiceLevelReceipt))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(positionReceipt)))

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)

        val headings = tree.filterIsInstance<H2>().map { it.text }
        assertTrue(headings.contains("Belege an der Rechnung"), "invoice-level card heading must be present")
        assertTrue(headings.contains("Belege an Positionen"), "position card heading must be present")

        val grids = tables(tree)
        assertEquals(2, grids.size)
        assertTrue(rows(grids[0]).single().contains("invoice-level.pdf"))
        val position = rows(grids[1]).single()
        assertEquals(listOf("1", "Beratung", "position.pdf"), position.take(3))
        assertFalse(tree.any { it.element.style.get("display") == "none" }, "no hidden duplicate receipt rows")
    }

    @Test
    fun `belege tab keeps both tables visible when no position carries a receipt`() {
        val item = itemDto(10L, "1", "Beratung")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)

        val headings = tree.filterIsInstance<H2>().map { it.text }
        assertTrue(headings.contains("Belege an der Rechnung"), "invoice-level card must still render")
        assertTrue(headings.contains("Belege an Positionen"))
        assertEquals(2, tables(tree).size)
        assertTrue(tables(tree).all { rows(it).isEmpty() })
    }

    @Test
    fun `belege tab groups only positions that actually carry receipts`() {
        val withReceipt = itemDto(10L, "1", "Beratung")
        val withoutReceipt = itemDto(11L, "2", "Material")
        val invoice = invoiceDto(42L, listOf(withReceipt, withoutReceipt))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(receipt(2L, "p1.pdf"))))

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val positions = rows(tables(flatten(content))[1])
        assertEquals(1, positions.size)
        assertEquals(listOf("1", "Beratung", "p1.pdf"), positions.single().take(3))
    }

    @Test
    fun `findItemReceiptsForInvoice delegates to the repository's batched query`() {
        // Guards the N+1 decision (UX-fix): the aggregated card reads position receipts in one
        // query via the repository method already used by the export path (InvoiceDtoMapper),
        // not via findReceiptsForItem called once per position.
        val repo = mock<de.littletrotter.domain.repository.ReceiptRepository>()
        val invoiceRepo = mock<de.littletrotter.domain.repository.InvoiceRepository>()
        val service = ReceiptLinkService(repo, invoiceRepo, mock(), mock(), mock())
        whenever(repo.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(1L to listOf(domainReceipt(2L, "x.pdf"))))

        val result = service.findItemReceiptsForInvoice(42L)

        assertTrue(result.containsKey(1L))
    }

    // ── document-size budget banner (PO decision) ───────────────────────────────────

    @Test
    fun `the too-large banner is visible with a DocumentTooLarge verdict and links to the receipt pool`() {
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        // Five legacy-sized receipts (5 MiB each): well over the 20 MiB document limit, same
        // fixture shape as ReceiptServiceInvoiceLevelTest/GenerationJobCreationServiceTest.
        val oversized = (1L..5L).map { id ->
            de.littletrotter.shared.receipt.ReceiptSize("legacy-$id.pdf", de.littletrotter.shared.receipt.DocumentSize.ofMebiBytes(5L))
        }
        val budget = InvoiceDocumentBudget.calculate(oversized)
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(budget)

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)

        val expected = de.littletrotter.ui.i18n.UiTranslations.tr(
            "receipt.budget.tooLarge.banner",
            de.littletrotter.ui.format.DocumentSizeFormatter.format(budget.estimatedDocumentSize),
            de.littletrotter.ui.format.DocumentSizeFormatter.format(de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE),
        )
        assertTrue(tree.filterIsInstance<com.vaadin.flow.component.html.Span>().any { it.text == expected }, "the banner message must be shown")
        val link = tree.filterIsInstance<com.vaadin.flow.router.RouterLink>().singleOrNull { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.budget.tooLarge.link") }
        assertTrue(link != null, "the banner must offer a link to the receipt pool")
    }

    @Test
    fun `the too-large banner is absent with an Accepted verdict`() {
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(42L)).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)

        assertTrue(tree.none { it.element.classList.contains("receipt-budget-too-large-banner") }, "no banner without a DocumentTooLarge verdict")
    }

    // ── detach ergonomics ─────────────────────────────────────────────────────────

    @Test
    fun `a single row detach on the invoice-level table executes immediately and offers undo`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1), emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]

        findDetachButton(lastColumnComponent(invoiceGrid, r1))._click()

        verify(attachReceiptsUseCase).detachReceipts(
            eq(42L),
            eq(ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L))),
            // The fixture's invoiceDto() never sets a version, so the view's captured invoice
            // (the one this row-click reads current.version from) genuinely carries a null
            // expectedVersion here; mockito-kotlin's any() does not match null, only anyOrNull().
            anyOrNull(),
        )
        assertEquals(5L, view.invoice?.version, "the view's invoice must be refreshed to the committed version")
    }

    @Test
    fun `undo after a single detach re-attaches through addReceiptsToInvoice with the current version`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1), emptyList(), listOf(r1))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L), invoice.copy(version = 6L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        findDetachButton(lastColumnComponent(invoiceGrid, r1))._click()

        val undoButton = allNotificationButtons().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.undo") }
        undoButton._click()

        verify(attachReceiptsUseCase).addReceiptsToInvoice(eq(42L), eq(listOf(1L)), eq(5L))
        assertEquals(6L, view.invoice?.version)
    }

    @Test
    fun `B6 - undo failing because the invoice became read-only shows an error instead of crashing`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1), emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L))
        whenever(attachReceiptsUseCase.addReceiptsToInvoice(eq(42L), eq(listOf(1L)), any()))
            .thenThrow(InvoiceImmutableException("RE-2026-0001"))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        findDetachButton(lastColumnComponent(invoiceGrid, r1))._click()
        val undoButton = allNotificationButtons().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.undo") }

        assertDoesNotThrow { undoButton._click() }
    }

    @Test
    fun `B2 - a bulk selection spanning the invoice level and two positions issues one atomic combined call`() {
        val invoiceLevel = receipt(1L, "invoice-level.pdf")
        val onFirst = receipt(2L, "first.pdf")
        val onSecond = receipt(3L, "second.pdf")
        val firstItem = itemDto(10L, "1", "Beratung")
        val secondItem = itemDto(11L, "2", "Material")
        val invoice = invoiceDto(42L, listOf(firstItem, secondItem))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(invoiceLevel))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L))
            .thenReturn(mapOf(10L to listOf(onFirst), 11L to listOf(onSecond)))
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 9L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)
        val invoiceGrid = tables(tree)[0]
        val positionGrid = tables(tree)[1]

        invoiceGrid.select(invoiceLevel)
        positionGrid.selectedItems // touch to ensure provider initialised
        val positionRows = positionGrid.genericDataView.items.toList()
        positionRows.forEach { positionGrid.select(it) }

        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detachSelected") }
            ._click()

        // ConfirmDialog is a client-side web component: its confirm/cancel controls are rendered
        // by the client, not as server-side Button components, so the confirm action is fired the
        // same way the client's own "confirm" DOM event would (ComponentUtil.fireEvent), rather
        // than searched for as a Button.
        val confirmDialog = allComponentsInUi().filterIsInstance<ConfirmDialog>().single()
        com.vaadin.flow.component.ComponentUtil.fireEvent(confirmDialog, ConfirmDialog.ConfirmEvent(confirmDialog, false))

        verify(attachReceiptsUseCase).detachReceipts(
            eq(42L),
            eq(
                ReceiptDetachSelection(
                    invoiceLevelReceiptIds = setOf(1L),
                    itemReceiptIds = mapOf(10L to setOf(2L), 11L to setOf(3L)),
                ),
            ),
            // Same null-version reason as the single-row detach test above.
            anyOrNull(),
        )
    }

    @Test
    fun `B1 - a read-only invoice shows the read-only hint and no selection or detach controls`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList(), status = InvoiceStatus.ISSUED.ordinal)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)

        assertTrue(tree.any { it is com.vaadin.flow.component.html.Span && it.text == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.receipts.readOnlyHint") })
        assertTrue(tree.filterIsInstance<SelectionActionBar>().isEmpty(), "no selection bar at all on a read-only invoice")
        val invoiceGrid = tables(tree)[0]
        assertEquals(Grid.SelectionMode.NONE, invoiceGrid.selectionMode, "no selection column on a read-only invoice")
    }

    @Test
    fun `B7 - clearing the selection hides the bar and re-shows the normal header`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val tree = flatten(content)
        val invoiceGrid = tables(tree)[0]

        invoiceGrid.select(r1)
        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        assertTrue(bar.isVisible)

        bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.element.getAttribute("aria-label") == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.selection.clear") }
            ._click()

        assertTrue(invoiceGrid.selectedItems.isEmpty())
        assertFalse(bar.isVisible)
    }

    @Test
    fun `B9 - a trip-expense receipt id is never offered in the position table`() {
        val tripReceiptId = 999L
        val tripItem = ItemDto(
            id = 20L, positionId = "1", description = "Reise", quantity = BigDecimal.ONE,
            unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
            netAmount = null, vatAmount = null, grossAmount = null,
            category = "TRIP",
            trip = TripDto(
                detailedOnInvoice = true,
                fixedNetPrice = null,
                calculatedNet = Money.ZERO,
                expenses = listOf(
                    TripExpenseDto(
                        id = 1L, invoiceItemId = 20L, kind = "TAXI", description = "Taxi",
                        travelDate = null, quantity = BigDecimal.ONE, unit = null,
                        unitPriceNet = Money.ZERO, netAmount = Money.ZERO, receiptId = tripReceiptId,
                    ),
                ),
            ),
        )
        val invoice = invoiceDto(42L, listOf(tripItem))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        // The tab only ever reads invoice_receipt/invoice_item_receipt; a TripExpense's own
        // receipt_id (owned by TripDialog) is never part of either map.
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val positionRows = rows(tables(flatten(content))[1])

        assertTrue(positionRows.none { row -> row.any { it.contains(tripReceiptId.toString()) } })
    }

    // ── Bar-driven single/bulk detach, the invoice-reference guard branches, and the
    // budget/validation exception types guardReceiptAction also documents handling ─────────────

    @Test
    fun `selecting exactly one invoice-level row via the bar action detaches immediately with undo`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1), emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        invoiceGrid.select(r1)

        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detachSelected") }
            ._click()

        verify(attachReceiptsUseCase).detachReceipts(
            eq(42L),
            eq(ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L))),
            anyOrNull(),
        )
        assertTrue(allComponentsInUi().filterIsInstance<ConfirmDialog>().isEmpty(), "exactly one row never opens a confirm dialog")
        val undoButton = allNotificationButtons().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.undo") }
        assertDoesNotThrow { undoButton._click() }
    }

    @Test
    fun `selecting exactly one position row via the bar action detaches immediately and undo re-attaches to the item`() {
        val item = itemDto(10L, "1", "Beratung")
        val posReceipt = receipt(2L, "pos.pdf")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L))
            .thenReturn(mapOf(10L to listOf(posReceipt)), emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 7L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val positionGrid = tables(flatten(content))[1]
        positionGrid.select(positionGrid.genericDataView.items.toList().single())

        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detachSelected") }
            ._click()

        verify(attachReceiptsUseCase).detachReceipts(
            eq(42L),
            eq(ReceiptDetachSelection(itemReceiptIds = mapOf(10L to setOf(2L)))),
            anyOrNull(),
        )
        val undoButton = allNotificationButtons().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.undo") }
        undoButton._click()
        verify(attachReceiptsUseCase).addReceiptsToItem(eq(10L), eq(listOf(2L)), eq(7L))
    }

    @Test
    fun `B1 - a read-only invoice's position table offers only the go-to-position button, no detach`() {
        val item = itemDto(10L, "1", "Beratung")
        val posReceipt = receipt(2L, "pos.pdf")
        val invoice = invoiceDto(42L, listOf(item), status = InvoiceStatus.ISSUED.ordinal)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(posReceipt)))

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val positionGrid = tables(flatten(content))[1]
        val row = positionGrid.genericDataView.items.toList().single()
        val actions = lastColumnComponent(positionGrid, row)

        assertTrue(
            actions is com.vaadin.flow.component.button.Button,
            "read-only: a single go-to-position button, not the writable pairing with a detach button",
        )
        assertEquals(
            de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.items.receipts.count", "1"),
            (actions as com.vaadin.flow.component.button.Button).text,
        )
    }

    @Test
    fun `the go-to-position button of a read-only invoice opens that position's receipt dialog`() {
        val item = itemDto(10L, "1", "Beratung")
        val invoice = invoiceDto(42L, listOf(item), status = InvoiceStatus.ISSUED.ordinal)
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(receipt(2L, "pos.pdf"))))
        val content = invoiceReceiptsTab.apply { render(invoice) }
        com.vaadin.flow.component.UI.getCurrent().add(content)
        val positionGrid = tables(flatten(content))[1]
        val goTo = lastColumnComponent(positionGrid, positionGrid.genericDataView.items.toList().single())

        (goTo as com.vaadin.flow.component.button.Button)._click()
        MockVaadin.clientRoundtrip()

        verify(invoiceReceiptQueryPort).findReceiptsForItem(10L)
        assertTrue(_find<com.vaadin.flow.component.dialog.Dialog>().any { it.isOpened })
    }

    @Test
    fun `the manage button opens the invoice receipt dialog`() {
        val invoice = invoiceDto(42L, listOf(itemDto(10L, "1", "Beratung")))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        val content = invoiceReceiptsTab.apply { render(invoice) }
        com.vaadin.flow.component.UI.getCurrent().add(content)

        flatten(content).filterIsInstance<com.vaadin.flow.component.button.Button>()
            .single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("invoiceDetail.receipts.manage") }._click()
        MockVaadin.clientRoundtrip()

        assertTrue(_find<com.vaadin.flow.component.dialog.Dialog>().any { it.isOpened })
    }

    @Test
    fun `a single row detach on the position table executes immediately and undo re-attaches to the item`() {
        val item = itemDto(10L, "1", "Beratung")
        val posReceipt = receipt(2L, "pos.pdf")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L))
            .thenReturn(mapOf(10L to listOf(posReceipt)), emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val positionGrid = tables(flatten(content))[1]
        val row = positionGrid.genericDataView.items.toList().single()

        findDetachButton(lastColumnComponent(positionGrid, row))._click()

        verify(attachReceiptsUseCase).detachReceipts(
            eq(42L),
            eq(ReceiptDetachSelection(itemReceiptIds = mapOf(10L to setOf(2L)))),
            anyOrNull(),
        )
        assertEquals(5L, view.invoice?.version)
    }

    @Test
    fun `B2 - a bulk selection of two invoice-level rows only shows the invoice-only confirm text and issues one call`() {
        val r1 = receipt(1L, "a.pdf")
        val r2 = receipt(2L, "b.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1, r2))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 9L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        invoiceGrid.select(r1)
        invoiceGrid.select(r2)

        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detachSelected") }
            ._click()

        val confirmDialog = allComponentsInUi().filterIsInstance<ConfirmDialog>().single()
        assertEquals(
            de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.confirm.invoice", "2"),
            confirmDialog.element.getProperty("message"),
            "two invoice-level rows only: the position-count wording must not appear",
        )
        com.vaadin.flow.component.ComponentUtil.fireEvent(confirmDialog, ConfirmDialog.ConfirmEvent(confirmDialog, false))

        verify(attachReceiptsUseCase).detachReceipts(
            eq(42L),
            eq(ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L, 2L))),
            anyOrNull(),
        )
    }

    @Test
    fun `cancelling the bulk confirm dialog leaves the selection and receipts untouched`() {
        val r1 = receipt(1L, "a.pdf")
        val r2 = receipt(2L, "b.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1, r2))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        invoiceGrid.select(r1)
        invoiceGrid.select(r2)
        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detachSelected") }
            ._click()
        val confirmDialog = allComponentsInUi().filterIsInstance<ConfirmDialog>().single()

        com.vaadin.flow.component.ComponentUtil.fireEvent(confirmDialog, ConfirmDialog.CancelEvent(confirmDialog, false))

        verify(attachReceiptsUseCase, never()).detachReceipts(any(), any(), anyOrNull())
        assertEquals(setOf(r1, r2), invoiceGrid.selectedItems, "cancel must not clear the selection")
    }

    // Belege-tab detach clicks used to guard on `InvoiceDetailView.invoice` turning null between
    // render and click — a defensive branch, never actually reachable in production (nothing in
    // this module ever sets `invoice = null` once an invoice has loaded). InvoiceReceiptsTab now
    // holds its own `current` invoice independently of the view, set once per render and never
    // cleared, so that branch has no equivalent to test any more; the four tests that forced
    // `view.invoice = null` to exercise it are removed rather than kept for a race that cannot
    // happen.

    @Test
    fun `the bar action button does nothing if the selection became empty before it was invoked`() {
        // Defensive branch for a race between a queued click and a selection that clears in the
        // meantime; Button#click() only checks isEnabled (unlike karibu-testing's _click(), which
        // also requires isVisible), so it reaches this guard the same way that race would.
        val r1 = receipt(1L, "a.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        invoiceGrid.select(r1)
        val bar = flatten(content).filterIsInstance<SelectionActionBar>().first { it.isVisible }
        val actionButton = bar._find<com.vaadin.flow.component.button.Button>()
            .first { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.action.detachSelected") }
        invoiceGrid.deselectAll()

        assertDoesNotThrow { actionButton.click() }

        verify(attachReceiptsUseCase, never()).detachReceipts(any(), any(), anyOrNull())
    }

    @Test
    fun `undo failing because the invoice-wide receipt cap would be exceeded still shows an error instead of crashing`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1), emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L))
        whenever(attachReceiptsUseCase.addReceiptsToInvoice(eq(42L), eq(listOf(1L)), any()))
            .thenThrow(ReceiptCountLimitExceededException(19, 20))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        findDetachButton(lastColumnComponent(invoiceGrid, r1))._click()
        val undoButton = allNotificationButtons().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.undo") }

        assertDoesNotThrow { undoButton._click() }
    }

    @Test
    fun `undo re-attaching a receipt still succeeds even though it pushes the document budget over the limit`() {
        // PO decision: the document-size verdict is advisory at link time now — only
        // GenerationJobCreationService refuses on size, so an undo that happens to push the
        // invoice's estimate past the limit must not fail, unlike the still-refused count guard
        // covered by the sibling test right above this one.
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1), emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(invoiceDetailQueryPort.findInvoiceDetailDto(42L)).thenReturn(invoice.copy(version = 5L))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]
        findDetachButton(lastColumnComponent(invoiceGrid, r1))._click()
        val undoButton = allNotificationButtons().single { it.text == de.littletrotter.ui.i18n.UiTranslations.tr("receipt.detach.undo") }

        assertDoesNotThrow { undoButton._click() }

        verify(attachReceiptsUseCase).addReceiptsToInvoice(eq(42L), eq(listOf(1L)), any())
    }

    @Test
    fun `a detach failing with a validation error is caught and shows the exception's own message`() {
        val r1 = receipt(1L, "invoice-level.pdf")
        val invoice = invoiceDto(42L, emptyList())
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(r1))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(emptyMap())
        whenever(attachReceiptsUseCase.detachReceipts(eq(42L), any(), anyOrNull()))
            .thenThrow(ApplicationValidationException("Receipt already detached"))
        val view = createView()
        view.invoice = invoice
        val content = invoiceReceiptsTab.apply { render(invoice) }
        val invoiceGrid = tables(flatten(content))[0]

        assertDoesNotThrow { findDetachButton(lastColumnComponent(invoiceGrid, r1))._click() }

        assertTrue(
            allComponentsInUi().filterIsInstance<com.vaadin.flow.component.html.Span>().any { it.text == "Receipt already detached" },
            "an ApplicationUserException's own message must reach the notification",
        )
    }

    // ── an item explicitly mapped to an empty receipt list, and a receipt's own (non-blank)
    // description in both grids. ──────────────────────────────────────────────────────────────

    @Test
    fun `belege tab treats an item explicitly mapped to an empty receipt list the same as one absent from the map`() {
        val withReceipt = itemDto(10L, "1", "Beratung")
        val explicitlyEmpty = itemDto(11L, "2", "Material")
        val invoice = invoiceDto(42L, listOf(withReceipt, explicitlyEmpty))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L))
            .thenReturn(mapOf(10L to listOf(receipt(2L, "p1.pdf")), 11L to emptyList()))

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val positions = rows(tables(flatten(content))[1])

        assertEquals(1, positions.size, "an item mapped to an explicit empty list must not appear either")
    }

    @Test
    fun `belege tab shows a receipt's own description instead of the blank placeholder when one is set`() {
        val invoiceLevelReceipt = receipt(1L, "invoice-level.pdf").copy(description = "Hotelrechnung")
        val item = itemDto(10L, "1", "Beratung")
        val positionReceipt = receipt(2L, "position.pdf").copy(description = "Taxiquittung")
        val invoice = invoiceDto(42L, listOf(item))
        whenever(invoiceReceiptQueryPort.findReceiptsForInvoice(42L)).thenReturn(listOf(invoiceLevelReceipt))
        whenever(invoiceReceiptQueryPort.findItemReceiptsForInvoice(42L)).thenReturn(mapOf(10L to listOf(positionReceipt)))

        val content = invoiceReceiptsTab.apply { render(invoice) }
        val grids = tables(flatten(content))

        assertTrue(rows(grids[0]).single().contains("Hotelrechnung"), "the invoice-level grid must show a set description")
        assertTrue(rows(grids[1]).single().contains("Taxiquittung"), "the position grid must show a set description")
    }

    // Two more branches used to be covered here: refreshing before the tab was ever built (the
    // tab is now a CDI-injected component that always exists once the test constructs it, so
    // there is no "never built" state to guard any more), and undo after `view.invoice` was
    // cleared (the same production-unreachable race the four removed detach tests above already
    // explain — InvoiceReceiptsTab's own `current` is independent of the view and never cleared).

    /** Everything currently attached anywhere in the mock UI — needed for a [ConfirmDialog] or a
     *  [UiNotifications.actionable] undo notification, both of which attach to [UI.getCurrent]
     *  directly rather than into the tab's own [content] subtree. Same idiom as
     *  ReceiptDialogActionsKaribuTest's `assertNotificationShown`/`allComponents`: a
     *  [MockVaadin.clientRoundtrip] flushes pending server-side writes first — a bare `_click()`
     *  does not attach a `Notification`'s content into the element tree on its own. */
    private fun allComponentsInUi(): List<Component> {
        MockVaadin.clientRoundtrip()
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(com.vaadin.flow.component.UI.getCurrent().element)
        return found
    }

    private fun allNotificationButtons(): List<com.vaadin.flow.component.button.Button> =
        allComponentsInUi().filterIsInstance<com.vaadin.flow.component.button.Button>()
}
