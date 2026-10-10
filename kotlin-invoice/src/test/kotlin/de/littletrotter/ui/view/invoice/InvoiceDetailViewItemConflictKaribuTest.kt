package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.exception.InvoicePositionNotFoundException
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.persistence.OptimisticLockException
import java.math.BigDecimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort

/**
 * WP1 (0.9.10, concurrent-editing) — the item dialogs' write path ([InvoiceItemDialogs]) must
 * surface a concurrent-edit conflict as the same optimistic-lock notification tier every other
 * write path in this app uses ([de.littletrotter.ui.notification.UiNotifications
 * .resolveMessage]), and a position another session already deleted as a readable "deleted
 * concurrently" one — never a raw technical exception string, and never a silent overwrite of
 * `InvoiceDetailState.dto`. Mirrors [de.littletrotter.ui.view.masterdata
 * .RecipientViewOptimisticLockKaribuTest]'s own shape one layer down (a plain CDI bean instead of
 * an [AbstractCrudView] subclass), and reuses [InvoiceDetailViewItemDialogsGoodsServiceKaribuTest]'s
 * fixture idiom (a bare `Div` host, [ItemDialogTestFixtures]-style setup) rather than the whole
 * [InvoiceDetailView].
 */
class InvoiceDetailViewItemConflictKaribuTest {

    private val taxRateQueries = mock<TaxRateQueryPort>()
    private val editInvoiceItemsUseCase = mock<EditInvoiceItemsUseCase>()
    private val host = Div()
    private val state = InvoiceDetailState()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val standard = TaxRateDto(id = 1L, code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), isDefault = true, isActive = true, isSelectable = true)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(standard))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun itemDialogs(): InvoiceItemDialogs {
        val (_, dialogs) = ItemDialogTestFixtures.itemComponents(taxRateQueries, editInvoiceItemsUseCase)
        dialogs.bind(state)
        return dialogs
    }

    /** The invoice the dialog session opened at version 5 — a concurrent write elsewhere already
     *  moved the persisted row past it by the time this session's save/delete reaches the server. */
    private fun invoiceAtVersion(version: Long) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = null, items = emptyList(), latestGenerationJob = null,
        version = version,
    )

    private fun item(category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT) = ItemDto(
        id = 5L, positionId = "1", description = "Bestandsposition", quantity = BigDecimal("2"),
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money(BigDecimal("10.00")),
        taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, category = category.name,
    )

    private fun dialog(): Dialog = _get<Dialog>()
    private fun saveButton(dlg: Dialog): Button = dlg._get<Button> { text = UiTranslations.tr("app.save") }
    private fun notificationTexts(): List<String> = getNotifications().map { it.element.textRecursively }

    @Test
    fun `updateItem conflict shows the translated optimistic-lock notification, never overwrites state, and carries the session's invoice version`() {
        val conflictingInvoice = invoiceAtVersion(5L)
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(eq(1L), any()))
            .thenThrow(OptimisticLockException("Invoice 1 was modified concurrently (expected version 5, found 6)"))

        itemDialogs().openEditInvoiceItemDialog(host, conflictingInvoice, item())
        val dlg = dialog()

        saveButton(dlg).click()

        val captor = argumentCaptor<InvoiceItemCommand>()
        verify(editInvoiceItemsUseCase).updateInvoiceItem(eq(1L), captor.capture())
        assertEquals(5L, captor.firstValue.expectedVersion, "the UI must forward the invoice version the session saw")

        val notifications = notificationTexts()
        assertTrue(notifications.isNotEmpty(), "a version conflict must notify the user")
        assertTrue(
            notifications.any { it.contains(UiTranslations.tr("message.error.optimisticLock")) },
            "expected the translated optimistic-lock message, got: $notifications",
        )
        assertEquals(null, state.dto, "a failed save must never overwrite InvoiceDetailState with stale/partial data")
    }

    @Test
    fun `deleteItem against a position removed by another session shows the translated deleted-concurrently notification`() {
        val invoice = invoiceAtVersion(5L)
        whenever(editInvoiceItemsUseCase.deleteItemFromInvoiceByPosition(eq(1L), eq("1"), eq(5L)))
            .thenThrow(InvoicePositionNotFoundException("1"))

        itemDialogs().deleteItem(host, invoice, item())

        verify(editInvoiceItemsUseCase).deleteItemFromInvoiceByPosition(eq(1L), eq("1"), eq(5L))
        val notifications = notificationTexts()
        assertTrue(notifications.isNotEmpty(), "a delete-vs-update conflict must notify the user")
        assertTrue(
            notifications.any { it.contains(UiTranslations.tr("message.error.itemDeletedConcurrently")) },
            "expected the translated deleted-concurrently message, got: $notifications",
        )
    }

    @Test
    fun `updateItem conflict asks the view to reload the committed invoice`() {
        var reloads = 0
        state.onReloadRequested { reloads++ }
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(eq(1L), any()))
            .thenThrow(RuntimeException("commit failed", OptimisticLockException("stale")))

        itemDialogs().openEditInvoiceItemDialog(host, invoiceAtVersion(5L), item())
        saveButton(dialog()).click()

        assertEquals(1, reloads)
    }

    @Test
    fun `updateItem unexpected failure does not reload`() {
        var reloads = 0
        state.onReloadRequested { reloads++ }
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(eq(1L), any())).thenThrow(IllegalStateException("boom"))

        itemDialogs().openEditInvoiceItemDialog(host, invoiceAtVersion(5L), item())
        saveButton(dialog()).click()

        assertEquals(0, reloads)
    }
}
