package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand
import de.littletrotter.application.quote.port.outbound.AttachLibraryDocumentRemote
import de.littletrotter.application.quote.port.outbound.AttachmentLibraryRemote
import de.littletrotter.application.quote.port.outbound.MaintainAttachmentLibraryRemote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AttachmentLibraryServicesTest {

    private val file = AttachmentLibraryFile("agb.pdf", "application/pdf", byteArrayOf(1))

    @Test
    fun `the query service reads entries and version content through the remote`() {
        val remote = mock<AttachmentLibraryRemote>()
        val content = QuoteAttachmentContent("agb.pdf", "application/pdf", byteArrayOf(1))
        whenever(remote.entries()).thenReturn(emptyList())
        whenever(remote.versionContent("l-1", "v-1")).thenReturn(content)
        val service = AttachmentLibraryQueryService(remote)

        assertEquals(emptyList<Any>(), service.entries())
        assertSame(content, service.versionContent("l-1", "v-1"))
    }

    @Test
    fun `the maintain service creates, versions and archives through the remote`() {
        val remote = mock<MaintainAttachmentLibraryRemote>()
        val entry = NewAttachmentLibraryEntry("AGB", "Bedingungen", file)
        whenever(remote.create(entry)).thenReturn("l-1")
        whenever(remote.addVersion("l-1", file)).thenReturn("v-2")
        val service = MaintainAttachmentLibraryService(remote)

        assertEquals("l-1", service.create(entry))
        assertEquals("v-2", service.addVersion("l-1", file))
        service.archive("l-1")

        verify(remote).archive("l-1")
    }

    @Test
    fun `the attach service takes a version over and swaps it through the remote`() {
        val remote = mock<AttachLibraryDocumentRemote>()
        val attach = AttachLibraryDocumentCommand("q-1", "v-1", null)
        val replace = ReplaceLibraryVersionCommand("q-1", "a-1", "v-2")
        whenever(remote.attach(attach)).thenReturn("a-1")
        val service = AttachLibraryDocumentService(remote)

        assertEquals("a-1", service.attach(attach))
        service.replaceVersion(replace)

        verify(remote).replaceVersion(replace)
    }
}
