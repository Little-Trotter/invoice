package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fetch
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import de.littletrotter.ui.component.DataTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SenderProfileGridComposerKaribuTest {

    private val service: SenderProfileQueryPort = mock()
    private val grid = DataTable(SenderProfileRowDto::class.java)
    private val composer = SenderProfileGridComposer(grid, service, { it }, { Span(it.name) }, {})

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `typing into the filter reloads the grid with the trimmed lower-case text`() {
        val acme = SenderProfileRowDto(id = 1L, version = 0L, name = "Acme", isDefault = false, invoicePrefix = "RE-", sellerNumber = null, shortHash = null, billingCity = null, billingCountryAlpha2 = null)
        whenever(service.findAll(0, 50, "acme")).thenReturn(listOf(acme))
        whenever(service.count("acme")).thenReturn(1L)
        val filter = composer.buildFilter()
        composer.configureGrid()

        filter.value = "  ACME "

        assertEquals(listOf(acme), grid._fetch(0, 50))
    }
}
