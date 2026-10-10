package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.upload.Upload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The receipt upload control must not fall back to Vaadin's English "Drop files here". */
class ReceiptUploadI18nKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `upload form carries German drop zone, add button and error texts`() {
        val form = ReceiptUploadForm("hint") { _, _, _, _ -> }
        val i18n = form.getComponent()._get<Upload>().i18n

        assertNotNull(i18n)
        assertEquals("Datei hier ablegen", i18n.dropFiles.one)
        assertEquals("Dateien hier ablegen", i18n.dropFiles.many)
        assertEquals("Datei auswählen …", i18n.addFiles.one)
        assertEquals("Die Datei ist zu groß.", i18n.error.fileIsTooBig)
        assertEquals("Ungültiger Dateityp. Erlaubt ist nur PDF.", i18n.error.incorrectFileType)
        assertEquals("Upload nicht erlaubt.", i18n.uploading.error.forbidden)
    }
}
