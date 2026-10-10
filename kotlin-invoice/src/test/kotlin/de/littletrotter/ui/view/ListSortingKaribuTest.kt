package de.littletrotter.ui.view

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._sort
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.data.provider.QuerySortOrder
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.ui.view.invoice.ReceiptPoolView
import de.littletrotter.ui.view.masterdata.SenderProfileView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection as DomainDirection
import de.littletrotter.ui.view.masterdata.SenderB2bSectionComposer
import de.littletrotter.ui.view.masterdata.SenderProfileDefaultsComposer

class ListSortingKaribuTest {
    @BeforeEach fun setup() = MockVaadin.setup()
    @AfterEach fun teardown() = MockVaadin.tearDown()

    private fun grid(view: Any): Grid<*> =
        Grid::class.java.cast(view.javaClass.getDeclaredField("grid").apply { isAccessible = true }.get(view))

    @Test
    fun `sender headers forward both sort directions and the requested page`() {
        val service = mock<SellerProfileService>()
        val view = SenderProfileView(
            sellerProfileService = service,
            b2bSectionComposer = SenderB2bSectionComposer(mock(), mock(), mock()),
            defaultsComposer = SenderProfileDefaultsComposer(mock()),
            consumerNoteComposer = de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(mock(), mock(), mock()),
            numberRangeQueries = mock(),
            partyFormRegistry = PartyFormExtensionRegistry(emptyList()),
            sellerVatRegistrationsRegistry = SellerVatRegistrationsExtensionRegistry(emptyList()),
        )
        val grid = grid(view)
        for (key in listOf("name", "cityCountry", "invoicePrefix")) {
            for (direction in SortDirection.entries) {
                val domain = if (direction == SortDirection.ASCENDING) DomainDirection.ASC else DomainDirection.DESC
                val row = SenderProfile().apply { id = 7L; name = "Sorted" }
                whenever(service.findAllSorted(1, 50, null, GridSort(key, domain))).thenReturn(listOf(row))
                grid._sort(QuerySortOrder(key, direction))
                assertEquals(listOf(row), grid._fetch(50, 50))
                assertEquals(key, grid.getColumnByKey(key).getSortOrder(direction).findFirst().orElseThrow().sorted)
                verify(service).findAllSorted(1, 50, null, GridSort(key, domain))
            }
        }
    }

    @Test
    fun `receipt headers forward both directions including an empty later page`() {
        val service = mock<ReceiptPoolService>()
        val grid = grid(ReceiptPoolView(mock(), service, mock()))
        for (key in listOf("fileName", "description", "documentDate", "uploadedAt", "sizeBytes")) {
            for (direction in SortDirection.entries) {
                val domain = if (direction == SortDirection.ASCENDING) DomainDirection.ASC else DomainDirection.DESC
                whenever(service.findAllSorted(1, 50, null, GridSort(key, domain))).thenReturn(emptyList())
                grid._sort(QuerySortOrder(key, direction))
                assertEquals(emptyList<Receipt>(), grid._fetch(50, 50))
                assertEquals(key, grid.getColumnByKey(key).getSortOrder(direction).findFirst().orElseThrow().sorted)
                verify(service).findAllSorted(1, 50, null, GridSort(key, domain))
            }
        }
    }
}
