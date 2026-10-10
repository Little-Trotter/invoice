package de.littletrotter.ui.view.mail

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.streams.DownloadResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MailPreviewCardKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun descendants(root: Component): List<Component> = root.children.toList().flatMap { listOf(it) + descendants(it) }

    private val attachments = listOf(MailPreviewAttachment("a.pdf", 1000, 0), MailPreviewAttachment("b.xml", 2000, 1))

    @Test
    fun `with a download source every attachment line is a new-tab link addressed by its index`() {
        val card = MailPreviewCard("Vorschau")
        val requested = mutableListOf<Int>()

        card.show(
            MailPreviewHeader("Betreff", attachments = attachments, attachmentDownload = { requested += it; DownloadResponse.error(404) }),
            "Text",
        )

        val anchors = descendants(card).filterIsInstance<Anchor>()
        assertEquals(2, anchors.size)
        anchors.forEach { assertEquals("_blank", it.target.orElse(null)) }
        assertTrue(anchors[0].text.startsWith("a.pdf"))
        assertTrue(anchors[1].text.startsWith("b.xml"))
        assertTrue(card.element.textRecursively.contains("b.xml"))
    }

    @Test
    fun `without a download source the attachment lines stay plain text`() {
        val card = MailPreviewCard("Vorschau")

        card.show(MailPreviewHeader("Betreff", attachments = attachments), "Text")

        assertTrue(descendants(card).filterIsInstance<Anchor>().isEmpty())
        assertTrue(card.element.textRecursively.contains("a.pdf"))
    }
}
