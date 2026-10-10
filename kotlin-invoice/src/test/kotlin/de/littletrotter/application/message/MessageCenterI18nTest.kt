package de.littletrotter.application.message

import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every `message.center.*` key [MessageCenterEventHandler] uses must exist in the
 * i18n bundle with a German value (task doc's Definition of Done). `UiTranslations.tr(key, ...)`
 * falls back to `"!{key}!"` for a missing key (see its own KDoc) — that is exactly what these
 * tests must never see. Not tested here: `"ui.error.raw"` (producer 2) — that key is
 * deliberately NOT in the bundle, its `resolved_text` is always the caller's already-resolved
 * message (see `UiNotifications.persistError`'s KDoc).
 */
class MessageCenterI18nTest {

    @Test
    fun `documentGenerated kositValid key resolves to a German value`() {
        val text = UiTranslations.tr("message.center.documentGenerated.kositValid", "RE-1")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("LT-Prüfung"))
        assertTrue(text.contains("bestanden"))
    }

    @Test
    fun `documentGenerated kositFailed key resolves to a German value`() {
        val text = UiTranslations.tr("message.center.documentGenerated.kositFailed", "RE-1")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("fehlgeschlagen"))
    }

    @Test
    fun `generationJob failed key resolves to a German value with two positional args`() {
        val text = UiTranslations.tr("message.center.generationJob.failed", "RE-1", "Timeout")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("Timeout"))
        assertTrue(text.contains("Dokumenterzeugung"))
    }

    @Test
    fun `generationJob exhausted key resolves to a German value with two positional args`() {
        val text = UiTranslations.tr("message.center.generationJob.exhausted", "RE-1", "5")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("5"))
        assertTrue(text.contains("E-Rechnungs-Auftrag"))
    }

    @Test
    fun `invoice sent key resolves to a German value`() {
        val text = UiTranslations.tr("message.center.invoice.sent", "RE-1")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("versendet"))
    }

    @Test
    fun `invoice cancelled key resolves to a German value`() {
        val text = UiTranslations.tr("message.center.invoice.cancelled", "RE-1", "ST-1")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("ST-1"))
        assertTrue(text.contains("storniert"))
    }

    @Test
    fun `llmAnalysis resolved key resolves to a German value with two positional args`() {
        val text = UiTranslations.tr("message.center.llmAnalysis.resolved", "RE-1", "abgeschlossen")
        assertFalse(text.startsWith("!"), "key must resolve: $text")
        assertTrue(text.contains("RE-1"))
        assertTrue(text.contains("LLM-Analyse"))
        assertTrue(text.contains("abgeschlossen"))
    }
}
