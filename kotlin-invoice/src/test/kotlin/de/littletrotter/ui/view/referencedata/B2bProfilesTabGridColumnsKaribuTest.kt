package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getColumnByKey
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.DataTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

private fun profileField(term: B2bTerm?, requirement: B2bFieldRequirement) =
    B2bProfileFieldDto(term = term, requirement = requirement)

/**
 * [B2bProfilesTab.configureProfileGridColumns]' plain `ValueProvider` columns
 * (description, "Felder" count, "Verwendung" count) are never invoked by Grid unless a row is
 * actually formatted — the same gap [SenderProfileViewGridColumnsKaribuTest] and
 * [RecipientViewGridColumnsKaribuTest] already closed for their own grids.
 * [B2bProfilesTabKaribuTest] reflects directly into cell-renderer *methods*
 * (`requirementBadge`, `defaultValueCell`, ...) instead, which never exercises a column's own
 * `ValueProvider` lambda — that's a different code path, formatted here via `_getFormattedRow`.
 */
class B2bProfilesTabGridColumnsKaribuTest {

    private val b2bProfileQueries: B2bProfileQueryPort = mock()

    private val manageB2bProfile: ManageB2bProfileUseCase = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val manageB2bFieldDefinition: ManageB2bFieldDefinitionUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun profileGrid(view: B2bProfilesTab): DataTable<B2bProfileDto> {
        val f = B2bProfilesTab::class.java.getDeclaredField("profileGrid")
        f.isAccessible = true
        return f.get(view).requireAs<DataTable<B2bProfileDto>>()
    }

    /** [B2bProfilesTab.refreshProfileGrid] is private — reflects into it the same way
     *  [B2bProfilesTabKaribuTest] reflects into the tab's other private members. */
    private fun refreshProfileGrid(view: B2bProfilesTab) {
        val m = B2bProfilesTab::class.java.getDeclaredMethod("refreshProfileGrid")
        m.isAccessible = true
        m.invoke(view)
    }

    // ── Merged identity column (name + description two-line cell), index 0 ───────────────
    // Grid.toPrettyString() (what _getFormattedRow relies on) never recurses into a
    // ComponentRenderer's children, so a wrapping Div only ever formats as its own flat
    // attributes ("Div[@class='...']") regardless of what's nested inside. Asserting on the
    // actual primary/secondary spans therefore needs the rendered component itself, not the
    // grid's string dump — the same approach DataTableKaribuTest uses at the helper level.
    private fun identityCell(view: B2bProfilesTab, profile: B2bProfileDto): Div {
        val column = profileGrid(view)._getColumnByKey("name")
        return column.componentFor(profile) as Div
    }

    @Test
    fun `the grid has five columns and only the identity column grows`() {
        val columns = profileGrid(B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)).columns

