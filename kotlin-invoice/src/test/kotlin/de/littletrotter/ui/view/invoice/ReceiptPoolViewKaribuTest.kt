package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.masterdata.Receipt
import java.time.OffsetDateTime

class ReceiptPoolViewKaribuTest {

    private val receiptUploadService: ReceiptUploadService = mock()
    private val receiptPoolService: ReceiptPoolService = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
            // ignore
        }
    }

    @Test
    fun `view loads entries when grid data provider is queried`() {
        // Arrange: return one receipt and count
        val sample = Receipt()
        sample.id = 1L
        sample.fileName = "sample.pdf"
        sample.description = "desc"
        sample.sizeBytes = 1024L
        sample.uploadedAt = OffsetDateTime.now()

        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(listOf(sample))
        whenever(receiptPoolService.countAll(null)).thenReturn(1L)

        // Act: create view and explicitly query the lazy data provider
        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<Receipt>>()
        val provider = grid.dataProvider.requireAs<DataProvider<Receipt, String>>()
        val query = Query<Receipt, String>(0, 50, emptyList(), null, null)
        provider.fetch(query).toList()
        provider.size(query)

        // Assert: service was invoked by the data provider
        verify(receiptPoolService).findAll(0, 50, null)
        verify(receiptPoolService).countAll(null)
    }

    @Test
    fun `view applies selected page size to pagination queries`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolService.findAll(1, 100, null)).thenReturn(emptyList())
        whenever(receiptPoolService.countAll(null)).thenReturn(0L)

        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())

        val pageSizeField = ReceiptPoolView::class.java.getDeclaredField("pageSizeField")
        pageSizeField.isAccessible = true
        val pageSize = pageSizeField.get(view).requireAs<ComboBox<Int>>()
        pageSize.value = 100

        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<Receipt>>()
        val provider = grid.dataProvider.requireAs<DataProvider<Receipt, Void>>()

        val query = Query<Receipt, Void>(100, 100, emptyList(), null, null)
        provider.fetch(query).toList()
        provider.size(query)

        assertEquals(100, grid.pageSize)
        verify(receiptPoolService).findAll(1, 100, null)
    }

    @Test
    fun `view handles fetch and count failures as empty result`() {
        whenever(receiptPoolService.findAll(0, 50, null)).thenThrow(RuntimeException("db down"))
        whenever(receiptPoolService.countAll(null)).thenThrow(RuntimeException("db down"))

        val view = ReceiptPoolView(receiptUploadService, receiptPoolService, mock())
        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<Receipt>>()
        val provider = grid.dataProvider.requireAs<DataProvider<Receipt, Void>>()
        val query = Query<Receipt, Void>(0, 50, emptyList(), null, null)

        assertDoesNotThrow {
            val rows = provider.fetch(query).toList()
            assertEquals(0, rows.size)
            assertEquals(0, provider.size(query))
        }

        verify(receiptPoolService, times(1)).findAll(0, 50, null)
        verify(receiptPoolService, times(1)).countAll(null)
    }
}
