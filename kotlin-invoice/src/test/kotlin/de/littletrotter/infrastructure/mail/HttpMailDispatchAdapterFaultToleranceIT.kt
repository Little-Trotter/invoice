package de.littletrotter.infrastructure.mail

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.mail.InvoiceMailAttachment
import de.littletrotter.application.mail.InvoiceMailDraft
import de.littletrotter.application.mail.InvoiceMailPlaceholderValue
import de.littletrotter.application.mail.MailDispatchPort
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.Email
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException as FtTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HttpMailDispatchAdapterTest] constructs the adapter directly, bypassing CDI
 * entirely, so its `@Retry`/`@Timeout` interceptors never actually run there. This proves the
 * interceptor chain itself is wired: a real bean injected via [MailDispatchPort] (forced on via
 * [MailStubServerResource]'s config overrides — `%test.mail.enabled=false` is the module's normal
 * default, see `application.properties`), talking to a real stub HTTP server on an ephemeral port.
 * The resource is restricted to this class: unrestricted, Quarkus would apply its `mail.enabled=true`
 * to every other integration test in the module and bind the mail consumer there.
 */
@QuarkusTest
@QuarkusTestResource(MailStubServerResource::class, restrictToAnnotatedClass = true)
class HttpMailDispatchAdapterFaultToleranceIT {

    @Inject
    lateinit var mailDispatch: MailDispatchPort

    private val attempts = AtomicInteger(0)

    private val draft = InvoiceMailDraft(
        referenceKey = "RE-2026-9001",
        documentFamily = InvoiceFormat.GERMAN,
        recipientAddress = Email("kunde@example.com"),
        recipientName = "Muster GmbH",
        placeholderValues = mapOf(
            "invoiceNumber" to InvoiceMailPlaceholderValue.Text("RE-2026-9001"),
            "invoiceDate" to InvoiceMailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
        ),
        attachments = listOf(
            InvoiceMailAttachment(
                filename = "Rechnung-RE-2026-9001.pdf",
                contentType = "application/pdf",
                sizeBytes = 1234L,
                sha256 = "c".repeat(64),
                downloadUrl = "http://rustfs:9000/invoice-documents/x.pdf?sig=1",
            ),
        ),
    )

    @BeforeEach
    fun resetStub() {
        attempts.set(0)
        runCatching { MailStubServerResource.server.removeContext("/mail/api/compose") }
    }

    @Test
    @DisplayName("a transient 503 is retried through the real CDI interceptor and succeeds on the second attempt")
    fun retryRecoversATransientFailure() {
        MailStubServerResource.server.createContext("/mail/api/compose") { exchange: HttpExchange ->
            if (attempts.incrementAndGet() == 1) {
                respond(exchange, 503, """{"error":"try again"}""")
            } else {
                respond(exchange, 200, """{"draftId":"d-retry","composeUrl":"/mail/compose/d-retry"}""")
            }
        }

        val handover = mailDispatch.compose(draft)

        assertEquals("d-retry", handover.draftId)
        assertEquals(2, attempts.get(), "exactly one retry (configured maxRetries=1 here) must have been used")
    }

    @Test
    @DisplayName("a response slower than the configured Timeout is aborted by the real CDI interceptor")
    fun timeoutAbortsASlowResponse() {
        MailStubServerResource.server.createContext("/mail/api/compose") { exchange: HttpExchange ->
            attempts.incrementAndGet()
            Thread.sleep(1500)
            respond(exchange, 200, """{"draftId":"d-slow","composeUrl":"/mail/compose/d-slow"}""")
        }

        assertThrows(FtTimeoutException::class.java) { mailDispatch.compose(draft) }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }
}

/**
 * Starts a stub [HttpServer] on an ephemeral port before Quarkus config is resolved,
 * and forces `mail.enabled=true` plus a shrunk retry/timeout budget (50ms/800ms instead of the
 * 500ms/20000ms production default) so the tests run in well under a second instead of tens of
 * seconds.
 */
class MailStubServerResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        return mapOf(
            "mail.enabled" to "true",
            "mail.base-url" to "http://127.0.0.1:${server.address.port}",
            "mail.integration-token" to "test-token",
            "invoice.mail.retry.max-retries" to "1",
            "invoice.mail.retry.delay-ms" to "50",
            "invoice.mail.timeout-ms" to "800",
        )
    }

    override fun stop() {
        server.stop(0)
    }

    companion object {
        lateinit var server: HttpServer
    }
}
