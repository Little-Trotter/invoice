package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class JobResultProtoTest {

    @Test
    fun `map, status and toBuilder`() {
        val builder = JobResult.newBuilder()
            .setId("job-1")
            .setStatus(JobStatus.COMPLETED)
            .putResultRefs("pdf", "http://example.com/job-1.pdf")
            .setKositReport("report-contents")

        val job = builder.build()
        val parsed = JobResult.parseFrom(job.toByteArray())

        assertThat(parsed.resultRefsMap).containsEntry("pdf", "http://example.com/job-1.pdf")
        assertThat(parsed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(parsed.kositReport).isEqualTo("report-contents")

        val modified = job.toBuilder().setCorrelationId("corr-1").build()
        assertThat(modified.correlationId).isEqualTo("corr-1")
    }
}
