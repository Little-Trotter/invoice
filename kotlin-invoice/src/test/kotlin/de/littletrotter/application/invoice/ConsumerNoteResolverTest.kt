package de.littletrotter.application.invoice

import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.doReturn

class ConsumerNoteResolverTest {
    private val port = mock<WordingProfileQueryPort> { on { currentProfile() } doReturn WordingProfile.HANDWERK }
    private val resolver = ConsumerNoteResolver(port)

    @Test
    fun `without a sender nothing applies but the context is kept`() {
        assertNull(resolver.defaultFor(null, "de", CustomerType.CONSUMER))
        val source = resolver.sourceFor(null, "de", CustomerType.CONSUMER)
        assertNull(source.senderProfileId)
        assertEquals("CONSUMER", source.sourceContext)
    }

    @Test
    fun `unknown recipient type yields an empty context`() {
        assertEquals("", resolver.sourceFor(null, "de", null).sourceContext)
    }

    @Test
    fun `default text exists in both document languages`() {
        assertEquals(true, ConsumerNoteDefaults.defaultText("de").startsWith("Bitte bewahren"))
        assertEquals(true, ConsumerNoteDefaults.defaultText("en").startsWith("Please keep"))
    }

    private fun senderWith(vararg texts: Pair<String, String>) = de.littletrotter.domain.masterdata.SenderProfile().apply {
        texts.forEachIndexed { i, (code, value) ->
            changeConsumerNote(
                de.littletrotter.domain.reference.Language().edit { id = i + 1; this.code = code; isFallback = code == "de" },
                value,
            )
        }
    }

    @Test
    fun `english document of a sender with only german text gets the english default`() {
        val text = resolver.textFor(senderWith("de" to "Nur deutsch"), "en")
        assertEquals(ConsumerNoteDefaults.defaultText("en"), text)
    }

    @Test
    fun `english document uses the english own text when maintained`() {
        assertEquals("Own english", resolver.textFor(senderWith("de" to "Deutsch", "en" to "Own english"), "en"))
    }
}
