package de.littletrotter.ui.view.system

import com.github.mvysny.kaributesting.v10._findAll
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SbomViewsTest {

    @Test
    fun `sbom panel shows empty state when no components are available`() {
        val panel = SbomPanel { emptyList() }

        assertTrue(texts(panel).any { it.contains(UiTranslations.tr("system.sbom.empty")) })
    }

    @Test
    fun `sbom panel renders filter and data grid when components are available`() {
        val panel = SbomPanel { sampleComponents() }

        assertEquals(1, flatten(panel).filterIsInstance<TextField>().size)
        assertEquals(1, flatten(panel).filterIsInstance<DataTable<*>>().size)
        assertTrue(texts(panel).any { it.contains("SBOM") })
    }

    @Test
    fun `sbom view shows empty state when no components are available`() {
        val view = SbomView { emptyList() }

        assertTrue(texts(view).any { it.contains(UiTranslations.tr("system.sbom.empty")) })
    }

    @Test
    fun `boundary - the route's default constructor wires SbomDataLoader loadComponents and renders without throwing`() {
        val view = SbomView()

        assertTrue(flatten(view).filterIsInstance<DataTable<*>>().size <= 1)
    }

    @Test
    fun `sbom view renders filter and data grid when components are available`() {
        val view = SbomView { sampleComponents() }

        assertEquals(1, flatten(view).filterIsInstance<TextField>().size)
        assertEquals(1, flatten(view).filterIsInstance<DataTable<*>>().size)
    }

    // ── UI-5 slice 3c — the filter TextField's value-change listener (name-or-description
    //    contains, case-insensitive) was entirely unexercised ─────────────────────────────────

    private fun fetchGridItems(panel: Component): List<SbomComponent> {
        val grid = flatten(panel).filterIsInstance<DataTable<*>>().single()
        return grid._findAll().filterIsInstance<SbomComponent>()
    }

    @Test
    fun `filtering by name narrows the grid to the matching component only`() {
        val panel = SbomPanel { sampleComponents() + SbomComponent("Other", "2.0", "library", "MIT", "unrelated entry") }
        val filterField = flatten(panel).filterIsInstance<TextField>().single()

        filterField.value = "core"

        assertEquals(listOf("Core"), fetchGridItems(panel).map { it.name })
    }

    @Test
    fun `filtering by description narrows the grid to the matching component only`() {
        val panel = SbomPanel { sampleComponents() + SbomComponent("Other", "2.0", "library", "MIT", "unrelated entry") }
        val filterField = flatten(panel).filterIsInstance<TextField>().single()

        filterField.value = "runtime"

        assertEquals(listOf("Core"), fetchGridItems(panel).map { it.name })
    }

    @Test
    fun `filtering with no match empties the grid`() {
        val panel = SbomPanel { sampleComponents() }
        val filterField = flatten(panel).filterIsInstance<TextField>().single()

        filterField.value = "no-such-component-zzz"

        assertTrue(fetchGridItems(panel).isEmpty())
    }

    // ── description cell, bean constructor, filter normalisation ──────────────────────────────

    private fun descriptionCell(panel: SbomPanel, component: SbomComponent): com.vaadin.flow.component.html.Span =
        panel.descriptionCell(component)

    @Test
    fun `happy path - the description cell shows the text and carries it as a tooltip for truncated rows`() {
        val component = sampleComponents().single()
        val panel = SbomPanel { listOf(component) }

        val cell = descriptionCell(panel, component)

        assertEquals("Runtime dependency", cell.text)
        assertEquals("Runtime dependency", cell.element.getAttribute("title"))
        assertTrue(cell.classNames.contains("sbom-description-cell"))
    }

    @Test
    fun `boundary - the bean constructor loads the packaged SBOM and renders without throwing`() {
        val panel = SbomPanel()

        assertTrue(texts(panel).any { it.contains("SBOM") })
    }

    @Test
    fun `boundary - the filter ignores case and surrounding whitespace`() {
        val panel = SbomPanel { sampleComponents() + SbomComponent("Other", "2.0", "library", "MIT", "unrelated entry") }
        val filterField = flatten(panel).filterIsInstance<TextField>().single()

        filterField.value = "  CORE  "

        assertEquals(listOf("Core"), fetchGridItems(panel).map { it.name })
    }

    @Test
    fun `boundary - clearing the filter shows every component again`() {
        val panel = SbomPanel { sampleComponents() + SbomComponent("Other", "2.0", "library", "MIT", "unrelated entry") }
        val filterField = flatten(panel).filterIsInstance<TextField>().single()
        filterField.value = "core"

        filterField.value = ""

        assertEquals(listOf("Core", "Other"), fetchGridItems(panel).map { it.name })
    }

    private fun sampleComponents(): List<SbomComponent> = listOf(
        SbomComponent("Core", "1.0.0", "library", "Apache-2.0", "Runtime dependency"),
    )

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }
}
