package de.littletrotter.infrastructure.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.jboss.logmanager.Level as JbossLevel

class ClientAbortLogFilterTest {
    private val filter = ClientAbortLogFilter()
    private val push = "com.vaadin.flow.server.communication.PushAtmosphereHandler"

    @AfterEach
    fun resetLogger() {
        Logger.getLogger(push).level = null
    }

    private fun record(logger: String, thrown: Throwable?) =
        LogRecord(Level.SEVERE, "Exception in push connection").apply {
            loggerName = logger
            this.thrown = thrown
        }

    private fun nullEventResource(): NullPointerException =
        NullPointerException("event.getResource()").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "org.atmosphere.cpr.DefaultAtmosphereResourceSessionFactory\$1",
                    "onDisconnect",
                    "X.java",
                    1,
                ),
            )
        }

    @Test
    fun `response already sent in cause chain is demoted and dropped at default level`() {
        val r = record(push, RuntimeException("wrap", IllegalStateException("UT000127: Response has already been sent")))
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `demoted record is kept when debug is enabled for the push logger`() {
        Logger.getLogger(push).level = JbossLevel.DEBUG
        val r = record(push, IllegalStateException("UT000127: Response has already been sent"))
        assertTrue(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `null resource in session factory disconnect is demoted`() {
        val r = record(push, nullEventResource())
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `other exceptions from the push logger stay error`() {
        val r = record(push, IOException("Broken pipe"))
        assertTrue(filter.isLoggable(r))
        assertEquals(Level.SEVERE, r.level)
    }

    @Test
    fun `unrelated null pointer and record without exception stay error`() {
        val npe = record(push, NullPointerException("x"))
        val plain = record(push, null)
        assertTrue(filter.isLoggable(npe))
        assertTrue(filter.isLoggable(plain))
        assertEquals(Level.SEVERE, npe.level)
    }

    @Test
    fun `same message from another logger stays error`() {
        val r = record("org.other.Handler", IllegalStateException("UT000127: Response has already been sent"))
        assertTrue(filter.isLoggable(r))
        assertEquals(Level.SEVERE, r.level)
    }

    // Same simple name as the Vert.x exception; the filter matches by name so no transport class is needed.
    private class HttpClosedException(message: String) : IOException(message)

    private val uidlLogger = "com.vaadin.flow.server.DefaultErrorHandler"
    private val uidlHandler = "com.vaadin.flow.server.communication.UidlRequestHandler"

    private fun <T : Throwable> T.withFrame(className: String, method: String): T = apply {
        stackTrace = arrayOf(StackTraceElement(className, method, "X.java", 1))
    }

    private fun committed(method: String = "commitJsonResponse") =
        IllegalStateException("UT000127: Response has already been sent").withFrame(uidlHandler, method)

    private val requestHandler = "com.vaadin.flow.server.SynchronizedRequestHandler"
    private val closedMessage = "io.vertx.core.http.HttpClosedException: Connection was closed"

    // Real shape: outer IOException reading the body, cause IOException that only carries the name.
    private fun httpClosed(frameClass: String = requestHandler, method: String = "getRequestBody") =
        IOException(closedMessage, IOException(closedMessage)).withFrame(frameClass, method)

    @AfterEach
    fun resetUidlLogger() {
        Logger.getLogger(uidlLogger).level = null
    }

    @Test
    fun `uidl response commit abort is demoted and dropped at default level`() {
        val r = record(uidlLogger, committed())
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `uidl response commit abort nested in cause chain is demoted`() {
        val r = record(uidlLogger, RuntimeException("wrap", committed()))
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `uidl demoted record is kept when debug is enabled`() {
        Logger.getLogger(uidlLogger).level = JbossLevel.DEBUG
        val r = record(uidlLogger, committed())
        assertTrue(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `closed connection while reading uidl request is demoted`() {
        val r = record(uidlLogger, RuntimeException("wrap", httpClosed()))
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `response already sent outside commitJsonResponse stays error`() {
        val elsewhere = record(uidlLogger, committed("other"))
        val noFrame = record(uidlLogger, IllegalStateException("UT000127: Response has already been sent"))
        assertTrue(filter.isLoggable(elsewhere))
        assertTrue(filter.isLoggable(noFrame))
        assertEquals(Level.SEVERE, elsewhere.level)
        assertEquals(Level.SEVERE, noFrame.level)
    }

    @Test
    fun `other exception in commitJsonResponse stays error`() {
        val r = record(uidlLogger, IllegalStateException("boom").withFrame(uidlHandler, "commitJsonResponse"))
        assertTrue(filter.isLoggable(r))
        assertEquals(Level.SEVERE, r.level)
    }

    @Test
    fun `closed connection by exception type in cause chain is demoted`() {
        val cause = HttpClosedException("Connection was closed")
        val r = record(uidlLogger, IOException("read failed", cause).withFrame(requestHandler, "getRequestBody"))
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `closed connection outside request body reading stays error`() {
        val elsewhere = record(uidlLogger, httpClosed("org.example.Other", "getRequestBody"))
        val otherMethod = record(uidlLogger, httpClosed(method = "handleRequest"))
        assertTrue(filter.isLoggable(elsewhere))
        assertTrue(filter.isLoggable(otherMethod))
        assertEquals(Level.SEVERE, otherMethod.level)
    }

    @Test
    fun `other io exception while reading request body stays error`() {
        val r = record(uidlLogger, IOException("Broken pipe").withFrame(requestHandler, "getRequestBody"))
        assertTrue(filter.isLoggable(r))
        assertEquals(Level.SEVERE, r.level)
    }

    @Test
    fun `uidl signatures from another logger stay error`() {
        val r = record("org.other.Handler", committed())
        assertTrue(filter.isLoggable(r))
        assertEquals(Level.SEVERE, r.level)
    }

    @Test
    fun `push signature does not apply to the uidl logger`() {
        val r = record(uidlLogger, nullEventResource())
        assertTrue(filter.isLoggable(r))
        assertEquals(Level.SEVERE, r.level)
    }

    private val pushHandler = "com.vaadin.flow.server.communication.PushHandler"
    private val processor = "org.atmosphere.cpr.AsynchronousProcessor"
    private val cancelledMessage = "Request object no longer valid. This object has been cancelled"

    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "X.java", 1)

    private fun cancelled(cls: String = pushHandler, method: String = "isDebugWindowConnection") =
        IllegalStateException(cancelledMessage).apply { stackTrace = arrayOf(frame(cls, method)) }

    private fun cancelRecord(logger: String, thrown: Throwable?, message: String = "Failed to cancel resource") =
        LogRecord(Level.SEVERE, message).apply {
            loggerName = logger
            this.thrown = thrown
        }

    private fun nullRequest(method: String = "getContentType") =
        NullPointerException("request").apply {
            stackTrace = arrayOf(frame("org.atmosphere.cpr.AtmosphereRequestImpl", method))
        }

    private fun wrappedCommit(inner: Throwable = IllegalStateException("UT000127: Response has already been sent")) =
        RuntimeException(IOException(inner))

    @Test
    fun `cancelled request in push handler is demoted for both frames and both loggers`() {
        listOf("isDebugWindowConnection", "handleConnectionLost").forEach { m ->
            assertFalse(filter.isLoggable(record(pushHandler, cancelled(method = m))))
        }
        assertFalse(filter.isLoggable(record(push, cancelled())))
    }

    @Test
    fun `cancelled request negatives stay error`() {
        val elsewhere = record(pushHandler, cancelled(cls = "org.example.Other"))
        val otherMethod = record(pushHandler, cancelled(method = "other"))
        val otherMessage = record(
            pushHandler,
            IllegalStateException("boom").apply { stackTrace = arrayOf(frame(pushHandler, "handleConnectionLost")) },
        )
        val otherLogger = record("org.other.Handler", cancelled())
        listOf(elsewhere, otherMethod, otherMessage, otherLogger).forEach {
            assertTrue(filter.isLoggable(it))
            assertEquals(Level.SEVERE, it.level)
        }
    }

    @Test
    fun `failed cancel with null request in content type is demoted`() {
        val r = cancelRecord(processor, nullRequest())
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `failed cancel with wrapped committed response is demoted`() {
        val r = cancelRecord(processor, wrappedCommit())
        assertFalse(filter.isLoggable(r))
        assertEquals(JbossLevel.DEBUG, r.level)
    }

    @Test
    fun `failed cancel negatives stay error`() {
        val other = listOf(
            cancelRecord(processor, nullRequest("getHeader")),
            cancelRecord(processor, NullPointerException("request")),
            cancelRecord(processor, wrappedCommit(IllegalStateException("other"))),
            cancelRecord(processor, IllegalStateException("UT000127: Response has already been sent")),
            cancelRecord(processor, RuntimeException(IllegalStateException("UT000127: x"))),
            cancelRecord(processor, nullRequest(), message = "Failed to something else"),
            cancelRecord("org.other.Processor", nullRequest()),
            cancelRecord(processor, null),
        )
        other.forEach {
            assertTrue(filter.isLoggable(it))
            assertEquals(Level.SEVERE, it.level)
        }
    }
}
