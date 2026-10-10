package de.littletrotter.application.invoice

import com.sun.net.httpserver.HttpExchange
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.RustFsTestResource
import de.littletrotter.application.invoice.port.inbound.DocumentResolution
import de.littletrotter.application.invoice.port.inbound.ResolveInvoiceDocumentUseCase
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.ReproductionNotSendableException
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.RestoreSource
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.infrastructure.mail.MailStubServerResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The mail-component seams of the document recovery, with `mail.enabled=true` and a stub mail
 * component on a local port: the journal restore (T3) and the server-side send gate (T6).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(RustFsTestResource::class)
@QuarkusTestResource(MailStubServerResource::class, restrictToAnnotatedClass = true)
class InvoiceDocumentRecoveryMailIT : DocumentRecoveryItSupport() {

    @Inject
    lateinit var resolver: ResolveInvoiceDocumentUseCase

    @Inject
    lateinit var mailDispatchService: InvoiceMailDispatchService

    @Inject
    lateinit var generationJobRepository: GenerationJobRepository

    @AfterEach
    fun removeStubContext() {
        runCatching { MailStubServerResource.server.removeContext(ATTACHMENT_PATH) }
    }

    @Test
    fun `T3 - sent invoice without a stored copy is restored from the mail journal`() {
        assertRestoredFromMailJournal(issuedInvoice(sent = true))
    }

    private fun assertRestoredFromMailJournal(f: IssuedInvoiceFixture) {
        documentStore.delete(f.pdfKey)
        MailStubServerResource.server.createContext(ATTACHMENT_PATH) { exchange: HttpExchange ->
            val query = exchange.requestURI.rawQuery
            val matches = query.contains("sha256=${f.pdfSha}") && query.contains("referenceKey=${f.number}") &&
                exchange.requestHeaders.getFirst("Authorization") == "Bearer test-token"
            if (matches) {
                exchange.responseHeaders.add("X-Mail-Journal-Id", "4711")
                exchange.responseHeaders.add("X-Mail-Journal-File", "message.eml")
                exchange.sendResponseHeaders(200, f.pdfBytes.size.toLong())
                exchange.responseBody.write(f.pdfBytes)
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }

        val result = resolver.resolveForInvoice(f.invoiceId)

        assertEquals(DocumentResolution.Restored(f.pdfKey, RestoreSource.MAIL_JOURNAL), result)
        assertArrayEquals(f.pdfBytes, documentStore.get(f.pdfKey))
        val ctx = journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_RESTORED).single().contextJson
        assert(ctx.contains("\"journal_id\": 4711") || ctx.contains("\"journal_id\":4711")) { ctx }
        assert(ctx.contains("MAIL_JOURNAL")) { ctx }
        assert(ctx.contains("message.eml")) { ctx }
    }

    @Test
    fun `an unknown copy at the mail component falls back to a reproduction`() {
        val f = issuedInvoice(sent = true)
        documentStore.delete(f.pdfKey)
        MailStubServerResource.server.createContext(ATTACHMENT_PATH) { exchange: HttpExchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val result = resolver.resolveForInvoice(f.invoiceId)

        assertEquals(true, (result as DocumentResolution.RegenerationStarted).reproduction)
    }

    @Test
    fun `T6 - the send is refused for a reproduced document and the rejection stays journaled`() {
        val f = issuedInvoice(sent = true)
        documentStore.delete(f.pdfKey)
        MailStubServerResource.server.createContext(ATTACHMENT_PATH) { exchange: HttpExchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val started = resolver.resolveForInvoice(f.invoiceId) as DocumentResolution.RegenerationStarted
        val newBytes = "%PDF reproduced ${f.number}".toByteArray()
        val newKey = jobKey(f.number, f.invoiceId, started.jobId.toString())
        documentStore.put(newKey, newBytes, "application/pdf")
        generationJobResultHandler.markCompleted(
            requireNotNull(generationJobRepository.findById(started.jobId)),
            """{"objectKey":"$newKey","sha256":"${Sha256Hex.of(newBytes)}"}""",
        )

        val rejection = assertThrows(ReproductionNotSendableException::class.java) { mailDispatchService.compose(f.invoiceId) }

        assertEquals("DOCUMENT_REPRODUCTION_NOT_SENDABLE", rejection.code)
        val rows = journal(f.invoiceId, InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED)
        assertEquals(1, rows.size)
        assert(rows.single().contextJson.contains(Sha256Hex.of(newBytes))) { rows.single().contextJson }
    }

    private companion object {
        const val ATTACHMENT_PATH = "/mail/api/journal/attachment"
    }
}
