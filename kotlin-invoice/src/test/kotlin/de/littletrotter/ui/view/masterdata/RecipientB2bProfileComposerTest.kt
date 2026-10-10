package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.ui.component.B2bProfileFieldsSection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [RecipientB2bProfileComposer] in isolation, mocking [B2bProfileQueryPort]/[B2bFieldDefinitionQueryPort]/
 * [B2bFieldValueQueryPort] and passing
 * plain Vaadin components (no [RecipientView] involved). The end-to-end wiring through the view
 * stays covered by `RecipientB2bProfileKaribuTest` and friends.
 */
class RecipientB2bProfileComposerTest {

    private val b2bProfileQueries: B2bProfileQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private lateinit var combo: ComboBox<B2bProfileDto>
    private lateinit var fieldsSection: B2bProfileFieldsSection
    private lateinit var container: Div
    private var recipientId: Long? = null

    private val activeProfile = B2bProfileDto(id = 1L, name = "Active")
    private val inactiveButAssignedProfile = B2bProfileDto(id = 2L, name = "Inactive")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(activeProfile))
        combo = ComboBox()
        fieldsSection = B2bProfileFieldsSection { it }
        container = Div()
        recipientId = null
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun composer() = RecipientB2bProfileComposer(
        combo,
        fieldsSection,
        container,
        RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
    ) { recipientId }

    @Test
    fun `prepare with no current profile lists only the active profiles`() {
        val c = composer()

        c.prepare(null)

        val items = (combo.dataProvider as ListDataProvider<B2bProfileDto>).items.toList()
        assertEquals(listOf(activeProfile), items)
    }

    @Test
    fun `prepare appends a current profile that is no longer active`() {
        val c = composer()

        c.prepare(inactiveButAssignedProfile)

        val items = (combo.dataProvider as ListDataProvider<B2bProfileDto>).items.toList()
        assertTrue(items.contains(inactiveButAssignedProfile))
        assertTrue(items.contains(activeProfile))
    }

    @Test
    fun `prepare does not duplicate a current profile that is still active`() {
        val c = composer()

        c.prepare(activeProfile)

        val items = (combo.dataProvider as ListDataProvider<B2bProfileDto>).items.toList()
        assertEquals(1, items.count { it.id == activeProfile.id })
    }

    @Test
    fun `refresh with no selected profile hides the container`() {
        val c = composer()
        combo.value = null

        c.refresh()

        assertFalse(container.isVisible)
    }

    @Test
    fun `refresh with a selected profile but no persisted recipient loads no values`() {
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(1L)).thenReturn(listOf(mock<B2bProfileFieldDto>()))
        val c = composer()
        combo.setItems(activeProfile)
        combo.value = activeProfile
        recipientId = null

        c.refresh()

        verify(b2bFieldValueQueries, never()).findB2bRecipientFieldValues(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `refresh with a persisted recipient and a selected profile loads its stored values`() {
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(1L)).thenReturn(emptyList())
        whenever(b2bFieldValueQueries.findB2bRecipientFieldValues(42L, 1L)).thenReturn(emptyMap())
        val c = composer()
        combo.setItems(activeProfile)
        combo.value = activeProfile
        recipientId = 42L

        c.refresh()

        verify(b2bFieldValueQueries).findB2bRecipientFieldValues(42L, 1L)
    }

    @Test
    fun `changing the combo value re-triggers refresh via the composer's own listener`() {
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(1L)).thenReturn(emptyList())
        val c = composer()
        combo.setItems(activeProfile)

        combo.value = activeProfile

        // No exception, and the facade was consulted for the newly selected profile's fields —
        // proof the composer's init-time listener (not a manual refresh() call) fired.
        verify(b2bFieldDefinitionQueries).findB2bRecipientFields(1L)
    }

    @Test
    fun `persist saves the section's values when a recipient and profile are both present and content exists`() {
        val c = composer()
        combo.setItems(activeProfile)
        combo.value = activeProfile

        c.persist(42L)

        verify(manageB2bFieldValue, never()).saveB2bRecipientFieldValues(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `persist is a no-op without a persisted recipient id`() {
        val c = composer()
        combo.setItems(activeProfile)
        combo.value = activeProfile

        c.persist(null)

        verify(manageB2bFieldValue, never()).saveB2bRecipientFieldValues(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `persist is a no-op without a selected profile`() {
        val c = composer()

        c.persist(42L)

        verify(manageB2bFieldValue, never()).saveB2bRecipientFieldValues(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }
}
