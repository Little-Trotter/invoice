package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getColumnByHeader
import com.github.mvysny.kaributesting.v10._getFormatted
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UI-5 slice 3c — [UnitsTab] had no test file at all: the client-side search filter (label,
 * symbol, UN/ECE code, each a separate OR branch) and the header counter wiring were entirely
 * unexercised.
 */
class UnitsTabKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private inline fun <reified T> declaredField(clazz: Class<*>, tab: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(tab) as T
    }

    private fun grid(tab: UnitsTab): Grid<InvoiceItemUnit> = declaredField(UnitsTab::class.java, tab, "grid")

    private fun install(tab: UnitsTab): Pair<TextField, Span> {
        val actionsSlot = Div()
        tab.installHeaderActions(actionsSlot)
        val search = actionsSlot.children.filter { it is TextField }.findFirst().get() as TextField
        val counter = actionsSlot.children.filter { it is Span }.findFirst().get() as Span
        return search to counter
    }

    @Test
    fun `the domain, label, symbol and UN-ECE code columns render every unit correctly`() {
        val tab = UnitsTab()
        val kilogram = InvoiceItemUnit.KILOGRAM

        val domain = grid(tab)._getColumnByHeader(UiTranslations.tr(tab, "referenceData.units.domain"))._getFormatted(kilogram)
        val label = grid(tab)._getColumnByHeader(UiTranslations.tr(tab, "referenceData.units.label"))._getFormatted(kilogram)
        val symbol = grid(tab)._getColumnByHeader(UiTranslations.tr(tab, "referenceData.units.symbol"))._getFormatted(kilogram)
        val unece = grid(tab)._getColumnByHeader(UiTranslations.tr(tab, "referenceData.units.uneceCode"))._getFormatted(kilogram)

        assertEquals(UiTranslations.tr(tab, kilogram.domain.labelKey), domain)
        assertEquals(UiTranslations.tr(tab, kilogram.labelKey), label)
        assertEquals("kg", symbol)
        assertEquals("KGM", unece)
    }

    @Test
    fun `a blank search shows every unit`() {
        val tab = UnitsTab()
        val (_, counter) = install(tab)

        assertEquals(UiTranslations.tr(tab, "referenceData.counter.total", InvoiceItemUnit.entries.size.toLong()), counter.text)
    }

    @Test
    fun `searching by symbol narrows the counter to the matching units only`() {
        val tab = UnitsTab()
        val (search, counter) = install(tab)

        search.value = "kg" // matches KILOGRAM's symbol, not its (translated) label or UN-ECE code

        assertEquals(UiTranslations.tr(tab, "referenceData.counter.total", 1L), counter.text)
    }

    @Test
    fun `searching by UN-ECE code narrows the counter to the matching units only`() {
        val tab = UnitsTab()
        val (search, counter) = install(tab)

        search.value = "kgm" // KILOGRAM's UN-ECE code; not a substring of its symbol or label

        assertEquals(UiTranslations.tr(tab, "referenceData.counter.total", 1L), counter.text)
    }

    @Test
    fun `searching with no match at all empties the counter`() {
        val tab = UnitsTab()
        val (search, counter) = install(tab)

        search.value = "no-such-unit-zzz"

        assertEquals(UiTranslations.tr(tab, "referenceData.counter.total", 0L), counter.text)
    }
}
