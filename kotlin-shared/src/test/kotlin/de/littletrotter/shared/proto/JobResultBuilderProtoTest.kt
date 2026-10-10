package de.littletrotter.shared.proto

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class JobResultBuilderProtoTest {
    @Test
    fun `jobresult builder map operations`() {
        val b = JobResult.newBuilder()
            .setId("job-x")
            .putResultRefs("a", "1")
            .putResultRefs("b", "2")
        val m = mapOf("c" to "3", "d" to "4")
        b.putAllResultRefs(m)
        val built = b.build()

        assertThat(built.resultRefsMap).containsEntry("a", "1")
        assertThat(built.resultRefsMap).containsEntry("c", "3")

        // toBuilder modifications
        val modified = built.toBuilder().removeResultRefs("a").build()
        assertThat(modified.resultRefsMap).doesNotContainKey("a")
    }
}
