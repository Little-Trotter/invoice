package de.littletrotter.application.masterdata

import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.consumerNoteFor
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.repository.LanguageRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class SenderConsumerNoteServiceTest {
    private val de = Language().edit { id = 1; code = "de"; isFallback = true }
    private val en = Language().edit { id = 2; code = "en" }
    private val sender = aSenderProfile { id = 5L; name = "Acme"; invoicePrefix = "RE-" }
    private val senders = mock<SenderProfileRepository> {
        on { findById(5L) } doReturn sender
        on { save(any()) } doAnswer { it.arguments[0] as SenderProfile }
    }
    private val languages = mock<LanguageRepository> {
        on { findActiveOrdered() } doReturn listOf(de, en)
        on { findByCode("de") } doReturn de
        on { findByCode("en") } doReturn en
    }
    private val service = SenderConsumerNoteService(senders, languages, SystemAuditActorProvider())

    @Test
    fun `load without own text returns enabled switch and defaults for every active language`() {
        val dto = service.loadConsumerNote(5L)

        assertEquals(5L, dto.senderProfileId)
        assertEquals(true, dto.enabled)
        assertEquals(emptyMap<String, String>(), dto.texts)
        assertEquals(setOf("de", "en"), dto.defaultTexts.keys)
        assertEquals(
            "Bitte bewahren Sie diese Rechnung zwei Jahre auf. Die enthaltenen Arbeits-, Maschinen- und Fahrtkosten können Sie bei Ihrer Steuererklärung angeben.",
            dto.defaultTexts["de"],
        )
    }

    @Test
    fun `save stores switch and texts and clearing a language removes it`() {
        service.saveConsumerNote(5L, false, mapOf("de" to "Eigener Text", "en" to "Own text"))
        assertFalse(sender.consumerNoteEnabled)
        val loaded = service.loadConsumerNote(5L)
        assertEquals(mapOf("de" to "Eigener Text", "en" to "Own text"), loaded.texts)

        service.saveConsumerNote(5L, true, mapOf("en" to " "))

        assertEquals(mapOf("de" to "Eigener Text"), service.loadConsumerNote(5L).texts)
        verify(senders, org.mockito.kotlin.times(2)).save(sender)
    }

    @Test
    fun `save ignores unknown language codes and leaves absent codes untouched`() {
        service.saveConsumerNote(5L, true, mapOf("de" to "Text"))

        service.saveConsumerNote(5L, true, mapOf("xx" to "ignored"))

        assertEquals("Text", sender.consumerNoteFor("de"))
    }

    @Test
    fun `unknown sender is rejected on load and save`() {
        assertThrows(SenderProfileNotFoundException::class.java) { service.loadConsumerNote(9L) }
        assertThrows(SenderProfileNotFoundException::class.java) { service.saveConsumerNote(9L, true, emptyMap()) }
    }
}
