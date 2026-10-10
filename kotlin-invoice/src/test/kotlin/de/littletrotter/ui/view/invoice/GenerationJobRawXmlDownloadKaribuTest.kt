package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Covers [rawXmlDownloadAnchor]'s in-memory download: bytes, encoding and metadata. */
class GenerationJobRawXmlDownloadKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    @Test
    fun `happy path - the anchor streams the raw report as an XML download under the given file name`() {
        val anchor = rawXmlDownloadAnchor("kosit-1.xml", "<report/>", "Download")

        val response = DownloadAnchorTestSupport.invoke(anchor)

        assertEquals("kosit-1.xml", response.fileName)
        assertEquals("application/xml", response.contentType)
        assertArrayEquals("<report/>".toByteArray(), response.inputStream.use { it.readAllBytes() })
    }

    @Test
    fun `boundary - an empty report streams zero bytes`() {
        val response = DownloadAnchorTestSupport.invoke(rawXmlDownloadAnchor("empty.xml", "", "Download"))

        assertEquals(0L, response.contentLength)
    }

    @Test
    fun `boundary - umlauts are encoded as UTF-8 and the content length counts bytes, not characters`() {
        val xml = "<t>Prüfung</t>"

        val response = DownloadAnchorTestSupport.invoke(rawXmlDownloadAnchor("u.xml", xml, "Download"))

        assertEquals(xml.toByteArray(Charsets.UTF_8).size.toLong(), response.contentLength)
        assertEquals(xml.length + 1L, response.contentLength, "ü takes two bytes in UTF-8")
    }

    @Test
    fun `boundary - the anchor is a download link carrying the labelled button`() {
        val anchor = rawXmlDownloadAnchor("r.xml", "<r/>", "Roh-XML")

        assertTrue(anchor.element.hasAttribute("download"))
        assertEquals("Roh-XML", anchor.children.toList().filterIsInstance<Button>().single().text)
    }
}
