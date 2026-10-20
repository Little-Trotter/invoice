package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand
import de.littletrotter.shared.quote.AddAttachmentLibraryVersionRequest
import de.littletrotter.shared.quote.AttachFromLibraryRequest
import de.littletrotter.shared.quote.AttachmentLibraryEntryView
import de.littletrotter.shared.quote.AttachmentLibraryVersionView
import de.littletrotter.shared.quote.CreateAttachmentLibraryEntryRequest
import de.littletrotter.shared.quote.ReplaceLibraryVersionRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import de.littletrotter.shared.quote.QuoteWireCodec as Codec

@DisplayName("Receipt pool adapters (/quote/api/attachment-library, /attachments/from-library)")
class HttpAttachmentLibraryAdaptersTest {

    private val stub = QuoteStubServer()
    private val api = QuoteStubServer.client(stub.baseUrl)
    private val day = LocalDate.of(2026, 11, 1)
    private val version = AttachmentLibraryVersionView("v-1", 1, "agb.pdf", "application/pdf", 4, "h", day, Instant.EPOCH, "anna")

    @AfterEach
    fun tearDown() = stub.close()

    @Test
    fun `entries maps the pool with its versions`() {
        val view = AttachmentLibraryEntryView("l-1", "AGB", "Allgemeine Geschäftsbedingungen", version, listOf(version), 3, false)
        stub.respond("/quote/api/attachment-library", 200, Codec.encode(listOf(view)))

        val entry = HttpAttachmentLibraryQueryAdapter(api).entries().single()

        assertEquals("AGB", entry.title)
        assertEquals(3, entry.usedByQuotes)
        assertEquals("v-1", entry.currentVersion.id)
        assertEquals(1, entry.versions.size)
    }

    @Test
    fun `a refused pool list reaches the caller`() {
        stub.respond("/quote/api/attachment-library", 403, """{"status":403,"message":"keine Berechtigung"}""")

        val e = assertThrows(QuoteServiceException::class.java) { HttpAttachmentLibraryQueryAdapter(api).entries() }

        assertEquals("keine Berechtigung", e.message)
    }

    @Test
    fun `version content takes name and type from the answer headers`() {
        val bytes = byteArrayOf(1, 2, 3)
        stub.respondBytes(
            "/quote/api/attachment-library/l-1/versions/v-1/content", 200, bytes, "application/pdf",
            mapOf("Content-Disposition" to "attachment; filename*=UTF-8''AGB%20Fassung%201.pdf"),
        )

        val content = HttpAttachmentLibraryQueryAdapter(api).versionContent("l-1", "v-1")

        assertEquals("AGB Fassung 1.pdf", content.fileName)
        assertEquals("application/pdf", content.mimeType)
        assertArrayEquals(bytes, content.bytes)
    }

    @Test
    fun `version content falls back to defaults and reports a missing version`() {
        stub.respondBytes("/quote/api/attachment-library/l-1/versions/v-2/content", 200, byteArrayOf(1), "")
        stub.respond("/quote/api/attachment-library/l-1/versions/v-9/content", 404, """{"status":404,"message":"Fassung fehlt"}""")
        val adapter = HttpAttachmentLibraryQueryAdapter(api)

        val content = adapter.versionContent("l-1", "v-2")

        assertEquals("attachment", content.fileName)
        assertEquals("application/octet-stream", content.mimeType)
        assertThrows(QuoteNotFoundException::class.java) { adapter.versionContent("l-1", "v-9") }
    }

