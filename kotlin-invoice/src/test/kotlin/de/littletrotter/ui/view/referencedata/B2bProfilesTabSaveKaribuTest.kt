package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldDefinitionUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bProfileUseCase
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The profile editor dialog's save path through [AbstractCrudView] (new profile, edited
 * profile, binder validation failure, facade rejection such as a duplicate name). The rest of
 * [B2bProfilesTab] is pinned by [B2bProfilesTabKaribuTest].
 */
class B2bProfilesTabSaveKaribuTest {

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

    private inline fun <reified T> field(owner: Class<*>, target: Any, name: String): T {
        val f = owner.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    private fun editorDialog(view: B2bProfilesTab): Dialog = field(AbstractCrudView::class.java, view, "editorDialog")
    private fun saveButton(view: B2bProfilesTab): Button = field(AbstractCrudView::class.java, view, "saveButton")
    private fun nameField(view: B2bProfilesTab): TextField = field(B2bProfilesTab::class.java, view, "nameField")

    private fun openEditor(view: B2bProfilesTab, profile: B2bProfileDto?) {
        val m = AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
        m.isAccessible = true
        m.invoke(view, profile)
    }

    @Test
    fun `happy path - saving a new named profile persists a fresh entity and closes the dialog`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        openEditor(view, null)
        nameField(view).value = "Kunde Neu"

        saveButton(view).click()

        val captor = argumentCaptor<B2bProfileDto>()
        verify(manageB2bProfile).saveB2bProfile(captor.capture())
        assertEquals("Kunde Neu", captor.firstValue.name)
        assertEquals(null, captor.firstValue.id)
        assertFalse(editorDialog(view).isOpened)
    }

    @Test
    fun `boundary - a blank profile name fails validation and never reaches the facade`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        openEditor(view, null)
        nameField(view).value = ""

        saveButton(view).click()

        verify(manageB2bProfile, never()).saveB2bProfile(any())
        assertTrue(editorDialog(view).isOpened)
        assertTrue(nameField(view).isInvalid)
    }

    @Test
    fun `boundary - a duplicate name rejected by the facade keeps the dialog open for correction`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        whenever(manageB2bProfile.saveB2bProfile(any())).thenThrow(IllegalArgumentException("B2B profile name already exists"))
        openEditor(view, null)
        nameField(view).value = "Kunde Doppelt"

        saveButton(view).click()

        assertTrue(editorDialog(view).isOpened)
    }

    @Test
    fun `boundary - saving an edited profile sends the freshly loaded row's id and revision, not a new row`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val persisted = B2bProfileDto(id = 41L, version = 3L, name = "Kunde Alt")
        whenever(b2bProfileQueries.findB2bProfileById(41L)).thenReturn(persisted)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(41L)).thenReturn(emptyList())
        openEditor(view, persisted)
        nameField(view).value = "Kunde Umbenannt"

        saveButton(view).click()

        val captor = argumentCaptor<B2bProfileDto>()
        verify(manageB2bProfile).saveB2bProfile(captor.capture())
        assertEquals(persisted.copy(name = "Kunde Umbenannt"), captor.firstValue)
    }

    @Test
    fun `boundary - an edited profile deleted concurrently is reported instead of re-created`() {
        val view = B2bProfilesTab(b2bProfileQueries, manageB2bProfile, b2bFieldDefinitionQueries, manageB2bFieldDefinition, time = FIXED_TIME_SOURCE)
        val persisted = B2bProfileDto(id = 42L, name = "Kunde Weg")
        whenever(b2bProfileQueries.findB2bProfileById(42L)).thenReturn(persisted)
        whenever(b2bFieldDefinitionQueries.findB2bProfileFields(42L)).thenReturn(emptyList())
        openEditor(view, persisted)
        whenever(b2bProfileQueries.findB2bProfileById(42L)).thenReturn(null)

        saveButton(view).click()

        verify(manageB2bProfile, never()).saveB2bProfile(any())
        assertTrue(editorDialog(view).isOpened)
    }
}
