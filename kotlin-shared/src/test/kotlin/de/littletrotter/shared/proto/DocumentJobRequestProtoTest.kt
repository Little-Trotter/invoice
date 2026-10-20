package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class DocumentJobRequestProtoTest {

    @Test
    fun `build DocumentJobRequest and roundtrip`() {
        val request = DocumentJobRequest.newBuilder()
            .setId("job-100")
            .setType(JobType.PDF_EXPORT)
            .setCorrelationId("corr-100")
            .build()

        val parsed = DocumentJobRequest.parseFrom(request.toByteArray())

        assertThat(parsed.id).isEqualTo("job-100")
        assertThat(parsed.type).isEqualTo(JobType.PDF_EXPORT)
        assertThat(parsed.correlationId).isEqualTo("corr-100")
    }
}