        assertEquals(5, columns.size, "identity, status, fields, usage, actions")
        assertEquals("name", columns[0].key)
        assertEquals(TextColumnWidth.LARGE.grow, columns[0].flexGrow)
        // An auto-width identity column would size itself to the longest description again.
        assertFalse(columns[0].isAutoWidth, "identity column takes the remaining width")
        columns.drop(1).forEach { assertEquals(0, it.flexGrow, "scan columns stay compact") }
    }

    @Test
    fun `the identity column renders only the primary line for a profile with no description`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 1L, name = "Kunde", description = null)

        // twoLineTextColumn renders a Div containing one Span (primary) when secondary is
        // blank/null — no second, empty span for the missing description.
        val cell = identityCell(view, profile)
        val children = cell.children.toList()

        assertEquals(1, children.size, "no secondary line for a null description")
        assertEquals("Kunde", (children[0] as Span).text)
        assertEquals("Kunde", children[0].element.getAttribute("title"))
        assertTrue(children[0].element.classList.contains("lt-cell-two-line__primary"))
    }

    @Test
    fun `the identity column renders both lines when a description is present`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 1L, name = "Kunde", description = "Standardprofil")

        val cell = identityCell(view, profile)
        val children = cell.children.toList()

        assertEquals(2, children.size)
        assertEquals("Kunde", (children[0] as Span).text)
        assertTrue(children[0].element.classList.contains("lt-cell-two-line__primary"))
        assertEquals("Standardprofil", (children[1] as Span).text)
        assertEquals("Standardprofil", children[1].element.getAttribute("title"))
        assertTrue(children[1].element.classList.contains("lt-cell-two-line__secondary"))
    }

    // ── "Felder" (field count) column: profile.id ?: -1 feeding the facade lookup ───────────────

    @Test
    fun `the field-count column queries the facade with -1 for a not-yet-persisted profile`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(-1L)).thenReturn(emptyList())
        val profile = B2bProfileDto(id = null, name = "Kunde")

        val row = profileGrid(view)._getFormattedRow(profile)

        assertEquals("0", row[2], "field-count column falls back to the -1 lookup, finding nothing")
    }

    @Test
    fun `the field-count column queries the facade with the profile's real id`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(7L)).thenReturn(
            listOf(B2bProfileFieldDto(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.OPTIONAL)),
        )
        val profile = B2bProfileDto(id = 7L, name = "Kunde")

        val row = profileGrid(view)._getFormattedRow(profile)

        assertEquals("1", row[2], "field-count column")
    }

    // ── "Verwendung" (usage count) column: usageCounts[it.id] ?: 0L ─────────────────────────────

    @Test
    fun `the usage-count column reads 0 for a profile absent from the usage-counts map`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.findB2bProfileUsageCounts()).thenReturn(emptyMap())
        refreshProfileGrid(view) // (re)seeds the tab's own usageCounts field from the facade above
        val profile = B2bProfileDto(id = 9L, name = "Kunde")

        val row = profileGrid(view)._getFormattedRow(profile)

        assertEquals("0", row[3], "usage-count column")
    }

    @Test
    fun `the usage-count column reads the pre-fetched count for a profile present in the usage-counts map`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(b2bProfileQueries.findB2bProfileUsageCounts()).thenReturn(mapOf(9L to 4L))
        refreshProfileGrid(view)
        val profile = B2bProfileDto(id = 9L, name = "Kunde")

        val row = profileGrid(view)._getFormattedRow(profile)

        assertEquals("4", row[3], "usage-count column")
    }

    // ── status column: statusBadge(active) rendered through the component column ────────────────

    @Test
    fun `the status column renders the active badge for an active profile`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 20L, name = "Kunde", active = true)

        val row = profileGrid(view)._getFormattedRow(profile)

        assertEquals(
            "StatusChip[text='Aktiv', @class='status-chip status-chip--ok', @role='status']",
            row[1],
            "status column: active",
        )
    }

    @Test
    fun `the status column renders the inactive badge for an inactive profile`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 21L, name = "Kunde", active = false)

        val row = profileGrid(view)._getFormattedRow(profile)

        assertEquals(
            "StatusChip[text='Inaktiv', @class='status-chip status-chip--neutral', @role='status']",
            row[1],
            "status column: inactive",
        )
    }

    // ── rules grid's "BT-Code" column: field.term?.officialCode.orEmpty() — never previously
    //    formatted (0/4 branches covered), same gap as the profile grid's own columns above ──────

    private fun invokeReadFormData(view: B2bProfilesTab, formData: B2bProfileFormData) {
        val m = B2bProfilesTab::class.java.getDeclaredMethod("readFormData", B2bProfileFormData::class.java)
        m.isAccessible = true
        m.invoke(view, formData)
    }

    private fun rulesTable(view: B2bProfilesTab): DataTable<B2bProfileFieldDto> {
        val f = B2bProfilesTab::class.java.getDeclaredField("fieldsSection")
        f.isAccessible = true
        val content = f.get(view) as com.vaadin.flow.component.orderedlayout.VerticalLayout
        return content.children.toList().filterIsInstance<DataTable<B2bProfileFieldDto>>().first()
    }

    @Test
    fun `the rules grid's BT-code column is blank for a field with no term`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 30L, name = "Kunde")
        val fieldWithNoTerm = profileField(term = null, requirement = B2bFieldRequirement.OPTIONAL).copy(id = 1L)
        whenever(b2bProfileQueries.findB2bProfileById(30L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(30L)).thenReturn(listOf(fieldWithNoTerm))
        whenever(b2bProfileQueries.findB2bProfileUsageNames(30L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 30L, name = "Kunde"))

        val row = rulesTable(view)._getFormattedRow(fieldWithNoTerm)

        assertEquals("", row[0], "BT-code column: no term selected yet")
    }

    @Test
    fun `the rules grid's BT-code column shows the term's official code`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val profile = B2bProfileDto(id = 31L, name = "Kunde")
        val fieldWithTerm = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.OPTIONAL).copy(id = 2L)
        whenever(b2bProfileQueries.findB2bProfileById(31L)).thenReturn(profile)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(31L)).thenReturn(listOf(fieldWithTerm))
        whenever(b2bProfileQueries.findB2bProfileUsageNames(31L)).thenReturn(emptyList())
        invokeReadFormData(view, B2bProfileFormData(id = 31L, name = "Kunde"))

        val row = rulesTable(view)._getFormattedRow(fieldWithTerm)

        assertEquals("BT-19", row[0], "BT-code column")
    }
}
