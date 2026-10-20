package de.littletrotter.infrastructure.logging

import io.quarkus.logging.LoggingFilter
import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * A browser that reloads or navigates away aborts its in-flight requests; the server then reports
 * the broken connection as ERROR although the client is simply gone and nothing is user-visible.
 * Only the known abort signatures are demoted to DEBUG, each bound to the exception type and the
 * code path that raises it; every other failure from the same loggers keeps ERROR:
 * - push: long-poll disconnect races reported by the Vaadin push handlers, including a request
 *   object that Atmosphere already cancelled when the handler inspects it;
 * - Atmosphere: cancelling a resource whose request or response is already gone;
 * - UIDL: Undertow "response already committed" raised while the UIDL response is written, and a
 *   closed HTTP connection raised while the request body is read.
 */
@LoggingFilter(name = "client-abort")
class ClientAbortLogFilter : Filter {
    override fun isLoggable(record: LogRecord): Boolean {
        val logger = record.loggerName
        val aborted = when (logger) {
            PUSH_LOGGER, PUSH_HANDLER_LOGGER ->
                chain(record.thrown).any { it.isResponseAlreadySent() || it.isNullEventResource() || it.isCancelledRequest() }
            ATMOSPHERE_PROCESSOR_LOGGER -> isCancelResourceAbort(record)
            UIDL_ERROR_LOGGER -> isUidlAbort(chain(record.thrown).toList())
            else -> false
        }
        if (!aborted) return true
        record.level = DEBUG
        // Handlers only check their own level before the filter runs, so the demoted record
        // must be dropped here unless DEBUG is actually enabled for this category.
        return Logger.getLogger(logger).isLoggable(DEBUG)
    }

    private fun chain(thrown: Throwable?): Sequence<Throwable> =
        generateSequence(thrown) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)

    private fun Throwable.isResponseAlreadySent() =
        this is IllegalStateException && message?.startsWith(UNDERTOW_RESPONSE_SENT) == true

    private fun Throwable.isNullEventResource() =
        this is NullPointerException &&
            stackTrace.any { it.className.startsWith(SESSION_FACTORY) && it.methodName == "onDisconnect" }

    private fun Throwable.isCancelledRequest() =
        this is IllegalStateException && message?.startsWith(REQUEST_CANCELLED) == true &&
            stackTrace.any { it.className == PUSH_HANDLER && it.methodName in PUSH_HANDLER_METHODS }

    // Cancelling a resource of a disconnected client either hits the already released request
    // (null request in the content type lookup) or fails to write to the already committed
    // response, which Atmosphere wraps as RuntimeException(IOException(IllegalStateException)).
    private fun isCancelResourceAbort(record: LogRecord): Boolean {
        if (record.message?.startsWith(CANCEL_FAILED) != true) return false
        val causes = chain(record.thrown).toList()
        val releasedRequest = causes.any {
            it is NullPointerException &&
                it.stackTrace.any { f -> f.className == ATMOSPHERE_REQUEST && f.methodName == "getContentType" }
        }
        val committedResponse = causes.firstOrNull() is RuntimeException &&
            causes.any { it is java.io.IOException } && causes.any { it.isResponseAlreadySent() }
        return releasedRequest || committedResponse
    }

    private fun Throwable.isUidlResponseCommitAbort() =
        isResponseAlreadySent() &&
            stackTrace.any { it.className == UIDL_HANDLER && it.methodName == "commitJsonResponse" }

    private fun isUidlAbort(causes: List<Throwable>) =
        causes.any { it.isUidlResponseCommitAbort() } || isRequestReadAbort(causes)

    // The transport reports the closed connection as a plain IOException that only carries the
    // exception name in its message, so type and message are both accepted; the origin is the
    // request body read.
    private fun isRequestReadAbort(causes: List<Throwable>) =
        causes.any { it.isHttpClosed() } &&
            causes.any { t -> t.stackTrace.any { it.className == REQUEST_HANDLER && it.methodName == "getRequestBody" } }

    private fun Throwable.isHttpClosed() =
        javaClass.simpleName == HTTP_CLOSED || message?.contains(HTTP_CLOSED_MESSAGE) == true
}

private const val PUSH_LOGGER = "com.vaadin.flow.server.communication.PushAtmosphereHandler"
private const val PUSH_HANDLER_LOGGER = "com.vaadin.flow.server.communication.PushHandler"
private const val PUSH_HANDLER = PUSH_HANDLER_LOGGER
private val PUSH_HANDLER_METHODS = setOf("isDebugWindowConnection", "handleConnectionLost")
private const val REQUEST_CANCELLED = "Request object no longer valid"
private const val ATMOSPHERE_PROCESSOR_LOGGER = "org.atmosphere.cpr.AsynchronousProcessor"
private const val ATMOSPHERE_REQUEST = "org.atmosphere.cpr.AtmosphereRequestImpl"
private const val CANCEL_FAILED = "Failed to cancel resource"
private const val UIDL_ERROR_LOGGER = "com.vaadin.flow.server.DefaultErrorHandler"
private const val UIDL_HANDLER = "com.vaadin.flow.server.communication.UidlRequestHandler"
private const val UNDERTOW_RESPONSE_SENT = "UT000127"
private const val REQUEST_HANDLER = "com.vaadin.flow.server.SynchronizedRequestHandler"
private const val HTTP_CLOSED = "HttpClosedException"
private const val HTTP_CLOSED_MESSAGE = "HttpClosedException: Connection was closed"
private const val SESSION_FACTORY = "org.atmosphere.cpr.DefaultAtmosphereResourceSessionFactory"
private const val MAX_CAUSE_DEPTH = 16
private val DEBUG: Level = org.jboss.logmanager.Level.DEBUG
