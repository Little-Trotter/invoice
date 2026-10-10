package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.wording.WordingProfile
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture

/** The lane of a job decides which stream subject and which dispatch bulkhead it uses. */
class DocumentJobLaneRoutingTest {

    private fun mapper(): InvoiceDtoMapper = object : InvoiceDtoMapper() {
        override fun mapToDto(inv: Invoice, wordingProfile: WordingProfile, issued: Boolean): InvoiceDto = InvoiceDto()
    }

    private fun job(id: Long, lane: DocumentJobLane) = GenerationJob().apply {
        this.id = id
        this.jobType = "PRINT_JOB"
        this.engine = DocumentEngine.MUSTANG
        this.invoice = Invoice()
        this.lane = lane
    }

    private fun publishedSubject(publisher: (JetStream) -> DocumentJobPublisher, job: GenerationJob): String {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture<PublishAck>())
        val subject = argumentCaptor<String>()
        publisher(jetStream).publish(job, false)
        verify(jetStream).publishAsync(subject.capture(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        return subject.firstValue
    }

    @Test
    fun `a bulk job is published on the configured bulk subject`() {
        val subject = publishedSubject(
            { DocumentJobPublisher(jetStreamProvider = { it }, jobsSubjectOverride = "bulk-x", interactiveSubjectOverride = "inter-x", mapper = mapper()) },
            job(1L, DocumentJobLane.BULK),
        )

        assertEquals("bulk-x", subject)
    }

    @Test
    fun `an interactive job is published on the configured interactive subject`() {
        val subject = publishedSubject(
            { DocumentJobPublisher(jetStreamProvider = { it }, jobsSubjectOverride = "bulk-x", interactiveSubjectOverride = "inter-x", mapper = mapper()) },
            job(2L, DocumentJobLane.INTERACTIVE),
        )

        assertEquals("inter-x", subject)
    }

    @Test
    fun `without overrides the lanes use the shared contract subjects`() {
        val publisherFactory = { js: JetStream -> DocumentJobPublisher(jetStreamProvider = { js }, mapper = mapper()) }

        assertEquals(DocumentJobSubjects.INTERACTIVE_JOBS, publishedSubject(publisherFactory, job(3L, DocumentJobLane.INTERACTIVE)))
        assertEquals(DocumentJobSubjects.JOBS, publishedSubject(publisherFactory, job(4L, DocumentJobLane.BULK)))
    }

    @Test
    fun `the publisher dispatches interactive jobs through the interactive gate method only`() {
        val gate = mock<DocumentJobDispatchGate>()
        val done = DispatchedJob(CompletableFuture.completedFuture(mock<PublishAck>()))
        whenever(gate.dispatchInteractive(any(), any(), any())).thenReturn(done)
        whenever(gate.dispatch(any(), any(), any())).thenReturn(done)
        val publisher = DocumentJobPublisher(mock<DocumentJobPublishTransaction>(), gate, mock<InvoiceRepository>())

        publisher.publish(job(5L, DocumentJobLane.INTERACTIVE), false)
        verify(gate).dispatchInteractive(any(), any(), any())
        verify(gate, never()).dispatch(any(), any(), any())

        publisher.publish(job(6L, DocumentJobLane.BULK), false)
        verify(gate).dispatch(any(), any(), any())
    }

    @Test
    fun `the interactive dispatch has a bulkhead of its own`() {
        val bulk = DocumentJobDispatchGate::class.java.getMethod(
            "dispatch", Invoice::class.java, GenerationJob::class.java, Boolean::class.javaPrimitiveType,
        )
        val interactive = DocumentJobDispatchGate::class.java.getMethod(
            "dispatchInteractive", Invoice::class.java, GenerationJob::class.java, Boolean::class.javaPrimitiveType,
        )

        assertNotNull(bulk.getAnnotation(Bulkhead::class.java))
        assertNotNull(interactive.getAnnotation(Bulkhead::class.java))
        assertTrue(bulk != interactive)
    }

    @Test
    fun `the interactive executor runs tasks on named threads and stops on shutdown`() {
        val executor = InteractiveFastPathExecutor(1, 4)
        val thread = CompletableFuture<String>()

        executor.execute { thread.complete(Thread.currentThread().name) }

        assertTrue(thread.get(5, java.util.concurrent.TimeUnit.SECONDS).startsWith("interactive-fast-path-"))
        executor.shutdown()
        org.junit.jupiter.api.assertThrows<java.util.concurrent.RejectedExecutionException> { executor.execute { } }
    }

    @Test
    fun `the interactive executor rejects work once its bounded queue is full`() {
        val executor = InteractiveFastPathExecutor(1, 1)
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        executor.execute { started.countDown(); release.await() }
        started.await(5, java.util.concurrent.TimeUnit.SECONDS)
        executor.execute { }

        org.junit.jupiter.api.assertThrows<java.util.concurrent.RejectedExecutionException> { executor.execute { } }

        release.countDown()
        executor.shutdown()
    }
}