    @Test
    fun `create posts the first file base64 encoded and returns the entry id`() {
        stub.respond("/quote/api/attachment-library", 201, """{"id":"l-7"}""")
        val file = AttachmentLibraryFile("agb.pdf", "application/pdf", byteArrayOf(1, 2), day)

        val id = HttpMaintainAttachmentLibraryAdapter(api).create(NewAttachmentLibraryEntry("AGB", "Bedingungen", file))

        assertEquals("l-7", id.id)
        assertEquals("POST", stub.last().method)
        assertEquals(
            CreateAttachmentLibraryEntryRequest("AGB", "Bedingungen", "agb.pdf", "application/pdf", Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), day),
            Codec.decode(stub.last().body, "request", CreateAttachmentLibraryEntryRequest::class.java),
        )
    }

    @Test
    fun `add version posts to the entry and returns the version id`() {
        stub.respond("/quote/api/attachment-library/l-1/versions", 201, """{"id":"v-2"}""")

        val id = HttpMaintainAttachmentLibraryAdapter(api).addVersion("l-1", AttachmentLibraryFile("agb2.pdf", "application/pdf", byteArrayOf(3)))

        assertEquals("v-2", id.id)
        assertEquals(
            AddAttachmentLibraryVersionRequest("agb2.pdf", "application/pdf", Base64.getEncoder().encodeToString(byteArrayOf(3)), null),
            Codec.decode(stub.last().body, "request", AddAttachmentLibraryVersionRequest::class.java),
        )
    }

    @Test
    fun `an identical file as new version is refused with the component's explanation`() {
        stub.respond("/quote/api/attachment-library/l-1/versions", 409, """{"status":409,"message":"Fassung identisch"}""")

        val e = assertThrows(QuoteServiceException::class.java) {
            HttpMaintainAttachmentLibraryAdapter(api).addVersion("l-1", AttachmentLibraryFile("agb.pdf", "application/pdf", byteArrayOf(1)))
        }

        assertEquals("Fassung identisch", e.message)
    }

    @Test
    fun `archive posts to the archive path`() {
        stub.respond("/quote/api/attachment-library/l-1/archive", 204, "")

        HttpMaintainAttachmentLibraryAdapter(api).archive("l-1")

        assertEquals("POST", stub.last().method)
    }

    @Test
    fun `a refused archive reaches the caller`() {
        stub.respond("/quote/api/attachment-library/l-1/archive", 403, """{"status":403,"message":"Archivieren nicht erlaubt"}""")

        val e = assertThrows(QuoteServiceException::class.java) { HttpMaintainAttachmentLibraryAdapter(api).archive("l-1") }

        assertEquals("Archivieren nicht erlaubt", e.message)
    }

    @Test
    fun `attach posts the version and the line and returns the attachment id`() {
        stub.respond("/quote/api/quotes/q-1/attachments/from-library", 201, """{"id":"a-5"}""")

        val id = HttpAttachLibraryDocumentAdapter(api).attach(AttachLibraryDocumentCommand("q-1", "v-1", "i-2"))

        assertEquals("a-5", id)
        assertEquals(AttachFromLibraryRequest("v-1", "i-2"), Codec.decode(stub.last().body, "request", AttachFromLibraryRequest::class.java))
    }

    @Test
    fun `attaching in a non-draft offer is refused`() {
        stub.respond("/quote/api/quotes/q-1/attachments/from-library", 409, """{"status":409,"message":"Angebot ist nicht im Entwurf"}""")

        val e = assertThrows(QuoteServiceException::class.java) {
            HttpAttachLibraryDocumentAdapter(api).attach(AttachLibraryDocumentCommand("q-1", "v-1"))
        }

        assertEquals("Angebot ist nicht im Entwurf", e.message)
    }

    @Test
    fun `replace version puts the new version id`() {
        stub.respond("/quote/api/quotes/q-1/attachments/a-5/version", 204, "")

        HttpAttachLibraryDocumentAdapter(api).replaceVersion(ReplaceLibraryVersionCommand("q-1", "a-5", "v-2"))

        assertEquals("PUT", stub.last().method)
        assertEquals(ReplaceLibraryVersionRequest("v-2"), Codec.decode(stub.last().body, "request", ReplaceLibraryVersionRequest::class.java))
    }

    @Test
    fun `library routes encode identifiers as single path segments`() {
        assertEquals("/api/attachment-library", QuoteRoutes.library())
        assertEquals("/api/attachment-library/a%20b/versions/x%2Fy/content", QuoteRoutes.library("/{id}/versions/{vid}/content", "a b", "x/y"))
    }
}
