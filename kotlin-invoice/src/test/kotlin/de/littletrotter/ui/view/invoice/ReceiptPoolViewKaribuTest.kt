package de.littletrotter.ui.view.invoice

import de.littletrotter.testing.receiptView
import de.littletrotter.application.invoice.port.inbound.UploadReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptPoolQueryPort
import de.littletrotter.application.invoice.port.inbound.EditReceiptDetailsUseCase
import de.littletrotter.application.invoice.port.inbound.DeleteReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptView
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
import java.time.OffsetDateTime

class ReceiptPoolViewKaribuTest {

    private val uploadReceiptUseCase: UploadReceiptUseCase = mock()
    private val receiptPoolQueryPort: ReceiptPoolQueryPort = mock()
    private val editReceiptDetailsUseCase: EditReceiptDetailsUseCase = mock()
    private val deleteReceiptUseCase: DeleteReceiptUseCase = mock()

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
        val sample = receiptView {
            id = 1L
            fileName = "sample.pdf"
            description = "desc"
            sizeBytes = 1024L
            uploadedAt = OffsetDateTime.now()
        }

        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenReturn(listOf(sample))
        whenever(receiptPoolQueryPort.countAll(null)).thenReturn(1L)

        // Act: create view and explicitly query the lazy data provider
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<ReceiptView>>()
        val provider = grid.dataProvider.requireAs<DataProvider<ReceiptView, String>>()
        val query = Query<ReceiptView, String>(0, 50, emptyList(), null, null)
        provider.fetch(query).toList()
        provider.size(query)

        // Assert: service was invoked by the data provider
        verify(receiptPoolQueryPort).findReceiptViews(0, 50, null)
        verify(receiptPoolQueryPort).countAll(null)
    }

    @Test
    fun `view applies selected page size to pagination queries`() {
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenReturn(emptyList())
        whenever(receiptPoolQueryPort.findReceiptViews(1, 100, null)).thenReturn(emptyList())
        whenever(receiptPoolQueryPort.countAll(null)).thenReturn(0L)

        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val pageSizeField = ReceiptPoolView::class.java.getDeclaredField("pageSizeField")
        pageSizeField.isAccessible = true
        val pageSize = pageSizeField.get(view).requireAs<ComboBox<Int>>()
        pageSize.value = 100

        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<ReceiptView>>()
        val provider = grid.dataProvider.requireAs<DataProvider<ReceiptView, Void>>()

        val query = Query<ReceiptView, Void>(100, 100, emptyList(), null, null)
        provider.fetch(query).toList()
        provider.size(query)

        assertEquals(100, grid.pageSize)
        verify(receiptPoolQueryPort).findReceiptViews(1, 100, null)
    }

    @Test
    fun `view handles fetch and count failures as empty result`() {
        whenever(receiptPoolQueryPort.findReceiptViews(0, 50, null)).thenThrow(RuntimeException("db down"))
        whenever(receiptPoolQueryPort.countAll(null)).thenThrow(RuntimeException("db down"))

        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val gridField = ReceiptPoolView::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        val grid = gridField.get(view).requireAs<com.vaadin.flow.component.grid.Grid<ReceiptView>>()
        val provider = grid.dataProvider.requireAs<DataProvider<ReceiptView, Void>>()
        val query = Query<ReceiptView, Void>(0, 50, emptyList(), null, null)

        assertDoesNotThrow {
            val rows = provider.fetch(query).toList()
            assertEquals(0, rows.size)
            assertEquals(0, provider.size(query))
        }

        verify(receiptPoolQueryPort, times(1)).findReceiptViews(0, 50, null)
        verify(receiptPoolQueryPort, times(1)).countAll(null)
    }
}
