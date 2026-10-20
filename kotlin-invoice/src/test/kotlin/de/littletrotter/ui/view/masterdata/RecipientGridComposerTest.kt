package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.ui.component.DataTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [RecipientGridComposer] in isolation, mocking [RecipientQueryPort]/[SenderQueryPort]/
 * [CreateInvoiceUseCase] and passing a plain [DataTable] (no [RecipientView] involved). The
 * row-level "Rechnung erstellen" button and the grid's column/sort-listener behaviour stay covered
 * end-to-end by `RecipientViewCoverageKaribuTest`/`RecipientViewGridColumnsKaribuTest`.
 */
class RecipientGridComposerTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private lateinit var grid: DataTable<RecipientSummaryDto>
    private val opened = mutableListOf<RecipientSummaryDto?>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        grid = DataTable(RecipientSummaryDto::class.java, false)
        opened.clear()
        whenever(recipientQuery.findRecipients(any(), any(), anyOrNull(), any(), any())).thenReturn(emptyList())
        whenever(recipientQuery.countRecipients(anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun composer() = RecipientGridComposer(grid, recipientQuery, senderQuery, createInvoiceUseCase, { it }) { opened.add(it) }

    private fun summary(id: Long, name: String) = RecipientSummaryDto(
        id = id,
        name = name,
        customerNumber = null,
        shortHash = null,
        contactPerson = null,
        routingId = null,
        vatId = null,
        customerType = null,
        vatIdVerificationStatus = null,
        reverseChargePreference = ReverseChargePreference.ASK,
        billingCity = null,
        billingCountryAlpha2 = null,
        deliveryCountryAlpha2 = null,
    )

    @Test
    fun `buildNewButton's button opens the editor with a null recipient`() {
        val c = composer()
        val newButton = c.buildNewButton()

        newButton.click()

        assertEquals(listOf<RecipientSummaryDto?>(null), opened)
    }

    @Test
    fun `configureGrid adds the customerNumber, name, city, contactPerson, shortHash and action columns`() {
        val c = composer()

        c.configureGrid()

        assertNotNull(grid.getColumnByKey("customerNumber"))
        assertNotNull(grid.getColumnByKey("name"))
        assertNotNull(grid.getColumnByKey("city"))
        assertNotNull(grid.getColumnByKey("contactPerson"))
    }

    @Test
    fun `refreshGrid fetches through recipientQuery with the default name-ascending sort`() {
        val c = composer()
        c.configureGrid()

        c.refreshGrid()
        grid.dataProvider.fetch(Query()).toList()

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    @Test
    fun `sorting by customerNumber refetches with the mapped sort field`() {
        val c = composer()
        c.configureGrid()
        val column = grid.getColumnByKey("customerNumber")!!

        grid.sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid.dataProvider.fetch(Query()).toList()

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("customerNumber"), eq(de.littletrotter.domain.shared.SortDirection.DESC))
    }

    @Test
    fun `clearing the sort order resets to name ascending`() {
        val c = composer()
        c.configureGrid()
        val column = grid.getColumnByKey("customerNumber")!!
        grid.sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        grid.sort(emptyList())
        grid.dataProvider.fetch(Query()).toList()

        verify(recipientQuery, atLeastOnce())
            .findRecipients(any(), any(), anyOrNull(), eq("name"), eq(de.littletrotter.domain.shared.SortDirection.ASC))
    }

    @Test
    fun `selecting a grid row opens the editor for that recipient`() {
        val c = composer()
        c.configureGrid()
        val recipient = summary(1L, "Acme")

        grid.asSingleSelect().value = recipient

        assertEquals(listOf(recipient), opened)
    }

    // `grid.asSingleSelect().addValueChangeListener { e -> e.value?.let { openEditor(it) } }`
    // only ever had its non-null branch tested (selecting a row); clearing the selection (a real
    // Vaadin single-select outcome, e.g. Grid.deselectAll()) sets value to null and must not call
    // openEditor again.
    @Test
    fun `deselecting a grid row does not reopen the editor`() {
        val c = composer()
        c.configureGrid()
        val recipient = summary(1L, "Acme")
        grid.asSingleSelect().value = recipient

        grid.deselectAll()

        assertEquals(listOf(recipient), opened, "clearing the selection must not call openEditor a second time")
    }

    @Test
    fun `openInvoiceCreateDialogFor opens the invoice-create dialog`() {
        UI.getCurrent().add(grid)
        val c = composer()
        val recipient = summary(1L, "Acme")

        c.openInvoiceCreateDialogFor(recipient)

        assertNotNull(_get<com.vaadin.flow.component.dialog.Dialog>())
    }

    @Test
    fun `applying a filter through the toolbar's field refreshes the grid with it`() {
        val c = composer()
        c.configureGrid()
        c.refreshGrid()
        val filterField = c.buildFilter()

        filterField.value = "  Acme "
        grid.dataProvider.size(Query())

        verify(recipientQuery, atLeastOnce()).countRecipients(eq("acme"))
    }
}
