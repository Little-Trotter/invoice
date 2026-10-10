package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.SenderConsumerNoteDto
import de.littletrotter.application.invoice.MultilingualTextsFeatureConfig
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.masterdata.port.inbound.EditSenderConsumerNoteUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The "Privatkunden" tab of the sender dialog: only for the crafts wording profile, defaults
 *  prefilled, saved through the narrow consumer-note port. */
class SenderProfileConsumerNoteKaribuTest {

    private val sellerProfileService: SellerProfileService = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val languageQueries: LanguageQueryPort = mock()
    private val consumerNotes: EditSenderConsumerNoteUseCase = mock()
    private val wordingProfile: WordingProfileQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(emptyList())
        whenever(languageQueries.findActiveLanguages()).thenReturn(
            listOf(LanguageDto(id = 1, code = "de", isFallback = true, sortOrder = 1), LanguageDto(id = 2, code = "en", isFallback = false, sortOrder = 2)),
        )
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): SenderProfileView = SenderProfileView(
        sellerProfileService,
        SenderB2bSectionComposer(b2bFieldDefinitionQueries, mock(), mock()),
        SenderProfileDefaultsComposer(mock()),
        SenderConsumerNoteComposer(languageQueries, consumerNotes, wordingProfile),
        mock(),
        PartyFormExtensionRegistry(emptyList()),
        SellerVatRegistrationsExtensionRegistry(emptyList()),
        MultilingualTextsFeatureConfig(true),
    )

    private fun note(enabled: Boolean = true, texts: Map<String, String> = emptyMap()) = SenderConsumerNoteDto(
        senderProfileId = 5L,
        enabled = enabled,
        texts = texts,
        defaultTexts = mapOf("de" to "Standard de", "en" to "Standard en"),
    )

    private fun invoke(view: SenderProfileView, name: String, vararg args: Pair<Class<*>, Any?>) {
        val m = SenderProfileView::class.java.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
        m.isAccessible = true
        m.invoke(view, *args.map { it.second }.toTypedArray())
    }

    private fun open(view: SenderProfileView, id: Long?) =
        invoke(view, "readFormData", SenderProfileFormData::class.java to SenderProfileFormData(id = id))

    private fun sections(view: SenderProfileView): SenderProfileDialogSections {
        val f = SenderProfileView::class.java.getDeclaredField("sections")
        f.isAccessible = true
        return f.get(view) as SenderProfileDialogSections
    }

    private fun fields(view: SenderProfileView): SenderConsumerNoteFields {
        val f = SenderProfileView::class.java.getDeclaredField("consumerNoteFields")
        f.isAccessible = true
        return f.get(view) as SenderConsumerNoteFields
    }

    private fun area(view: SenderProfileView, code: String): TextArea =
        fields(view).textAreasByLanguage.entries.first { it.key.code == code }.value

    private fun save(view: SenderProfileView) =
        invoke(view, "persistForm", SenderProfileFormData::class.java to SenderProfileFormData(id = 5L), SenderProfile::class.java to SenderProfile().apply { id = 5L })

    @Test
    fun `tab is hidden for the consulting and trade profiles`() {
        for (profile in listOf(WordingProfile.BERATUNG, WordingProfile.HANDEL)) {
            whenever(wordingProfile.currentProfile()).thenReturn(profile)
            val view = newView()
            open(view, null)
            assertFalse(sections(view).consumerTab!!.isVisible, "tab must be hidden for $profile")
        }
        verify(consumerNotes, never()).loadConsumerNote(any())
    }

    @Test
    fun `tab is visible for the crafts profile and loads the stored note of an existing sender`() {
        whenever(wordingProfile.currentProfile()).thenReturn(WordingProfile.HANDWERK)
        whenever(consumerNotes.loadConsumerNote(5L)).thenReturn(note(enabled = false, texts = mapOf("de" to "Eigener Text")))
        val view = newView()

        open(view, 5L)

        assertTrue(sections(view).consumerTab!!.isVisible)
        assertFalse(fields(view).enabledField.value)
        assertEquals("Eigener Text", area(view, "de").value)
        assertEquals("Standard en", area(view, "en").value, "no own text: the default wording is prefilled")
    }

    @Test
    fun `a new sender gets the switch on and the default wording of each language`() {
        whenever(wordingProfile.currentProfile()).thenReturn(WordingProfile.HANDWERK)
        val view = newView()

        open(view, null)

        assertTrue(fields(view).enabledField.value)
        assertTrue(area(view, "de").value.startsWith("Bitte bewahren"))
        assertTrue(area(view, "en").value.startsWith("Please keep"))
    }

    @Test
    fun `save sends the switch, own text and null for the untouched default`() {
        whenever(wordingProfile.currentProfile()).thenReturn(WordingProfile.HANDWERK)
        whenever(consumerNotes.loadConsumerNote(5L)).thenReturn(note())
        val view = newView()
        open(view, 5L)
        fields(view).enabledField.value = false
        area(view, "de").value = "Neuer Text"

        save(view)

        verify(consumerNotes).saveConsumerNote(eq(5L), eq(false), eq(mapOf("de" to "Neuer Text", "en" to null)))
    }

    @Test
    fun `save sends null for a blank text`() {
        whenever(wordingProfile.currentProfile()).thenReturn(WordingProfile.HANDWERK)
        whenever(consumerNotes.loadConsumerNote(5L)).thenReturn(note(texts = mapOf("de" to "Alt", "en" to "Old")))
        val view = newView()
        open(view, 5L)
        area(view, "en").value = "   "

        save(view)

        verify(consumerNotes).saveConsumerNote(eq(5L), eq(true), eq(mapOf("de" to "Alt", "en" to null)))
    }

    @Test
    fun `save leaves the notice alone when the tab is not offered`() {
        whenever(wordingProfile.currentProfile()).thenReturn(WordingProfile.HANDEL)
        val view = newView()
        open(view, 5L)

        save(view)

        verify(consumerNotes, never()).saveConsumerNote(any(), any(), any())
    }
}
