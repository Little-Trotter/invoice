package de.littletrotter.ui.settings

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsQueryPort
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.UpdateArchiveHandoverSettingsUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.ui.view.settings.ArchiveHandoverSettingsCard
import de.littletrotter.ui.view.settings.ArchiveHandoverSettingsCardFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@DisplayName("ArchiveHandoverSettingsCard and its factory")
class ArchiveHandoverSettingsCardKaribuTest {

    private val query: ArchiveHandoverSettingsQueryPort = mock()
    private val updater: UpdateArchiveHandoverSettingsUseCase = mock()
    private val roles: AppRoleProvider = mock()
    private val stored = ArchiveHandoverSettings("archiv@example.com", true, false, Instant.parse("2026-10-01T10:00:00Z"), "anna")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(query.settings()).thenReturn(stored)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun card(mayEdit: Boolean = true) = ArchiveHandoverSettingsCard(query, updater, mayEdit) { _, _ -> Div() }

    @Test
    fun `the card shows the stored address and both switches`() {
        val card = card()

        assertEquals("archiv@example.com", card.address.value)
        assertTrue(card.invoiceSwitch.value)
        assertFalse(card.quoteSwitch.value)
        assertTrue(card.address.isEnabled)
        assertTrue(card.saveButton.isEnabled)
    }

    @Test
    fun `saving sends the whole setting and shows what the component stored`() {
        whenever(updater.update(any())).thenReturn(stored.copy(address = "neu@example.com", quoteEnabled = true))
        val card = card()
        card.address.value = "neu@example.com"
        card.quoteSwitch.value = true

        card.save()

        verify(updater).update(ArchiveHandoverSettingsUpdate("neu@example.com", invoiceEnabled = true, quoteEnabled = true))
        assertEquals("neu@example.com", card.address.value)
    }

    @Test
    fun `a cleared address is saved as none`() {
        whenever(updater.update(any())).thenReturn(stored.copy(address = null))
        val card = card()
        card.address.value = ""

        card.save()

        verify(updater).update(ArchiveHandoverSettingsUpdate(null, invoiceEnabled = true, quoteEnabled = false))
    }

    @Test
    fun `an invalid address is refused before anything is sent`() {
        val card = card()
        card.address.value = "kein-mail"
        clearNotifications()

        card.save()

        verify(updater, never()).update(any())
        assertEquals(1, getNotifications().size)
    }

    @Test
    fun `a failed save is reported`() {
        whenever(updater.update(any())).thenThrow(IllegalStateException("down"))
        val card = card()
        clearNotifications()

        card.save()

        assertEquals(1, getNotifications().size)
    }

    @Test
    fun `an unreadable setting leaves the form disabled instead of showing defaults`() {
        whenever(query.settings()).thenThrow(IllegalStateException("down"))

        val card = card()

        assertEquals("", card.address.value)
        assertFalse(card.address.isEnabled)
        assertFalse(card.saveButton.isEnabled)
        assertEquals(1, getNotifications().size)
    }

    @Test
    fun `without the right to change it the fields are read-only and the button is hidden`() {
        val card = card(mayEdit = false)

        assertFalse(card.address.isEnabled)
        assertFalse(card.invoiceSwitch.isEnabled)
        assertFalse(card.saveButton.parent.get().let { (it as com.vaadin.flow.component.Component).isVisible })
    }

    @Test
    fun `the factory builds the card only for an administrator of a deployment with the mail component`() {
        whenever(roles.hasPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)).thenReturn(true)
        assertNotNull(ArchiveHandoverSettingsCardFactory(MailAvailability(true), query, updater, roles).create { _, _ -> Div() })
        assertNull(ArchiveHandoverSettingsCardFactory(MailAvailability(false), query, updater, roles).create { _, _ -> Div() })

        whenever(roles.hasPermission(AppPermission.MAIL_ARCHIVE_HANDOVER_ADMIN)).thenReturn(false)
        assertNull(ArchiveHandoverSettingsCardFactory(MailAvailability(true), query, updater, roles).create { _, _ -> Div() })
    }
}
